package com.beiwang.memo.ui

import android.content.Context
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import com.beiwang.memo.data.Blocks
import com.beiwang.memo.data.Ids
import com.beiwang.memo.data.Layout
import com.beiwang.memo.data.Note
import com.beiwang.memo.data.Media
import com.beiwang.memo.data.Store
import com.beiwang.memo.legacy.LegacyMigration
import com.beiwang.memo.ui.editor.VideoPlayback
import com.beiwang.memo.ui.editor.VoicePlayer
import com.beiwang.memo.ui.editor.VoiceRecording
import com.beiwang.memo.ui.theme.BgCrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 底部弹出的面板 */
sealed interface Sheet {
    data object Settings : Sheet
    data object Trash : Sheet
    /** id = null 表示新建分类 */
    data class CategoryEdit(val id: String?) : Sheet
    data object Move : Sheet
    data object Unlock : Sheet
    /** 保险箱里多选后「移出到」某个分类 */
    data object MoveOut : Sheet
    /** 手机之间直传：发送 / 接收 */
    data class Transfer(val sending: Boolean) : Sheet
    /** 先挑内容，再导出成文件（[share] = false）或用系统分享发出去（[share] = true） */
    data class Export(val share: Boolean) : Sheet
}

/** 保险箱密码界面的特殊流程（设置/解锁由状态自动推出，不在这里） */
sealed interface VaultFlow {
    /** 改密码：先验证旧密码 */
    data object ChangeVerify : VaultFlow
    /** 改密码：输入新密码 */
    data object ChangeNew : VaultFlow
    /** 别的设备传来的保险箱内容：输入原设备的保险箱密码 */
    data class Foreign(val id: String) : VaultFlow
    /** 在设置里开启指纹：先验证密码（指纹要包住内容密钥，锁着时拿不到密钥） */
    data object EnableBio : VaultFlow
}

class ToastSpec(val message: String, val undo: (() -> Unit)?, val id: Long = System.nanoTime())

class DialogSpec(
    val title: String,
    val message: String,
    val confirm: String,
    val danger: Boolean = false,
    val cancel: String? = "取消",
    val onConfirm: () -> Unit,
)

/** 编辑页里的一段：文字（一个输入框）或一组附件。key 是 [Blocks] 里的稳定标识 */
@Stable
sealed interface EditBlock {
    val key: Long
}

@Stable
class TextEdit(override val key: Long, text: String) : EditBlock {
    val state = TextFieldState(text)
    val focus = FocusRequester()
    /** 最近一次排版结果（拖附件时找段落边界用） */
    var layout: (() -> TextLayoutResult?)? = null
    var coords: LayoutCoordinates? = null
}

@Stable
class GroupEdit(override val key: Long, items: List<Media>) : EditBlock {
    var items by mutableStateOf(items)
    var coords: LayoutCoordinates? = null
}

/**
 * 正在编辑的笔记：标题一个输入框；正文拆成「文字段 / 附件组」交替的序列（见 [Blocks]），每段文字一个输入框。
 * 停手 0.4 秒自动保存：保存时再合回「纯文字正文 + 每个附件在正文里的位置」。
 */
@Stable
class EditorSession(val base: Note, val layout: Layout, val isNew: Boolean, val vault: Boolean = false) {
    val id: String get() = base.id
    val title = TextFieldState(base.title)
    var blocks by mutableStateOf(build(Blocks.split(base.body, base.media), emptyMap()))
        private set
    var pinned by mutableStateOf(base.pinned)
    /** 最近一次写盘的时间，用来闪一下「已保存」 */
    var savedAt by mutableLongStateOf(0L)
    /** 是否已经写进过数据库（新建但一个字没写的不入库） */
    var persisted = !isNew
    /** 结构每变一次（插入、删除、挪动、改大小）+1：自动保存靠它知道附件变了 */
    var revision by mutableIntStateOf(0)
        private set
    /** 最近有过光标的文字段：从工具条加附件时插在它的光标处 */
    var lastFocused: TextEdit? = null
    /** 选中的附件（显示拖角，底部工具条换成对齐/删除） */
    var selected by mutableStateOf<String?>(null)

    val media: List<Media> get() = blocks.flatMap { if (it is GroupEdit) it.items else emptyList() }

    fun pieces(): List<Blocks.Piece> = blocks.map { b ->
        when (b) {
            is TextEdit -> Blocks.Text(b.key, b.state.text.toString())
            is GroupEdit -> Blocks.Group(b.key, b.items)
        }
    }

    /** 换成新的序列：没动过的文字段沿用原来的输入框（光标、输入法都不断） */
    fun apply(next: List<Blocks.Piece>) {
        blocks = build(next, blocks.associateBy { it.key })
        revision++
    }

    /** 插附件的位置：最近的光标处；从没点过正文就放文末 */
    fun insertTarget(): Blocks.Target {
        val t = lastFocused?.takeIf { f -> blocks.any { it === f } }
            ?: return Blocks.end(pieces())
        return Blocks.IntoText(t.key, t.state.selection.min)
    }

    /**
     * 在光标处插入附件；之后「光标」挪到附件后面那段文字的开头，连着加几次会依次往下排。
     * 不主动弹键盘：刚从相册回来，先让人看到加进来的东西。
     */
    fun insert(items: List<Media>) {
        if (items.isEmpty()) return
        apply(Blocks.insert(pieces(), insertTarget(), items))
        val gi = blocks.indexOfFirst { it is GroupEdit && it.items.any { m -> m.id == items.last().id } }
        (blocks.getOrNull(gi + 1) as? TextEdit)?.let { t ->
            lastFocused = t
            t.state.edit { selection = TextRange(0) }
        }
    }

    fun snapshot(now: Long): Note {
        val (body, media) = Blocks.join(pieces())
        return base.copy(title = title.text.toString(), body = body, media = media, pinned = pinned, updated = now)
    }

    /** 和库里的一样吗（比标准形：旧数据里「文末」的附件位置是 -1，打开再关上不算改动） */
    fun sameAs(n: Note): Boolean {
        if (n.title != title.text.toString() || n.pinned != pinned) return false
        return Blocks.canonical(n.body, n.media) == Blocks.join(pieces())
    }

    private fun build(pieces: List<Blocks.Piece>, old: Map<Long, EditBlock>): List<EditBlock> = pieces.map { p ->
        when (p) {
            is Blocks.Text -> (old[p.key] as? TextEdit)?.also {
                if (it.state.text.toString() != p.text) it.state.setTextAndPlaceCursorAtEnd(p.text)
            } ?: TextEdit(p.key, p.text)
            is Blocks.Group -> (old[p.key] as? GroupEdit)?.also { it.items = p.items } ?: GroupEdit(p.key, p.items)
        }
    }
}

/**
 * 界面状态（不入库的那部分）：当前在看什么、选中了哪些、开着哪个面板。
 * 数据本身都在 [Store]。
 */
@Stable
class AppState(val store: Store) {

    var query by mutableStateOf("")
    var searching by mutableStateOf(false)
    /** 搜索胶囊在屏幕上的位置：搜索时点它以外的地方就退出搜索（Root 里判断） */
    var searchBounds = Rect.Zero

    var selection by mutableStateOf<Set<String>>(emptySet())
        private set
    val selecting: Boolean get() = selection.isNotEmpty()

    var editor by mutableStateOf<EditorSession?>(null)
        private set
    private var _viewer by mutableStateOf<Media?>(null)
    /** 正在看的大图；换图时旋转归零 */
    var viewer: Media?
        get() = _viewer
        set(v) {
            _viewer = v
            viewerTurns = 0
        }
    var viewerTurns by mutableIntStateOf(0)

    /** 看大图 / 看视频（按附件种类，Root 里决定用哪个界面） */
    fun openViewer(m: Media) {
        viewer = m
    }
    var sheet by mutableStateOf<Sheet?>(null)
    var dialog by mutableStateOf<DialogSpec?>(null)
    var toast by mutableStateOf<ToastSpec?>(null)
        private set
    /** 条目式分类里顶部的「记一条」输入行 */
    var quickAdd by mutableStateOf(false)
    /** 换背景：选好图后的缩放裁剪页 */
    var bgCrop by mutableStateOf<BgCrop?>(null)

    /** 编辑页里的语音条播放（同一时间只放一条） */
    val voice = VoicePlayer(store)
    /** 正在录音：编辑页底部换成录音条 */
    var recording by mutableStateOf<VoiceRecording?>(null)
    /** 正在看的视频：取景层放画面，悬浮层放按钮，共用这一个播放器（看视频的界面自己建、自己释放） */
    var video by mutableStateOf<VideoPlayback?>(null)

    /** 保险箱界面开着（未解锁时显示密码盘） */
    var vaultOpen by mutableStateOf(false)
    var vaultFlow by mutableStateOf<VaultFlow?>(null)
    /** 在外面选了笔记「移到保险箱」但保险箱还锁着：解锁后再移 */
    var pendingVaultMove by mutableStateOf<Set<String>>(emptySet())

    /** 手机有没有可用的指纹（设置打开时在后台问一次系统） */
    var bioAvailable by mutableStateOf(false)

    /** 旧数据迁移进度：null = 没在搬；否则 (已处理, 总数) */
    var migrating by mutableStateOf<Pair<Int, Int>?>(null)

    private var toastJob: Job? = null

    // ---------------- 提示 ----------------

    fun showToast(message: String, undo: (() -> Unit)? = null) {
        val t = ToastSpec(message, undo)
        toast = t
        toastJob?.cancel()
        toastJob = store.scope.launch {
            delay(if (undo != null) 4200 else 2000)
            if (toast === t) toast = null
        }
    }

    fun dismissToast() {
        toast = null
    }

    // ---------------- 多选 ----------------

    fun toggleSelect(id: String) {
        selection = if (id in selection) selection - id else selection + id
    }

    fun selectAll(ids: List<String>) {
        selection = if (selection.containsAll(ids)) emptySet() else ids.toSet()
    }

    fun clearSelection() {
        selection = emptySet()
    }

    // ---------------- 编辑 ----------------

    /** 打开笔记。保险箱里的笔记传进来的是解密后的明文 */
    fun open(note: Note) {
        val s = store.data.value
        if (note.encrypted) {
            sheet = Sheet.Unlock
            return
        }
        if (note.vault) {
            editor = EditorSession(note, Layout.Cards, isNew = false, vault = true)
            return
        }
        val layout = s.category(note.cat)?.layout ?: Layout.Cards
        quickAdd = false
        editor = EditorSession(note, layout, isNew = false)
    }

    /** 在当前分类新建：卡片式打开编辑页；条目式在列表顶部出一行输入框 */
    fun create() {
        val s = store.data.value
        clearSelection()
        if (vaultOpen) {
            if (!store.vault.unlocked.value) return
            val now = System.currentTimeMillis()
            editor = EditorSession(Note(id = Ids.next(), cat = Ids.NOTE, created = now, updated = now, vault = true),
                Layout.Cards, isNew = true, vault = true)
            return
        }
        val cat = s.category(store.prefs.currentCat.value) ?: s.category(Ids.NOTE) ?: return
        searching = false
        if (cat.layout == Layout.List) {
            quickAdd = true
            return
        }
        val now = System.currentTimeMillis()
        editor = EditorSession(Note(id = Ids.next(), cat = cat.id, created = now, updated = now), cat.layout, isNew = true)
    }

    /** 自动保存：内容没变不写；新建且还是空白的不写 */
    fun saveEditor() {
        val e = editor ?: return
        if (e.vault && !store.vault.unlocked.value) return
        val stored = store.data.value.note(e.id)
        val current = if (e.vault && stored != null) store.vault.openNote(stored) else stored
        if (current != null && e.sameAs(current)) return
        val n = e.snapshot(System.currentTimeMillis())
        if (!e.persisted && n.isBlank) return
        if (e.vault) store.saveVaultNote(n) else store.saveNote(n)
        e.persisted = true
        e.savedAt = System.currentTimeMillis()
    }

    /**
     * 结束录音，插到正在编辑的笔记的光标处。太短（不到半秒）或出错时提示。
     * 直接在主线程做：普通笔记只是挪个文件；保险箱里的要加密，语音不大（一分钟约 0.5MB），也很快。
     */
    fun finishRecording() {
        val r = recording ?: return
        recording = null
        val dur = r.stop()
        val e = editor
        if (dur == null) {
            showToast("太短了，没录上")
            return
        }
        if (e == null || (e.vault && !store.vault.unlocked.value)) {
            store.clips.recordingFile(r.id).delete()
            return
        }
        val m = store.clips.adoptRecording(r.id, dur, if (e.vault) store.vault::sealFile else null)
        if (m == null) showToast("录音没存上") else e.insert(listOf(m))
    }

    fun cancelRecording() {
        recording?.cancel()
        recording = null
    }

    /** 关闭编辑页：先落盘；一个字都没有的笔记直接丢掉（不进回收站，旧版会留下「空白笔记」） */
    fun closeEditor() {
        finishRecording()
        voice.stop()
        val e = editor ?: return
        val n = e.snapshot(System.currentTimeMillis())
        if (n.isBlank) {
            if (e.persisted) store.purge(listOf(e.id))
        } else {
            saveEditor()
        }
        editor = null
        viewer = null
    }

    fun trashEditing() {
        val e = editor ?: return
        saveEditor()
        val id = e.id
        editor = null
        if (e.persisted) {
            store.trash(listOf(id))
            showToast("已移入回收站") { store.restore(listOf(id)) }
        }
    }

    // ---------------- 背景 ----------------

    /** 裁剪页点「设为背景」：按屏幕上看到的范围裁图、存图、重新取强调色 */
    fun applyBackgroundCrop() {
        val c = bgCrop ?: return
        if (c.scale <= 0f) return
        val rect = c.visibleRect()
        val width = c.view.width
        bgCrop = null
        showToast("处理中…")
        store.scope.launch {
            val next = withContext(Dispatchers.IO) { store.background.setCropped(c.image, rect, width, store.prefs.bg.value) }
            if (next == null) showToast("背景没设上，再试一次") else {
                store.prefs.setBg(next)
                showToast("背景已更换，强调色已跟随")
            }
        }
    }

    // ---------------- 保险箱 ----------------

    fun openVault() {
        sheet = null
        clearSelection()
        searching = false
        quickAdd = false
        vaultOpen = true
    }

    /** 离开保险箱：立刻上锁，清掉内存里的明文和解密后的图片 */
    fun closeVault() {
        if (editor?.vault == true) closeEditor()
        clearSelection()
        vaultFlow = null
        pendingVaultMove = emptySet()
        vaultOpen = false
        lockVault()
    }

    fun lockVault() {
        voice.stop()
        store.vault.lock()
        store.vault.clearCache()
        store.images.clearSealedCache()
        // 保险箱里的录音录完就加密、删临时文件；这里再清一遍，万一哪次没删掉
        if (recording == null) store.scope.launch(store.io) { store.clips.clearTemp() }
    }

    /** 解锁成功后：把在外面选好的笔记移进来 */
    fun afterVaultUnlocked() {
        val ids = pendingVaultMove
        if (ids.isEmpty()) return
        pendingVaultMove = emptySet()
        store.scope.launch {
            val n = store.moveToVault(ids)
            if (n > 0) showToast("已移入保险箱 $n 条")
        }
    }

    /**
     * 马上要跳到系统界面（选图片、选文件）拿结果：这一次离开前台不上锁。
     * 回到前台时清掉（[onForeground]）：有的系统界面是半屏的，不会触发离开前台，标记不能留到下一次。
     */
    var expectingExternal = false

    fun onForeground() {
        expectingExternal = false
    }

    /** App 切到后台：录音收尾、停播放；保险箱立刻上锁（界面停在密码盘，回来要重新解锁） */
    fun onBackground() {
        finishRecording()
        voice.stop()
        video?.pause()
        saveEditor()
        if (expectingExternal) {
            expectingExternal = false
            return
        }
        if (!store.vault.unlocked.value) return
        if (editor?.vault == true) {
            editor = null
            viewer = null
        }
        clearSelection()
        lockVault()
    }

    // ---------------- 启动 / 旧数据 ----------------

    /** 进程启动后跑一次：读库 → 需要的话搬旧数据 → 清理孤儿图片 */
    suspend fun startup(context: Context) {
        store.load()
        if (!store.prefs.legacyDone) migrate(context)
        store.collectGarbage()
    }

    suspend fun migrate(context: Context) {
        var finished = false
        val outcome = LegacyMigration(context.applicationContext, store).migrate { done, total ->
            // 桥线程回调：切回主线程更新进度；搬完之后迟到的回调直接丢掉
            if (total > 0) store.scope.launch { if (!finished) migrating = done to total }
        }
        finished = true
        migrating = null
        when (outcome) {
            is LegacyMigration.Outcome.Empty -> Unit
            is LegacyMigration.Outcome.Done -> {
                val extra = buildList {
                    if (outcome.missingImages > 0) add("${outcome.missingImages} 张图在旧版里就已丢失")
                    if (outcome.result.failedImages > 0) add("${outcome.result.failedImages} 张图读不了")
                }
                showToast("已从旧版搬来 ${outcome.total} 条" + if (extra.isEmpty()) "" else "（" + extra.joinToString("，") + "）")
            }
            is LegacyMigration.Outcome.Failed -> dialog = DialogSpec(
                title = "旧数据还没搬完",
                message = outcome.message + "\n\n旧数据仍完好地留在手机里。可以重试，也可以在「设置 → 旧版数据」里先把它导出成备份文件。",
                confirm = "重试",
                cancel = "稍后",
                onConfirm = { store.scope.launch { migrate(context) } },
            )
        }
    }

    // ---------------- 返回键 ----------------

    /** 有东西可以用返回键关掉时为 true */
    val canGoBack: Boolean
        get() = dialog != null || bgCrop != null || viewer != null || sheet != null || vaultFlow != null || editor != null || selecting ||
            vaultOpen || quickAdd || searching || query.isNotEmpty()

    /** 返回键按层级逐层关闭；返回 false 表示已经没有可关的，交给系统退出 */
    fun back(): Boolean {
        when {
            dialog != null -> dialog = null
            bgCrop != null -> bgCrop = null
            viewer != null -> viewer = null
            sheet != null -> sheet = null
            vaultFlow != null -> vaultFlow = null
            editor != null -> closeEditor()
            selecting -> clearSelection()
            vaultOpen -> closeVault()
            quickAdd -> quickAdd = false
            // 第一下收起搜索框（关键词留着，列表仍是搜索结果），第二下清空关键词
            searching -> searching = false
            query.isNotEmpty() -> query = ""
            else -> return false
        }
        return true
    }
}
