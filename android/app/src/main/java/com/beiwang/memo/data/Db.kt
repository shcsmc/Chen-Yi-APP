package com.beiwang.memo.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 本地数据库（SQLite）。只做存取，不含业务逻辑；所有调用都在 Store 的单线程 IO 上。
 *
 * 表结构改动规则：只加不删。版本号 +1，在 onUpgrade 里按旧版本逐步 ALTER，
 * 手机上有真实数据，任何时候都不能清库重建。
 */
class Db(context: Context) : SQLiteOpenHelper(context, "memo.db", null, VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE categories(id TEXT PRIMARY KEY, name TEXT NOT NULL, icon TEXT NOT NULL," +
                " layout INTEGER NOT NULL, sort INTEGER NOT NULL, builtin INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE notes(id TEXT PRIMARY KEY, cat TEXT NOT NULL, title TEXT NOT NULL," +
                " body TEXT NOT NULL, pinned INTEGER NOT NULL, deleted_at INTEGER NOT NULL," +
                " created INTEGER NOT NULL, updated INTEGER NOT NULL, encrypted INTEGER NOT NULL DEFAULT 0)"
        )
        // 先建第 1 版，再走一遍升级：保证新装和老用户升级后的结构完全一样
        db.execSQL(
            "CREATE TABLE images(id TEXT PRIMARY KEY, note TEXT NOT NULL, pos INTEGER NOT NULL," +
                " w INTEGER NOT NULL, h INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX images_note ON images(note)")
        for (c in BUILTIN) insertCategory(db, c)
        onUpgrade(db, 1, VERSION)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // 第 2 版：保险箱
            db.execSQL("ALTER TABLE notes ADD COLUMN vault INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE notes ADD COLUMN vault_key TEXT NOT NULL DEFAULT ''")
        }
        if (oldVersion < 3) {
            // 第 3 版：附件除了图片还有视频、语音；可以插在正文中间、改大小和对齐。
            // 表名仍叫 images（只加不删），旧图片都是 kind=0、at=-1（文末）
            db.execSQL("ALTER TABLE images ADD COLUMN kind INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE images ADD COLUMN dur INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE images ADD COLUMN at INTEGER NOT NULL DEFAULT -1")
            db.execSQL("ALTER TABLE images ADD COLUMN width INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE images ADD COLUMN align INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE images ADD COLUMN size INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE images ADD COLUMN mime TEXT NOT NULL DEFAULT ''")
        }
        // 以后：if (oldVersion < 4) { ... } 依次往下
    }

    fun loadAll(): Snapshot {
        val db = readableDatabase
        val cats = ArrayList<Category>()
        db.rawQuery("SELECT id,name,icon,layout,sort,builtin FROM categories ORDER BY sort", null).use { c ->
            while (c.moveToNext()) {
                cats += Category(
                    id = c.getString(0), name = c.getString(1), icon = c.getString(2),
                    layout = Layout.of(c.getInt(3)), sort = c.getInt(4), builtin = c.getInt(5) != 0,
                )
            }
        }
        val media = HashMap<String, MutableList<Media>>()
        db.rawQuery("SELECT id,note,w,h,kind,dur,at,width,align,size,mime FROM images ORDER BY note,pos", null).use { c ->
            while (c.moveToNext()) {
                media.getOrPut(c.getString(1)) { ArrayList() } += Media(
                    id = c.getString(0), w = c.getInt(2), h = c.getInt(3), kind = MediaKind.of(c.getInt(4)),
                    dur = c.getLong(5), at = c.getInt(6), width = c.getInt(7), align = c.getInt(8),
                    size = c.getLong(9), mime = c.getString(10),
                )
            }
        }
        val notes = ArrayList<Note>()
        db.rawQuery(
            "SELECT id,cat,title,body,pinned,deleted_at,created,updated,encrypted,vault,vault_key FROM notes", null
        ).use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                notes += Note(
                    id = id, cat = c.getString(1), title = c.getString(2), body = c.getString(3),
                    media = media[id].orEmpty(), pinned = c.getInt(4) != 0, deletedAt = c.getLong(5),
                    created = c.getLong(6), updated = c.getLong(7), encrypted = c.getInt(8) != 0,
                    vault = c.getInt(9) != 0, vaultKey = c.getString(10),
                )
            }
        }
        return Snapshot(cats, notes, loaded = true)
    }

    fun upsertNotes(list: Collection<Note>) = tx { db -> for (n in list) writeNote(db, n) }

    fun deleteNotes(ids: Collection<String>) = tx { db ->
        for (id in ids) {
            db.delete("notes", "id=?", arrayOf(id))
            db.delete("images", "note=?", arrayOf(id))
        }
    }

    fun upsertCategories(list: Collection<Category>) = tx { db -> for (c in list) insertCategory(db, c) }

    fun deleteCategory(id: String) = tx { db -> db.delete("categories", "id=?", arrayOf(id)) }

    private fun writeNote(db: SQLiteDatabase, n: Note) {
        db.insertWithOnConflict("notes", null, ContentValues().apply {
            put("id", n.id)
            put("cat", n.cat)
            put("title", n.title)
            put("body", n.body)
            put("pinned", if (n.pinned) 1 else 0)
            put("deleted_at", n.deletedAt)
            put("created", n.created)
            put("updated", n.updated)
            put("encrypted", if (n.encrypted) 1 else 0)
            put("vault", if (n.vault) 1 else 0)
            put("vault_key", n.vaultKey)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        db.delete("images", "note=?", arrayOf(n.id))
        n.media.forEachIndexed { i, m ->
            db.insertWithOnConflict("images", null, ContentValues().apply {
                put("id", m.id)
                put("note", n.id)
                put("pos", i)
                put("w", m.w)
                put("h", m.h)
                put("kind", m.kind.code)
                put("dur", m.dur)
                put("at", m.at)
                put("width", m.width)
                put("align", m.align)
                put("size", m.size)
                put("mime", m.mime)
            }, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    private inline fun tx(block: (SQLiteDatabase) -> Unit) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            block(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    companion object {
        const val VERSION = 3

        val BUILTIN = listOf(
            Category(Ids.NOTE, "笔记", "doc", Layout.Cards, 0, builtin = true),
            Category(Ids.MEMO, "备忘", "list", Layout.List, 1, builtin = true),
        )

        private fun insertCategory(db: SQLiteDatabase, c: Category) {
            db.insertWithOnConflict("categories", null, ContentValues().apply {
                put("id", c.id)
                put("name", c.name)
                put("icon", c.icon)
                put("layout", c.layout.code)
                put("sort", c.sort)
                put("builtin", if (c.builtin) 1 else 0)
            }, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }
}
