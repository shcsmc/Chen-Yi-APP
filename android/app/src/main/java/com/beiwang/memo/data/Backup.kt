package com.beiwang.memo.data

import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter

/**
 * 备份文件（JSON），边读边写，图片再多也不会一次性占满内存。
 *
 * v4（本版）：{v:4, at, categories:[{id,name,icon,layout,sort,builtin}],
 *              vaults:[保险箱便携头],
 *              notes:[{id,cat,type,title,body,pin,del,delAt,cr,up,enc,vault,vaultId,imgs:[{w,h,data[,thumb,sealed]}]}]}
 *   保险箱里的笔记原样导出密文（title/body 是 "v2:…"，图片是加密字节，sealed=true），
 *   便携头里只有「保险箱密码包住的内容密钥」—— 所以备份文件里的保险箱内容只靠保险箱密码保护。
 * v3（旧网页版）：{v:3, at, lock, notes:[{id,type:"note"|"memo",title,body,pin,del,cr,up,imgs:[{w,h,data}]}]}
 * 两种都能导入；导出时同时写 type 字段，旧版也认得出笔记/备忘。
 */
object Backup {

    data class Result(val added: Int, val updated: Int, val skipped: Int, val failedImages: Int)

    // ---------------- 导出 ----------------

    /**
     * 导出；返回（笔记条数，图片张数），用于传输时核对。
     * [ids] 非空时只导出这些笔记，分类、保险箱便携头、图片也只带它们用到的。
     */
    fun export(out: OutputStream, store: Store, ids: Set<String>? = null): Pair<Int, Int> {
        val snap = store.data.value
        val notes = if (ids == null) snap.notes else snap.notes.filter { it.id in ids }
        val usedCats = notes.mapTo(HashSet()) { it.cat }
        val categories = if (ids == null) snap.categories else snap.categories.filter { it.id in usedCats }
        val images = store.images
        val vault = store.vault
        var imageCount = 0
        val w = JsonWriter(OutputStreamWriter(out, Charsets.UTF_8).buffered())
        w.beginObject()
        w.name("v").value(4)
        w.name("at").value(System.currentTimeMillis())
        w.name("categories").beginArray()
        for (c in categories) {
            w.beginObject()
            w.name("id").value(c.id)
            w.name("name").value(c.name)
            w.name("icon").value(c.icon)
            w.name("layout").value(c.layout.code)
            w.name("sort").value(c.sort)
            w.name("builtin").value(c.builtin)
            w.endObject()
        }
        w.endArray()
        val localVaultId = vault.id
        val vaultIds = notes.filter { it.vault }.mapTo(LinkedHashSet()) { it.vaultKey.ifEmpty { localVaultId.orEmpty() } }
        w.name("vaults").beginArray()
        for (vid in vaultIds) {
            val h = if (vid == localVaultId) vault.portableHeader() else vault.foreign(vid)
            if (h != null) {
                w.beginObject()
                w.name("id").value(h.id)
                w.name("kind").value(h.kind)
                w.name("salt").value(VaultCrypto.b64(h.salt))
                w.name("iter").value(h.iterations)
                w.name("key").value(VaultCrypto.b64(h.wrapped))
                w.endObject()
            }
        }
        w.endArray()
        w.name("notes").beginArray()
        for (n in notes) {
            val layout = snap.category(n.cat)?.layout ?: Layout.Cards
            w.beginObject()
            w.name("id").value(n.id)
            w.name("cat").value(n.cat)
            w.name("type").value(if (layout == Layout.List) "memo" else "note")
            w.name("title").value(n.title)
            w.name("body").value(n.body)
            w.name("pin").value(n.pinned)
            w.name("del").value(n.inTrash)
            w.name("delAt").value(n.deletedAt)
            w.name("cr").value(n.created)
            w.name("up").value(n.updated)
            w.name("enc").value(n.encrypted)
            if (n.vault) {
                w.name("vault").value(true)
                w.name("vaultId").value(n.vaultKey.ifEmpty { localVaultId.orEmpty() })
            }
            w.name("imgs").beginArray()
            for (m in n.images) {
                if (n.vault) {
                    val full = images.sealedBytes(m.id, thumb = false) ?: continue
                    w.beginObject()
                    w.name("w").value(m.w)
                    w.name("h").value(m.h)
                    w.name("sealed").value(true)
                    w.name("data").value(Base64.encodeToString(full, Base64.NO_WRAP))
                    images.sealedBytes(m.id, thumb = true)?.let { w.name("thumb").value(Base64.encodeToString(it, Base64.NO_WRAP)) }
                    w.endObject()
                } else {
                    val f = images.full(m.id)
                    if (!f.exists()) continue
                    w.beginObject()
                    w.name("w").value(m.w)
                    w.name("h").value(m.h)
                    w.name("data").value("data:image/jpeg;base64," + Base64.encodeToString(f.readBytes(), Base64.NO_WRAP))
                    w.endObject()
                }
                imageCount++
            }
            w.endArray()
            w.endObject()
        }
        w.endArray()
        w.endObject()
        w.flush()
        return notes.size to imageCount
    }

    // ---------------- 导入 ----------------

    /**
     * 按 id 合并：本地没有 → 新增；本地更新时间更晚或相同 → 跳过；否则用备份覆盖。
     * 在 IO 线程调用；返回要写入的笔记，由调用方交给 Store。
     */
    fun import(input: InputStream, store: Store): Result {
        val r = JsonReader(InputStreamReader(input, Charsets.UTF_8).buffered())
        val existing = store.data.value.notes.associateBy { it.id }
        val catMap = HashMap<String, String>()   // 备份里的分类 id → 本地分类 id
        val out = ArrayList<Note>()
        var added = 0
        var updated = 0
        var skipped = 0
        var failed = 0
        var sawNotes = false
        var legacyLock: String? = null
        val headers = HashMap<String, VaultCrypto.Header>()
        val vaultKeys = HashMap<String, String>()   // 备份里的保险箱 id → 本机存的 vaultKey
        fun vaultKeyFor(vid: String): String = vaultKeys.getOrPut(vid) {
            val v = store.vault
            val h = headers[vid]
            when {
                vid.isNotEmpty() && vid == v.id -> ""                          // 本来就是本机的保险箱
                !v.configured.value && h != null -> { v.adopt(h); "" }          // 本机还没保险箱：直接沿用对方的
                else -> { h?.let { v.addForeign(it) }; vid }                    // 另一个保险箱：先存着，等输原密码转换
            }
        }

        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "categories" -> {
                    r.beginArray()
                    while (r.hasNext()) {
                        val c = readCategory(r) ?: continue
                        // 本地已有同 id 的分类就用它；没有（包括本地删掉了「备忘」）就按备份补建，满了则放进「笔记」
                        val local = store.data.value.category(c.id) ?: store.ensureCategory(c)
                        catMap[c.id] = local?.id ?: (if (c.layout == Layout.List) Ids.MEMO else Ids.NOTE)
                    }
                    r.endArray()
                }
                "vaults" -> {
                    r.beginArray()
                    while (r.hasNext()) {
                        VaultCrypto.Header.parse(readFlatObject(r))?.let { headers[it.id] = it }
                    }
                    r.endArray()
                }
                "notes" -> {
                    sawNotes = true
                    r.beginArray()
                    while (r.hasNext()) {
                        val parsed = readNote(r, existing, store.images, catMap)
                        when {
                            parsed == null -> skipped++
                            else -> {
                                failed += parsed.second
                                val raw = parsed.first
                                // readNote 把备份里的保险箱 id 暂放在 vaultKey 里，这里换成本机的
                                val n = if (raw.vault) raw.copy(vaultKey = vaultKeyFor(raw.vaultKey)) else raw
                                val old = existing[n.id]
                                if (old == null) added++ else updated++
                                out += n
                            }
                        }
                    }
                    r.endArray()
                }
                "lock" -> {
                    if (r.peek() == JsonToken.NULL) r.nextNull() else legacyLock = readLock(r)
                }
                else -> r.skipValue()
            }
        }
        r.endObject()
        if (!sawNotes) throw IllegalArgumentException("这不是辰Yi记（或旧版备忘）的备份文件")

        // 分类在本地不存在（比如「备忘」被删了）的，一律放进「笔记」，不能让内容挂在看不见的分类下
        val cats = store.data.value.categories.mapTo(HashSet()) { it.id }
        val placed = out.map { if (it.cat in cats) it else it.copy(cat = Ids.NOTE) }
        // 覆盖旧笔记时，旧图片文件交给启动时的清理去删（撤销/失败都不会丢图）
        store.putNotes(placed)
        if (legacyLock != null && placed.any { it.encrypted } && store.prefs.legacyLock.value == null) {
            store.prefs.setLegacyLock(legacyLock)
        }
        return Result(added, updated, skipped, failed)
    }

    private fun readCategory(r: JsonReader): Category? {
        var id: String? = null
        var name = ""
        var icon = "doc"
        var layout = Layout.Cards
        var sort = 0
        var builtin = false
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "id" -> id = str(r)
                "name" -> name = str(r)
                "icon" -> icon = str(r).ifBlank { "doc" }
                "layout" -> layout = Layout.of(long(r).toInt())
                "sort" -> sort = long(r).toInt()
                "builtin" -> builtin = bool(r)
                else -> r.skipValue()
            }
        }
        r.endObject()
        return id?.let { Category(it, name.ifBlank { "分类" }, icon, layout, sort, builtin) }
    }

    /** 返回（笔记，失败的图片数）；本地版本更新时返回 null（跳过，图片也不解码） */
    private fun readNote(
        r: JsonReader,
        existing: Map<String, Note>,
        images: Images,
        catMap: Map<String, String>,
    ): Pair<Note, Int>? {
        var id: String? = null
        var cat: String? = null
        var type = "note"
        var title = ""
        var body = ""
        var pin = false
        var del = false
        var delAt = 0L
        var cr = 0L
        var up = 0L
        var enc = false
        val imgs = ArrayList<NoteImage>()
        var failed = 0
        var skip = false
        var upKnown = false
        var vault = false
        var vaultId = ""

        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "id" -> id = str(r)
                "cat" -> cat = str(r)
                "type" -> type = str(r)
                "title" -> title = str(r)
                "body" -> body = str(r)
                "pin" -> pin = bool(r)
                "del" -> del = bool(r)
                "delAt" -> delAt = long(r)
                "cr" -> cr = long(r)
                "up" -> { up = long(r); upKnown = true }
                "enc" -> enc = bool(r)
                "vault" -> vault = bool(r)
                "vaultId" -> vaultId = str(r)
                "imgs" -> {
                    // 本地版本更新的话不必解码图片。旧版备份里 imgs 排在 up 前面，
                    // 那时还不知道要不要跳过，只能先导入；跳过后留下的图片由启动清理删掉
                    val old = id?.let { existing[it] }
                    skip = upKnown && old != null && old.updated >= up
                    if (skip || r.peek() != JsonToken.BEGIN_ARRAY) {
                        r.skipValue()
                    } else {
                        r.beginArray()
                        while (r.hasNext()) {
                            val m = readImage(r, images)
                            if (m == null) failed++ else imgs += m
                        }
                        r.endArray()
                    }
                }
                else -> r.skipValue()
            }
        }
        r.endObject()

        val nid = id?.takeIf { it.isNotBlank() } ?: return null
        val old = existing[nid]
        if (skip || (old != null && old.updated >= up)) return null
        val now = System.currentTimeMillis()
        val localCat = cat?.let { catMap[it] ?: it.takeIf { c -> c == Ids.NOTE || c == Ids.MEMO } }
            ?: if (type == "memo") Ids.MEMO else Ids.NOTE
        val encrypted = enc || (type == "memo" && body.startsWith("v1:"))
        return Note(
            id = nid, cat = localCat, title = title, body = body, images = imgs, pinned = pin,
            deletedAt = if (del) (if (delAt > 0) delAt else up.takeIf { it > 0 } ?: now) else 0L,
            created = if (cr > 0) cr else now, updated = if (up > 0) up else now, encrypted = encrypted,
            vault = vault, vaultKey = if (vault) vaultId else "",
        ) to failed
    }

    private fun readImage(r: JsonReader, images: Images): NoteImage? {
        var data: String? = null
        var thumb: String? = null
        var sealed = false
        var w = 0
        var h = 0
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "data" -> data = str(r)
                "thumb" -> thumb = str(r)
                "sealed" -> sealed = bool(r)
                "w" -> w = long(r).toInt()
                "h" -> h = long(r).toInt()
                else -> r.skipValue()
            }
        }
        r.endObject()
        val d = data?.takeIf { it.isNotEmpty() } ?: return null
        fun decode(s: String) = runCatching { Base64.decode(s.substringAfter(','), Base64.DEFAULT) }.getOrNull()
        val bytes = decode(d) ?: return null
        // 保险箱图片是密文，原样存，不解码
        return if (sealed) images.importSealed(bytes, thumb?.let { decode(it) }, w, h) else images.importBytes(bytes)
    }

    /** 读一个只有字符串/数字字段的小对象，转回 JSON 文本（保险箱便携头用） */
    private fun readFlatObject(r: JsonReader): String {
        val sb = StringBuilder("{")
        r.beginObject()
        var first = true
        while (r.hasNext()) {
            val k = r.nextName()
            val v = when (r.peek()) {
                JsonToken.STRING -> org.json.JSONObject.quote(r.nextString())
                JsonToken.NUMBER -> r.nextString()
                else -> { r.skipValue(); continue }
            }
            if (!first) sb.append(',')
            sb.append(org.json.JSONObject.quote(k)).append(':').append(v)
            first = false
        }
        r.endObject()
        return sb.append('}').toString()
    }

    private fun readLock(r: JsonReader): String {
        val sb = StringBuilder("{")
        r.beginObject()
        var first = true
        while (r.hasNext()) {
            val k = r.nextName()
            if (r.peek() != JsonToken.STRING) { r.skipValue(); continue }
            val v = r.nextString()
            if (!first) sb.append(',')
            sb.append(org.json.JSONObject.quote(k)).append(':').append(org.json.JSONObject.quote(v))
            first = false
        }
        r.endObject()
        return sb.append('}').toString()
    }

    // 宽松读取：字段缺失、为 null 或类型不对都给默认值，不让一条坏数据毁掉整次导入
    private fun str(r: JsonReader): String = when (r.peek()) {
        JsonToken.STRING, JsonToken.NUMBER -> r.nextString()
        else -> { r.skipValue(); "" }
    }

    private fun bool(r: JsonReader): Boolean = when (r.peek()) {
        JsonToken.BOOLEAN -> r.nextBoolean()
        JsonToken.NUMBER -> r.nextDouble() != 0.0
        else -> { r.skipValue(); false }
    }

    private fun long(r: JsonReader): Long = when (r.peek()) {
        JsonToken.NUMBER, JsonToken.STRING -> r.nextString().toDoubleOrNull()?.toLong() ?: 0L
        else -> { r.skipValue(); 0L }
    }
}
