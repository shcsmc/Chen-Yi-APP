package com.beiwang.memo.data

import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 备份文件。边读边写，附件再大（视频）也不会一次性读进内存。
 *
 * v5（本版）：zip 包，第一个条目是 backup.json，后面是附件的原始文件（media/…，不转码）：
 *   {v:5, at, categories:[{id,name,icon,layout,sort,builtin}], vaults:[保险箱便携头],
 *    notes:[{id,cat,type,title,body,pin,del,delAt,cr,up,enc,font,vault,vaultId,
 *            media:[{kind,w,h,dur,at,width,align,size,mime,sealed,file,thumb}]}]}
 *   file/thumb 是 zip 里的条目名。保险箱里的笔记原样导出密文（title/body 是 "v2:…"，附件是加密文件，sealed=true），
 *   便携头里只有「保险箱密码包住的内容密钥」—— 所以备份文件里的保险箱内容只靠保险箱密码保护。
 * v4（上一版，纯 JSON）：同上，但附件只有图片，放在 imgs:[{w,h,data[,thumb,sealed]}] 里（base64）。
 * v3（旧网页版，纯 JSON）：{v:3, at, lock, notes:[{id,type:"note"|"memo",title,body,pin,del,cr,up,imgs:[{w,h,data}]}]}
 * 三种都能导入（按文件开头认：zip 以 "PK" 开头，JSON 以 "{" 开头）；导出时同时写 type 字段。
 */
object Backup {

    data class Result(val added: Int, val updated: Int, val skipped: Int, val failedImages: Int)

    /** 导出文件的类型和扩展名 */
    const val MIME = "application/zip"
    const val EXT = "zip"

    private const val JSON_NAME = "backup.json"
    private const val NOT_BACKUP = "这不是辰Yi记（或旧版备忘）的备份文件"

    // ---------------- 导出 ----------------

    /**
     * 导出；返回（笔记条数，附件个数），用于传输时核对。
     * [ids] 非空时只导出这些笔记，分类、保险箱便携头、附件也只带它们用到的。不关闭 [out]。
     */
    fun export(out: OutputStream, store: Store, ids: Set<String>? = null): Pair<Int, Int> {
        val snap = store.data.value
        val notes = if (ids == null) snap.notes else snap.notes.filter { it.id in ids }
        val usedCats = notes.mapTo(HashSet()) { it.cat }
        val categories = if (ids == null) snap.categories else snap.categories.filter { it.id in usedCats }
        val images = store.images
        val clips = store.clips
        val vault = store.vault
        val files = ArrayList<Pair<String, File>>()
        var mediaCount = 0

        val zip = ZipOutputStream(BufferedOutputStream(NonClosingOutput(out), 64 * 1024))
        zip.setLevel(Deflater.DEFAULT_COMPRESSION)
        zip.putNextEntry(ZipEntry(JSON_NAME))
        val w = JsonWriter(OutputStreamWriter(NonClosingOutput(zip), Charsets.UTF_8).buffered())
        w.beginObject()
        w.name("v").value(5)
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
            if (n.font > 0) w.name("font").value(n.font)
            if (n.vault) {
                w.name("vault").value(true)
                w.name("vaultId").value(n.vaultKey.ifEmpty { localVaultId.orEmpty() })
            }
            // 附件放在 up 后面：导入时先知道要不要跳过这条，跳过的就不必收它的文件
            w.name("media").beginArray()
            for (m in n.media) {
                val sealed = n.vault
                val main = when {
                    m.kind == MediaKind.Image -> if (sealed) images.sealedFull(m.id) else images.full(m.id)
                    sealed -> clips.sealed(m.id)
                    else -> clips.plain(m.id)
                }
                if (!main.exists()) continue
                val thumb = (if (sealed) images.sealedThumb(m.id) else images.thumb(m.id)).takeIf { it.exists() }
                val base = "media/${m.id}"
                val mainName = base + "." + when {
                    m.kind == MediaKind.Image -> if (sealed) "vault" else "jpg"
                    sealed -> "vclip"
                    else -> Clips.extension(m)
                }
                val thumbName = base + "_t." + if (sealed) "vault" else "jpg"
                w.beginObject()
                w.name("kind").value(m.kind.code)
                w.name("w").value(m.w)
                w.name("h").value(m.h)
                if (m.dur > 0) w.name("dur").value(m.dur)
                w.name("at").value(m.at)
                if (m.width > 0) w.name("width").value(m.width)
                if (m.align != 0) w.name("align").value(m.align)
                if (m.size > 0) w.name("size").value(m.size)
                if (m.mime.isNotEmpty()) w.name("mime").value(m.mime)
                if (sealed) w.name("sealed").value(true)
                w.name("file").value(mainName)
                if (thumb != null) w.name("thumb").value(thumbName)
                w.endObject()
                files += mainName to main
                if (thumb != null) files += thumbName to thumb
                mediaCount++
            }
            w.endArray()
            w.endObject()
        }
        w.endArray()
        w.endObject()
        w.flush()
        zip.closeEntry()

        // 附件：图片、视频、语音本来就是压缩过的，不再压（省时间）
        zip.setLevel(Deflater.NO_COMPRESSION)
        val buf = ByteArray(64 * 1024)
        val written = HashSet<String>()
        for ((name, f) in files) {
            if (!written.add(name)) continue          // 同名条目 zip 不允许，重复的只写一次
            // 导出途中文件被删了（比如刚清空回收站）：跳过它，导入时这个附件会记为「没导入」
            val input = runCatching { f.inputStream() }.getOrNull() ?: continue
            input.use {
                zip.putNextEntry(ZipEntry(name))
                while (true) {
                    val r = it.read(buf)
                    if (r < 0) break
                    zip.write(buf, 0, r)
                }
                zip.closeEntry()
            }
        }
        zip.finish()
        zip.flush()
        return notes.size to mediaCount
    }

    // ---------------- 导入 ----------------

    /**
     * 按 id 合并：本地没有 → 新增；本地更新时间更晚或相同 → 跳过；否则用备份覆盖。
     * 在 IO 线程调用。认得 v5（zip）和 v3/v4（JSON）。
     */
    fun import(input: InputStream, store: Store): Result {
        val bin = input as? BufferedInputStream ?: BufferedInputStream(input, 64 * 1024)
        bin.mark(8)
        val head = ByteArray(4)
        var n = 0
        while (n < 4) {
            val r = bin.read(head, n, 4 - n)
            if (r < 0) break
            n += r
        }
        bin.reset()
        val isZip = n == 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() && head[2] == 3.toByte() && head[3] == 4.toByte()
        return if (isZip) importZip(bin, store) else importJson(bin, store)
    }

    private fun importJson(input: InputStream, store: Store): Result {
        val r = JsonReader(InputStreamReader(input, Charsets.UTF_8).buffered())
        val parsed = parse(r, store) { reader -> readInlineImage(reader, store.images) }
        return finish(store, parsed, parsed.notes)
    }

    /** 一个附件在 zip 里的文件要写到哪 */
    private class Incoming(val dest: File, val main: Boolean, val mediaId: String) {
        var received = false
    }

    private fun importZip(input: InputStream, store: Store): Result {
        val zip = ZipInputStream(input)
        val first = zip.nextEntry
        if (first == null || first.name != JSON_NAME) throw IllegalArgumentException(NOT_BACKUP)
        val pending = HashMap<String, Incoming>()
        val r = JsonReader(InputStreamReader(NonClosingInput(zip), Charsets.UTF_8).buffered())
        val parsed = parse(r, store) { reader -> readZipMedia(reader, store, pending) }

        // 其余条目是附件文件：按 json 里登记的去处写（条目名只当查表的键，不拿来拼路径）
        var e = zip.nextEntry
        while (e != null) {
            val inc = pending[e.name]
            if (inc != null && !inc.received) {
                runCatching { StreamCrypto.writeAtomically(inc.dest) { out -> zip.copyTo(out, 64 * 1024) } }
                    .onSuccess { inc.received = true }
            }
            e = zip.nextEntry
        }

        // 主文件没收到的附件：从笔记里去掉（缩略图可以没有：图片会用原图补，视频只是没有封面）
        val missing = pending.values.filter { it.main && !it.received }.mapTo(HashSet()) { it.mediaId }
        pending.values.filter { it.mediaId in missing }.forEach { it.dest.delete() }
        val notes = if (missing.isEmpty()) parsed.notes else parsed.notes.map { n ->
            if (n.media.none { it.id in missing }) n else n.copy(media = n.media.filterNot { it.id in missing })
        }
        return finish(store, parsed.copy(failed = parsed.failed + missing.size), notes)
    }

    private data class Parsed(
        val notes: List<Note>,
        val added: Int,
        val updated: Int,
        val skipped: Int,
        val failed: Int,
        val legacyLock: String?,
    )

    /** 读整份 JSON（分类、保险箱便携头、笔记）；附件怎么读由 [readMedia] 决定（返回 null = 这个附件读不了） */
    private fun parse(r: JsonReader, store: Store, readMedia: (JsonReader) -> Media?): Parsed {
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
                        val parsed = readNote(r, existing, catMap, readMedia)
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
        if (!sawNotes) throw IllegalArgumentException(NOT_BACKUP)
        return Parsed(out, added, updated, skipped, failed, legacyLock)
    }

    private fun finish(store: Store, parsed: Parsed, notes: List<Note>): Result {
        // 分类在本地不存在（比如「备忘」被删了）的，一律放进「笔记」，不能让内容挂在看不见的分类下
        val cats = store.data.value.categories.mapTo(HashSet()) { it.id }
        val placed = notes.map { if (it.cat in cats) it else it.copy(cat = Ids.NOTE) }
        // 覆盖旧笔记时，旧附件文件交给启动时的清理去删（撤销/失败都不会丢）
        store.putNotes(placed)
        val lock = parsed.legacyLock
        if (lock != null && placed.any { it.encrypted } && store.prefs.legacyLock.value == null) {
            store.prefs.setLegacyLock(lock)
        }
        return Result(parsed.added, parsed.updated, parsed.skipped, parsed.failed)
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

    /** 返回（笔记，失败的附件数）；本地版本更新时返回 null（跳过，附件也不读） */
    private fun readNote(
        r: JsonReader,
        existing: Map<String, Note>,
        catMap: Map<String, String>,
        readMedia: (JsonReader) -> Media?,
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
        val media = ArrayList<Media>()
        var failed = 0
        var skip = false
        var upKnown = false
        var vault = false
        var vaultId = ""
        var font = 0

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
                "font" -> font = long(r).toInt()
                "imgs", "media" -> {
                    // 本地版本更新的话不必读附件。旧版备份里 imgs 排在 up 前面，
                    // 那时还不知道要不要跳过，只能先导入；跳过后留下的文件由启动清理删掉
                    val old = id?.let { existing[it] }
                    skip = upKnown && old != null && old.updated >= up
                    if (skip || r.peek() != JsonToken.BEGIN_ARRAY) {
                        r.skipValue()
                    } else {
                        r.beginArray()
                        while (r.hasNext()) {
                            val m = readMedia(r)
                            if (m == null) failed++ else media += m
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
            id = nid, cat = localCat, title = title, body = body, media = media, pinned = pin,
            deletedAt = if (del) (if (delAt > 0) delAt else up.takeIf { it > 0 } ?: now) else 0L,
            created = if (cr > 0) cr else now, updated = if (up > 0) up else now, encrypted = encrypted,
            vault = vault, vaultKey = if (vault) vaultId else "", font = font,
        ) to failed
    }

    /** v3/v4：图片内嵌在 JSON 里（base64） */
    private fun readInlineImage(r: JsonReader, images: Images): Media? {
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

    /** v5：附件是 zip 里的文件。这里只登记「哪个条目写到哪」，换一个新 id；文件在 json 之后才读到 */
    private fun readZipMedia(r: JsonReader, store: Store, pending: MutableMap<String, Incoming>): Media? {
        var kind = MediaKind.Image
        var w = 0
        var h = 0
        var dur = 0L
        var at = -1
        var width = 0
        var align = 0
        var size = 0L
        var mime = ""
        var sealed = false
        var file: String? = null
        var thumb: String? = null
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "kind" -> kind = MediaKind.of(long(r).toInt())
                "w" -> w = long(r).toInt()
                "h" -> h = long(r).toInt()
                "dur" -> dur = long(r)
                "at" -> at = long(r).toInt()
                "width" -> width = long(r).toInt()
                "align" -> align = long(r).toInt()
                "size" -> size = long(r)
                "mime" -> mime = str(r)
                "sealed" -> sealed = bool(r)
                "file" -> file = str(r)
                "thumb" -> thumb = str(r)
                else -> r.skipValue()
            }
        }
        r.endObject()
        val f = file?.takeIf { it.isNotEmpty() } ?: return null
        val id = Ids.next()
        val images = store.images
        val clips = store.clips
        val dest = when {
            kind == MediaKind.Image -> if (sealed) images.sealedFull(id) else images.full(id)
            sealed -> clips.sealed(id)
            else -> clips.plain(id)
        }
        pending[f] = Incoming(dest, main = true, mediaId = id)
        thumb?.takeIf { it.isNotEmpty() && it != f }?.let { t ->
            pending[t] = Incoming(if (sealed) images.sealedThumb(id) else images.thumb(id), main = false, mediaId = id)
        }
        return Media(id, w, h, kind, dur = dur, at = at, width = width, align = align, size = size, mime = mime)
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

    /** 写 zip 条目时不让 JsonWriter 顺手把整个 zip 关掉 */
    private class NonClosingOutput(private val out: OutputStream) : OutputStream() {
        override fun write(b: Int) = out.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
        override fun flush() = out.flush()
        override fun close() = out.flush()
    }

    private class NonClosingInput(private val src: InputStream) : InputStream() {
        override fun read(): Int = src.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = src.read(b, off, len)
        override fun available(): Int = src.available()
        override fun close() {}
    }
}
