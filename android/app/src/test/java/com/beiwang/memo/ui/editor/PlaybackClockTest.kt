package com.beiwang.memo.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class PlaybackClockTest {

    private var t = 0L
    private val clock = PlaybackClock { t }

    private class Frame(val at: Long, val truth: Long, val shown: Long)

    /**
     * 模拟一段播放：[truth] 是此刻真正放到的位置，播放器每 100 毫秒报一次 [reported]，
     * 界面每 8 毫秒（120Hz）取一帧。
     */
    private fun play(ms: Long, dur: Long = 60_000, truth: (Long) -> Long, reported: (Long) -> Long = truth): List<Frame> {
        val out = ArrayList<Frame>()
        val end = t + ms
        while (t <= end) {
            if (t % 100 == 0L) clock.sync(reported(t), dur)
            if (t % 8 == 0L) out += Frame(t, truth(t), clock.position(dur))
            t++
        }
        return out
    }

    private fun jitter(seed: Int, amount: Int): (Long) -> Long {
        val r = Random(seed)
        return { r.nextInt(-amount, amount + 1).toLong() }
    }

    @Test
    fun waitsForTheSoundThenKeepsUp() {
        // 按下播放 150 毫秒后才出声，播放器的读数有 ±10 毫秒抖动
        val j = jitter(1, 10)
        val truth = { at: Long -> (at - 150).coerceAtLeast(0) }
        clock.hold(0)
        val frames = play(3_000, truth = truth, reported = { at -> truth(at).let { if (it == 0L) 0 else (it + j(at)).coerceAtLeast(0) } })
        // 出声之前一直不动
        assertTrue(frames.filter { it.at < 150 }.all { it.shown == 0L })
        // 任何时候都不比声音快多少
        assertTrue(frames.all { it.shown - it.truth <= 30 })
        // 一秒以后基本对齐
        assertTrue(frames.filter { it.at > 1_000 }.all { abs(it.shown - it.truth) <= 25 })
        // 只往前走，开始走之后每帧的步子在正常速度的 0.5～1.5 倍之间（第一步除外：从停着到跟上）
        frames.zipWithNext().forEach { (a, b) -> assertTrue(b.shown >= a.shown) }
        val moving = frames.dropWhile { it.shown == 0L }.drop(1)
        moving.zipWithNext().forEach { (a, b) -> assertTrue("${b.shown - a.shown}", b.shown - a.shown in 3..13) }
    }

    @Test
    fun followsAPlayerThatRunsFastWithoutJumping() {
        // 播放器的钟比系统钟快 3%（比真实设备夸张得多）
        val truth = { at: Long -> at * 103 / 100 }
        clock.hold(0)
        val frames = play(10_000, truth = truth)
        assertTrue(frames.filter { it.at > 2_000 }.all { abs(it.shown - it.truth) <= 25 })
        frames.zipWithNext().forEach { (a, b) -> assertTrue(b.shown >= a.shown) }
    }

    @Test
    fun bigDifferenceSnaps() {
        clock.hold(0)
        play(1_000, truth = { it })
        // 播放器忽然跳到 5 秒（卡了一下又追上来）
        val frames = play(300, truth = { it + 4_000 })
        assertTrue(abs(frames.last().shown - frames.last().truth) <= 10)
    }

    @Test
    fun holdsAfterSeekUntilThePlayerMoves() {
        clock.hold(0)
        play(500, truth = { it })
        // 拖到 5 秒：播放器在 200 毫秒内都报 5000（还在跳），之后才接着放
        clock.hold(5_000)
        val start = t
        val truth = { at: Long -> 5_000 + (at - start - 200).coerceAtLeast(0) }
        val frames = play(1_000, truth = truth)
        assertTrue(frames.filter { it.at - start < 200 }.all { it.shown == 5_000L })
        assertTrue(frames.all { it.shown - it.truth <= 10 })
        assertTrue(abs(frames.last().shown - frames.last().truth) <= 10)
    }

    @Test
    fun neverPastTheEnd() {
        clock.hold(0)
        val frames = play(2_000, dur = 1_000, truth = { it.coerceAtMost(1_000) })
        assertTrue(frames.all { it.shown <= 1_000 })
        assertEquals(1_000L, frames.last().shown)
    }
}
