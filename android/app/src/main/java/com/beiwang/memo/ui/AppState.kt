package com.beiwang.memo.ui

import android.content.Context
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
class EditorSession(val base: Note, val layout: Layout, val isNew: Boolean) {
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

    fun open(note: Note) {
        val s = store.data.value
        if (note.encrypted) {
            sheet = Sheet.Unlock
            return
        }
        val layout = s.category(note.cat)?.layout ?: Layout.Cards
        quickAdd = false
        editor = EditorSession(note, layout, isNew = false)
    }

    /** 在当前分类新建：卡片式打开编辑页；条目式在列表顶部出一行输入框 */
    fun create() {
        val s = store.data.value
        val cat = s.category(store.prefs.currentCat.value) ?: s.category(Ids.NOTE) ?: return
        clearSelection()
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
        val current = store.data.value.note(e.id)
        if (current != null && e.sameAs(current)) return
        val n = e.snapshot(System.currentTimeMillis())
        if (!e.persisted && n.isBlank) return
        store.saveNote(n)
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
        get() = dialog != null || viewer != null || sheet != null || editor != null || selecting ||
            quickAdd || searching || query.isNotEmpty()

    /** 返回键按层级逐层关闭；返回 false 表示已经没有可关的，交给系统退出 */
    fun back(): Boolean {
        when {
            dialog != null -> dialog = null
            viewer != null -> viewer = null
            sheet != null -> sheet = null
            editor != null -> closeEditor()
            selecting -> clearSelection()
            quickAdd -> quickAdd = false
            searching || query.isNotEmpty() -> { searching = false; query = "" }
            else -> return false
        }
        return true
    }
}
