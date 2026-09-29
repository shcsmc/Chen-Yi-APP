package com.beiwang.memo.ui

import android.content.Context
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import com.beiwang.memo.data.Ids
import com.beiwang.memo.data.Layout
import com.beiwang.memo.data.Note
import com.beiwang.memo.data.NoteImage
import com.beiwang.memo.data.Store
import com.beiwang.memo.legacy.LegacyMigration
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

/** 正在编辑的笔记：文字放在输入框状态里，停手 0.4 秒自动保存 */
@Stable
class EditorSession(val base: Note, val layout: Layout, val isNew: Boolean, val vault: Boolean = false) {
    val id: String get() = base.id
    val title = TextFieldState(base.title)
    val body = TextFieldState(base.body)
    var images by mutableStateOf(base.images)
    var pinned by mutableStateOf(base.pinned)
    /** 最近一次写盘的时间，用来闪一下「已保存」 */
    var savedAt by mutableLongStateOf(0L)
    /** 是否已经写进过数据库（新建但一个字没写的不入库） */
    var persisted = !isNew

    fun snapshot(now: Long): Note = base.copy(
        title = title.text.toString(),
        body = body.text.toString(),
        images = images,
        pinned = pinned,
        updated = now,
    )

    fun sameAs(n: Note): Boolean =
        n.title == title.text.toString() && n.body == body.text.toString() && n.images == images && n.pinned == pinned
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
    private var _viewer by mutableStateOf<NoteImage?>(null)
    /** 正在看的大图；换图时旋转归零 */
    var viewer: NoteImage?
        get() = _viewer
        set(v) {
            _viewer = v
            viewerTurns = 0
        }
    var viewerTurns by mutableIntStateOf(0)
    var sheet by mutableStateOf<Sheet?>(null)
    var dialog by mutableStateOf<DialogSpec?>(null)
    var toast by mutableStateOf<ToastSpec?>(null)
        private set
    /** 条目式分类里顶部的「记一条」输入行 */
    var quickAdd by mutableStateOf(false)

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

    /** 关闭编辑页：先落盘；一个字都没有的笔记直接丢掉（不进回收站，旧版会留下「空白笔记」） */
    fun closeEditor() {
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
        store.vault.lock()
        store.vault.clearCache()
        store.images.clearSealedCache()
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

    /** App 切到后台：保险箱立刻上锁（界面停在密码盘，回来要重新解锁） */
    fun onBackground() {
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
        get() = dialog != null || viewer != null || sheet != null || vaultFlow != null || editor != null || selecting ||
            vaultOpen || quickAdd || searching || query.isNotEmpty()

    /** 返回键按层级逐层关闭；返回 false 表示已经没有可关的，交给系统退出 */
    fun back(): Boolean {
        when {
            dialog != null -> dialog = null
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
