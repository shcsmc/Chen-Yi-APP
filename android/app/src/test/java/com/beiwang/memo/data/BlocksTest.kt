package com.beiwang.memo.data

import com.beiwang.memo.data.Blocks.Group
import com.beiwang.memo.data.Blocks.IntoGroup
import com.beiwang.memo.data.Blocks.IntoText
import com.beiwang.memo.data.Blocks.Piece
import com.beiwang.memo.data.Blocks.Text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class BlocksTest {

    private fun img(id: String, at: Int = -1) = Media(id, 100, 100, at = at)

    /** 只看内容（文字 / 附件 id），不看 key */
    private fun shape(pieces: List<Piece>): List<Any> =
        pieces.map { p -> if (p is Text) p.text else (p as Group).items.map { it.id } }

    @Test
    fun noMediaIsJustTheBody() {
        val p = Blocks.split("第一段\n第二段", emptyList())
        assertEquals(listOf("第一段\n第二段"), shape(p))
        assertEquals("第一段\n第二段" to emptyList<Media>(), Blocks.join(p))
    }

    @Test
    fun legacyImagesGoToTheEnd() {
        // 旧数据：图片 at = -1，一律在文末，正文原样
        val p = Blocks.split("hello", listOf(img("a"), img("b")))
        assertEquals(listOf("hello", listOf("a", "b"), ""), shape(p))
        val (body, media) = Blocks.join(p)
        assertEquals("hello\n", body)
        assertTrue(media.all { it.at == 6 })
        // 标准形和原始数据的标准形一致 → 打开再关上不算改动
        assertEquals(Blocks.canonical("hello", listOf(img("a"), img("b"))), Blocks.join(p))
    }

    @Test
    fun mediaBetweenParagraphsTakesTheLineBreak() {
        val p = listOf(Text(1, "第一段"), Group(2, listOf(img("x"))), Text(3, "第二段"))
        val (body, media) = Blocks.join(p)
        assertEquals("第一段\n第二段", body)             // 删掉附件文字不变
        assertEquals(4, media.single().at)
        assertEquals(listOf("第一段", listOf("x"), "第二段"), shape(Blocks.split(body, media)))
    }

    @Test
    fun emptyTextBetweenGroupsKeepsThemApart() {
        val p = listOf(Text(1, "a"), Group(2, listOf(img("x"))), Text(3, ""), Group(4, listOf(img("y"))), Text(5, ""))
        val (body, media) = Blocks.join(p)
        assertEquals("a\n\n", body)
        assertEquals(listOf(2, 3), media.map { it.at })
        assertEquals(listOf("a", listOf("x"), "", listOf("y"), ""), shape(Blocks.split(body, media)))
    }

    @Test
    fun trailingNewlinesOfUserTextSurvive() {
        val p = listOf(Text(1, "a\n"), Group(2, listOf(img("x"))), Text(3, "\nb\n"))
        val (body, media) = Blocks.join(p)
        assertEquals(listOf("a\n", listOf("x"), "\nb\n"), shape(Blocks.split(body, media)))
    }

    @Test
    fun insertAtLineStartUsesTheExistingLineBreak() {
        val start = Blocks.split("第一段\n第二段", emptyList())
        val key = start[0].key
        // 光标在「第二段」行首
        val p = Blocks.insert(start, IntoText(key, 4), listOf(img("x")))
        assertEquals(listOf("第一段", listOf("x"), "第二段"), shape(p))
        assertEquals("第一段\n第二段", Blocks.join(p).first)
        // 光标在「第一段」行尾：一样
        val q = Blocks.insert(start, IntoText(key, 3), listOf(img("x")))
        assertEquals(listOf("第一段", listOf("x"), "第二段"), shape(q))
    }

    @Test
    fun insertInMiddleOfLineBreaksTheLine() {
        val start = Blocks.split("hello world", emptyList())
        val p = Blocks.insert(start, IntoText(start[0].key, 6), listOf(img("x")))
        assertEquals(listOf("hello ", listOf("x"), "world"), shape(p))
        assertEquals("hello \nworld", Blocks.join(p).first)
    }

    @Test
    fun insertOnEmptyLineConsumesIt() {
        val start = Blocks.split("a\n\nb", emptyList())
        val p = Blocks.insert(start, IntoText(start[0].key, 2), listOf(img("x")))
        assertEquals(listOf("a", listOf("x"), "b"), shape(p))
    }

    @Test
    fun insertIntoGroupAdoptsAlignment() {
        val start = listOf(Text(1, "t"), Group(2, listOf(img("a").copy(align = 1))), Text(3, ""))
        val p = Blocks.insert(start, IntoGroup(2, 1), listOf(img("b")))
        assertEquals(listOf("t", listOf("a", "b"), ""), shape(p))
        assertEquals(listOf(1, 1), (p[1] as Group).items.map { it.align })
    }

    @Test
    fun removingLastItemGluesTextBack() {
        val start = Blocks.split("第一段\n第二段", emptyList())
        val p = Blocks.insert(start, IntoText(start[0].key, 4), listOf(img("x")))
        val q = Blocks.remove(p, "x")
        assertEquals(listOf("第一段\n第二段"), shape(q))
    }

    @Test
    fun removingFromBiggerGroupKeepsTheRest() {
        val p = listOf(Text(1, "t"), Group(2, listOf(img("a"), img("b"))), Text(3, "u"))
        assertEquals(listOf("t", listOf("b"), "u"), shape(Blocks.remove(p, "a")))
    }

    @Test
    fun moveWithinGroupReorders() {
        val p = listOf(Text(1, ""), Group(2, listOf(img("a"), img("b"), img("c"))), Text(3, ""))
        assertEquals(listOf("", listOf("b", "c", "a"), ""), shape(Blocks.move(p, "a", IntoGroup(2, 3))))
        assertEquals(listOf("", listOf("c", "a", "b"), ""), shape(Blocks.move(p, "c", IntoGroup(2, 0))))
        assertEquals(listOf("", listOf("a", "b", "c"), ""), shape(Blocks.move(p, "b", IntoGroup(2, 1))))
    }

    @Test
    fun moveBetweenParagraphs() {
        val start = Blocks.split("一\n二\n三", emptyList())
        val p = Blocks.insert(start, IntoText(start[0].key, 2), listOf(img("x")))    // 一 / x / 二\n三
        assertEquals(listOf("一", listOf("x"), "二\n三"), shape(p))
        val last = p[2] as Text
        val q = Blocks.move(p, "x", IntoText(last.key, 2))                               // 挪到「三」前面
        assertEquals(listOf("一\n二", listOf("x"), "三"), shape(q))
        assertEquals("一\n二\n三", Blocks.join(q).first)                                // 文字始终没变
    }

    @Test
    fun moveNextToAnotherItemJoinsItsGroup() {
        val p = listOf(Text(1, "t"), Group(2, listOf(img("a"))), Text(3, "u"), Group(4, listOf(img("b"))), Text(5, ""))
        val q = Blocks.move(p, "b", IntoGroup(2, 1))
        assertEquals(listOf("t", listOf("a", "b"), "u"), shape(q))
    }

    @Test
    fun moveToSameSpotIsNoOp() {
        val p = listOf(Text(1, "t"), Group(2, listOf(img("a"))), Text(3, "u"))
        val q = Blocks.move(p, "a", IntoText(1, 1))
        assertEquals(shape(p), shape(q))
        assertEquals(Blocks.join(p).first, Blocks.join(q).first)
    }

    @Test
    fun widthSnaps() {
        assertEquals(50, Blocks.snapWidth(52f))
        assertEquals(100, Blocks.snapWidth(98f))
        assertEquals(42, Blocks.snapWidth(42.4f))
        assertEquals(Media.MIN_WIDTH, Blocks.snapWidth(5f))
    }

    @Test
    fun audioCanBeResizedButNotTooNarrow() {
        val a = Media("a", 0, 0, MediaKind.Audio, dur = 5_000)
        assertEquals(Blocks.audioWidth(5_000), a.displayWidth)            // 没拖过：按时长
        assertEquals(60, a.copy(width = 60).displayWidth)                 // 拖过：按拖的
        assertEquals(Media.MIN_AUDIO_WIDTH, a.copy(width = 10).displayWidth)
        assertEquals(Media.MIN_AUDIO_WIDTH, Blocks.snapWidth(12f, a.minWidth))
        assertEquals(33, Blocks.snapWidth(31f, a.minWidth))                // 吸附也不低于下限
    }

    @Test
    fun audioWidthGrowsWithDuration() {
        assertTrue(Blocks.audioWidth(1_000) < Blocks.audioWidth(30_000))
        assertEquals(Blocks.audioWidth(60_000), Blocks.audioWidth(600_000))
    }

    /** 随机拼一堆序列：合 → 拆 → 合，结果不变；拆出来的永远是 T, G, T, … , T */
    @Test
    fun randomRoundTrips() {
        val rnd = Random(7)
        val words = listOf("", "", "a", "bc", "第一段", "x\ny", "\n", "\n\n", "end\n", "\nstart")
        var id = 0
        repeat(2000) {
            val pieces = ArrayList<Piece>()
            val n = rnd.nextInt(0, 6)
            pieces += Text(Blocks.newKey(), words.random(rnd))
            repeat(n) {
                pieces += Group(Blocks.newKey(), List(rnd.nextInt(1, 4)) { img("m${id++}") })
                pieces += Text(Blocks.newKey(), words.random(rnd))
            }
            val (body, media) = Blocks.join(pieces)
            val back = Blocks.split(body, media)
            assertEquals(shape(Blocks.normalize(pieces)), shape(back))
            assertEquals(body to media, Blocks.join(back))
            // 交替结构
            back.forEachIndexed { i, p -> assertTrue(if (i % 2 == 0) p is Text else p is Group) }
            // 所有附件都在，顺序不变
            assertEquals(pieces.filterIsInstance<Group>().flatMap { g -> g.items.map { it.id } }, media.map { it.id })
        }
    }

    /** 随机做插入/删除/挪动：文字内容（去掉附件占的换行）始终完整，结构始终合法 */
    @Test
    fun randomEditsKeepStructure() {
        val rnd = Random(11)
        var id = 0
        repeat(300) {
            var p: List<Piece> = Blocks.split("一\n二\n三\n四", emptyList())
            val alive = HashSet<String>()
            repeat(40) {
                when (rnd.nextInt(4)) {
                    0 -> {
                        val t = p.filterIsInstance<Text>().random(rnd)
                        val m = img("m${id++}")
                        p = Blocks.insert(p, IntoText(t.key, rnd.nextInt(0, t.text.length + 1)), listOf(m))
                        alive += m.id
                    }
                    1 -> if (alive.isNotEmpty()) {
                        val victim = alive.random(rnd)
                        p = Blocks.remove(p, victim)
                        alive -= victim
                    }
                    2 -> if (alive.isNotEmpty()) {
                        val t = p.filterIsInstance<Text>().random(rnd)
                        p = Blocks.move(p, alive.random(rnd), IntoText(t.key, rnd.nextInt(0, t.text.length + 1)))
                    }
                    else -> if (alive.isNotEmpty()) {
                        val g = p.filterIsInstance<Group>().random(rnd)
                        p = Blocks.move(p, alive.random(rnd), IntoGroup(g.key, rnd.nextInt(0, g.items.size + 1)))
                    }
                }
                p.forEachIndexed { i, x -> assertTrue(if (i % 2 == 0) x is Text else x is Group) }
                val ids = p.filterIsInstance<Group>().flatMap { g -> g.items.map { it.id } }
                assertEquals(alive, ids.toSet())
                assertEquals(ids.size, ids.toSet().size)
                val (body, media) = Blocks.join(p)
                // 四段文字一个不少（插入可能把一行断开，所以只数汉字）
                assertEquals("一二三四", body.filter { it != '\n' })
                assertEquals(body to media, Blocks.join(Blocks.split(body, media)))
            }
        }
    }
}
