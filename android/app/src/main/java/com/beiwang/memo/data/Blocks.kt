package com.beiwang.memo.data

import java.util.concurrent.atomic.AtomicLong

/**
 * 图文混排：正文 + 附件位置 ↔ 编辑页里的「文字段 / 附件组」序列。纯算法，不碰界面，有单元测试。
 *
 * 存储：正文 [Note.body] 永远是纯文字（搜索、预览、复制都直接用它）；每个附件记着自己在正文里的位置 [Media.at]。
 * 编辑：文字段和附件组严格交替 —— T0, G1, T1, G2, …, Gk, Tk，首尾都是文字段（可以为空）。
 *
 * 合（[join]）：正文 = T0 + "\n" + T1 + "\n" + … + Tk，第 i 组附件的位置 = 第 i 个换行符之后。
 * 这个换行符是附件占掉的「分段」，拆（[split]）的时候去掉。于是：
 * - 附件夹在两段文字之间 → 正文里就是一个换行，删掉附件文字一个字都不变；
 * - 两组附件之间隔一个空文字段 → 位置不同，上下两行；同一组 → 位置相同，并排（排满换行）。
 * 旧数据的附件位置是 -1（文末），拆的时候当作在最后；超出正文长度的也当作在最后。
 */
object Blocks {

    sealed interface Piece {
        /** 编辑时的稳定标识（界面用它对应输入框、定位拖放目标）；不入库 */
        val key: Long
    }

    data class Text(override val key: Long, val text: String) : Piece
    data class Group(override val key: Long, val items: List<Media>) : Piece

    private val keys = AtomicLong(1)
    fun newKey(): Long = keys.getAndIncrement()

    // ---------------- 拆 / 合 ----------------

    fun split(body: String, media: List<Media>): List<Piece> {
        if (media.isEmpty()) return listOf(Text(newKey(), body))
        val len = body.length
        // 稳定排序：同一位置的按原来的先后
        val sorted = media.withIndex()
            .sortedWith(compareBy<IndexedValue<Media>>({ anchor(it.value, len) }, { it.index }))
            .map { it.value }
        val out = ArrayList<Piece>()
        var start = 0
        var i = 0
        while (i < sorted.size) {
            val a = anchor(sorted[i], len)
            var j = i
            while (j < sorted.size && anchor(sorted[j], len) == a) j++
            var seg = body.substring(start, a)
            if (seg.endsWith('\n')) seg = seg.dropLast(1)     // 附件占掉的那个换行
            out += Text(newKey(), seg)
            out += Group(newKey(), sorted.subList(i, j).toList())
            start = a
            i = j
        }
        out += Text(newKey(), body.substring(start))
        return out
    }

    private fun anchor(m: Media, len: Int) = if (m.at < 0 || m.at > len) len else m.at

    /** 编辑后的序列 → （正文，带新位置的附件）；附件顺序即显示顺序 */
    fun join(pieces: List<Piece>): Pair<String, List<Media>> {
        val norm = normalize(pieces)
        val sb = StringBuilder((norm[0] as Text).text)
        val media = ArrayList<Media>()
        var k = 1
        while (k < norm.size) {
            val g = norm[k] as Group
            val t = norm[k + 1] as Text
            sb.append('\n')
            val at = sb.length
            g.items.forEach { media += it.copy(at = at) }
            sb.append(t.text)
            k += 2
        }
        return sb.toString() to media
    }

    /** 同一份内容的标准形：用来判断「编辑页里的内容和库里的是不是一样」，旧数据的 -1 位置不会被当成改动 */
    fun canonical(body: String, media: List<Media>): Pair<String, List<Media>> = join(split(body, media))

    /**
     * 整理成 T, G, T, …, T：去掉空组，相邻的组合并（中间没有文字就是同一组），相邻的文字段用换行接起来，
     * 首尾补空文字段。
     */
    fun normalize(pieces: List<Piece>): List<Piece> {
        val out = ArrayList<Piece>()
        for (p in pieces) {
            when (p) {
                is Group -> {
                    if (p.items.isEmpty()) continue
                    when (val last = out.lastOrNull()) {
                        is Group -> out[out.lastIndex] = last.copy(items = last.items + p.items)
                        null -> { out += Text(newKey(), ""); out += p }
                        else -> out += p
                    }
                }
                is Text -> {
                    val last = out.lastOrNull()
                    if (last is Text) out[out.lastIndex] = last.copy(text = glue(last.text, p.text)) else out += p
                }
            }
        }
        if (out.isEmpty() || out.last() is Group) out += Text(newKey(), "")
        return out
    }

    /** 两段文字接起来：都不空时中间换行（附件被拿走后，它两边的文字还是上下两段） */
    private fun glue(a: String, b: String) = when {
        a.isEmpty() -> b
        b.isEmpty() -> a
        else -> a + "\n" + b
    }

    // ---------------- 编辑操作（都返回新序列，不改原来的） ----------------

    /** 放到哪里：文字段里的某个位置（在那里断开，附件自成一组），或者某一组里的第几个 */
    sealed interface Target
    data class IntoText(val key: Long, val offset: Int) : Target
    data class IntoGroup(val key: Long, val index: Int) : Target

    /** 文末（没有光标时新加的附件放这里） */
    fun end(pieces: List<Piece>): Target {
        val last = pieces.lastOrNull { it is Text } as? Text ?: return IntoText(-1, 0)
        return IntoText(last.key, last.text.length)
    }

    /**
     * 插入附件。插进文字段时在 [IntoText.offset] 处断开：光标在行首/行尾时，附件就占那个换行
     * （「第一段|↵第二段」→ 第一段、附件、第二段，正文还是原来那两行）；在一行中间就把这行断成两行。
     */
    fun insert(pieces: List<Piece>, target: Target, items: List<Media>): List<Piece> {
        if (items.isEmpty()) return pieces
        val out = normalize(pieces).toMutableList()
        when (target) {
            is IntoText -> {
                var i = out.indexOfFirst { it is Text && it.key == target.key }
                if (i < 0) i = out.lastIndex                          // 找不到（已被合并掉）：放文末
                val t = out[i] as Text
                val o = (if (i == out.lastIndex && target.key != t.key) t.text.length else target.offset).coerceIn(0, t.text.length)
                var a = t.text.substring(0, o)
                var b = t.text.substring(o)
                if (a.endsWith('\n')) a = a.dropLast(1)
                if (b.startsWith('\n')) b = b.drop(1)
                out[i] = t.copy(text = a)
                out.add(i + 1, Group(newKey(), items))
                out.add(i + 2, Text(newKey(), b))
            }
            is IntoGroup -> {
                val i = out.indexOfFirst { it is Group && it.key == target.key }
                if (i < 0) return insert(out, end(out), items)
                val g = out[i] as Group
                val align = g.items.firstOrNull()?.align ?: 0
                val list = g.items.toMutableList()
                list.addAll(target.index.coerceIn(0, list.size), items.map { it.copy(align = align) })
                out[i] = g.copy(items = list)
            }
        }
        return normalize(out)
    }

    /** 删掉一个附件；它所在的组空了，组两边的文字接回一段 */
    fun remove(pieces: List<Piece>, id: String): List<Piece> {
        val g = pieces.firstOrNull { it is Group && it.items.any { m -> m.id == id } } ?: return pieces
        return removeFrom(pieces, g.key, id)
    }

    private fun removeFrom(pieces: List<Piece>, groupKey: Long, id: String): List<Piece> {
        val out = pieces.toMutableList()
        val gi = out.indexOfFirst { it is Group && it.key == groupKey }
        if (gi < 0) return pieces
        val g = out[gi] as Group
        val rest = g.items.filterNot { it.id == id }
        if (rest.isNotEmpty()) {
            out[gi] = g.copy(items = rest)
            return out
        }
        val before = out.getOrNull(gi - 1) as? Text
        val after = out.getOrNull(gi + 1) as? Text
        if (before != null && after != null) {
            out[gi - 1] = before.copy(text = glue(before.text, after.text))
            out.removeAt(gi + 1)
        }
        out.removeAt(gi)
        return normalize(out)
    }

    /** 挪位置：先放到目标处，再把原来那个拿掉（同一组里只是换顺序） */
    fun move(pieces: List<Piece>, id: String, target: Target): List<Piece> {
        val src = pieces.firstOrNull { it is Group && it.items.any { m -> m.id == id } } as? Group ?: return pieces
        val item = src.items.first { it.id == id }
        if (target is IntoGroup && target.key == src.key) {
            val list = src.items.toMutableList()
            val from = list.indexOfFirst { it.id == id }
            list.removeAt(from)
            val to = (if (target.index > from) target.index - 1 else target.index).coerceIn(0, list.size)
            list.add(to, item)
            return pieces.map { if (it.key == src.key) src.copy(items = list) else it }
        }
        // 放进别的组：跟那一组的对齐方式走；自成一组：保留自己的
        val placed = insert(pieces, target, listOf(item))
        return removeFrom(placed, src.key, id)
    }

    /** 改某个附件（宽度等） */
    fun update(pieces: List<Piece>, id: String, change: (Media) -> Media): List<Piece> =
        pieces.map { p -> if (p is Group && p.items.any { it.id == id }) p.copy(items = p.items.map { if (it.id == id) change(it) else it }) else p }

    /** 改一整组的对齐 */
    fun align(pieces: List<Piece>, groupKey: Long, align: Int): List<Piece> =
        pieces.map { p -> if (p is Group && p.key == groupKey) p.copy(items = p.items.map { it.copy(align = align) }) else p }

    fun groupOf(pieces: List<Piece>, id: String): Group? =
        pieces.firstOrNull { it is Group && it.items.any { m -> m.id == id } } as? Group

    // ---------------- 尺寸 ----------------

    /** 语音条的宽度（百分比）：像聊天软件那样，越长越宽，1 秒最短、60 秒以上最长 */
    fun audioWidth(dur: Long): Int = (34 + (dur / 1000).coerceIn(0, 60) * 0.7).toInt().coerceIn(34, 76)

    /** 拖角改大小时吸附的宽度（百分比） */
    val SNAPS = listOf(25, 33, 50, 66, 75, 100)

    /** 吸附：离某个常用宽度差 3% 以内就吸过去 */
    fun snapWidth(raw: Float): Int {
        val w = raw.coerceIn(Media.MIN_WIDTH.toFloat(), 100f)
        val near = SNAPS.minByOrNull { kotlin.math.abs(it - w) }!!
        return if (kotlin.math.abs(near - w) <= 3f) near else w.toInt()
    }
}
