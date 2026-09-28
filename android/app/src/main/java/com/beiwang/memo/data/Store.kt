package com.beiwang.memo.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 唯一的数据入口。
 *
 * - 内存里的 [data] 是界面读取的唯一来源；每次修改先换掉内存快照（界面立刻刷新），
 *   再把同一份改动排进单线程 IO 队列写库，写库顺序和修改顺序一致。
 * - 界面层只调用这里的方法，不直接碰 Db / 文件。
 */
class Store(context: Context) {

    private val app = context.applicationContext
    val prefs = Prefs(app)
    val images = Images(app)
    val background = Background(app)
    private val db = Db(app)

    @OptIn(ExperimentalCoroutinesApi::class)
    val io = Dispatchers.IO.limitedParallelism(1)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _data = MutableStateFlow(Snapshot())
    val data: StateFlow<Snapshot> = _data.asStateFlow()

    private val started = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 启动流程只跑一次（Activity 重建时不重复） */
    fun startOnce(): Boolean = started.compareAndSet(false, true)

    suspend fun load() {
        _data.value = withContext(io) { db.loadAll() }
    }

    /** 启动时清理孤儿图片；必须在没有导入/迁移进行时调用 */
    fun collectGarbage() {
        val keep = _data.value.notes.flatMapTo(HashSet()) { n -> n.images.map { it.id } }
        scope.launch(io) { images.collectGarbage(keep) }
    }

    private fun write(block: Db.() -> Unit) {
        scope.launch(io) { db.block() }
    }

    // ---------------- 笔记 ----------------

    fun saveNote(note: Note) {
        _data.update { s ->
            val i = s.notes.indexOfFirst { it.id == note.id }
            s.copy(notes = if (i < 0) s.notes + note else s.notes.toMutableList().also { it[i] = note })
        }
        write { upsertNotes(listOf(note)) }
    }

    private fun changeNotes(ids: Collection<String>, change: (Note) -> Note) {
        val set = ids.toHashSet()
        val changed = ArrayList<Note>()
        _data.update { s ->
            changed.clear()
            s.copy(notes = s.notes.map { n -> if (n.id in set) change(n).also { changed += it } else n })
        }
        if (changed.isNotEmpty()) {
            val list = changed.toList()
            write { upsertNotes(list) }
        }
    }

    fun setPinned(id: String, pinned: Boolean) = changeNotes(listOf(id)) { it.copy(pinned = pinned) }

    fun trash(ids: Collection<String>) {
        val now = System.currentTimeMillis()
        changeNotes(ids) { it.copy(deletedAt = now) }
    }

    /** 恢复；原分类已经删掉的，回到「笔记」 */
    fun restore(ids: Collection<String>) {
        val cats = _data.value.categories.mapTo(HashSet()) { it.id }
        changeNotes(ids) { n -> n.copy(deletedAt = 0L, cat = if (n.cat in cats) n.cat else Ids.NOTE) }
    }

    fun move(ids: Collection<String>, cat: String) {
        val now = System.currentTimeMillis()
        changeNotes(ids) { it.copy(cat = cat, updated = now) }
    }

    /** 彻底删除（清空回收站、丢弃空白笔记），连同图片文件 */
    fun purge(ids: Collection<String>) {
        val set = ids.toHashSet()
        val gone = _data.value.notes.filter { it.id in set }
        if (gone.isEmpty()) return
        _data.update { s -> s.copy(notes = s.notes.filterNot { it.id in set }) }
        scope.launch(io) {
            db.deleteNotes(set)
            gone.forEach { n -> n.images.forEach { images.delete(it.id) } }
        }
    }

    fun purgeTrash() = purge(_data.value.notes.filter { it.inTrash }.map { it.id })

    /** 导入/迁移：整批写入（调用方已经决定好覆盖谁） */
    fun putNotes(list: List<Note>) {
        if (list.isEmpty()) return
        val byId = list.associateBy { it.id }
        _data.update { s ->
            val kept = s.notes.filterNot { it.id in byId }
            s.copy(notes = kept + list)
        }
        write { upsertNotes(list) }
    }

    // ---------------- 分类 ----------------

    val canAddCategory: Boolean get() = _data.value.categories.size < Ids.MAX_CATEGORIES

    fun addCategory(name: String, icon: String, layout: Layout): Category? {
        val s = _data.value
        if (s.categories.size >= Ids.MAX_CATEGORIES) return null
        val c = Category(
            id = "c" + Ids.next(), name = name.trim().take(Ids.MAX_NAME), icon = icon, layout = layout,
            sort = (s.categories.maxOfOrNull { it.sort } ?: 0) + 1, builtin = false,
        )
        _data.update { it.copy(categories = it.categories + c) }
        write { upsertCategories(listOf(c)) }
        return c
    }

    fun updateCategory(id: String, name: String, icon: String, layout: Layout) {
        var changed: Category? = null
        _data.update { s ->
            s.copy(categories = s.categories.map { c ->
                if (c.id == id) c.copy(name = name.trim().take(Ids.MAX_NAME), icon = icon, layout = layout)
                    .also { changed = it } else c
            })
        }
        changed?.let { c -> write { upsertCategories(listOf(c)) } }
    }

    /** 删除分类：里面的内容全部移进回收站（能恢复，恢复后回到「笔记」） */
    fun deleteCategory(id: String) {
        val c = _data.value.category(id) ?: return
        if (!c.deletable) return
        trash(_data.value.notes.filter { it.cat == id && !it.inTrash }.map { it.id })
        _data.update { s -> s.copy(categories = s.categories.filterNot { it.id == id }) }
        write { deleteCategory(id) }
        if (prefs.currentCat.value == id) prefs.setCurrentCat(Ids.NOTE)
    }

    /** 导入备份时补建分类（超过上限的返回 null，调用方改放进内置分类） */
    fun ensureCategory(c: Category): Category? {
        _data.value.category(c.id)?.let { return it }
        if (!canAddCategory) return null
        val fixed = c.copy(builtin = false, sort = (_data.value.categories.maxOfOrNull { it.sort } ?: 0) + 1)
        _data.update { it.copy(categories = it.categories + fixed) }
        write { upsertCategories(listOf(fixed)) }
        return fixed
    }
}
