package com.beiwang.memo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WavesTest {

    @Test
    fun encodeDecodeRoundTrip() {
        val levels = List(500) { i -> ((i % 50) / 50f) }
        val s = Waves.encode(levels)
        assertTrue(s.length < 100)
        val back = Waves.decode(s)!!
        assertEquals(Waves.SIZE, back.size)
        assertTrue(back.all { it in 0f..1f })
        assertEquals(1f, back.maxOrNull()!!, 0.01f)       // 拉满
    }

    @Test
    fun emptyAndBroken() {
        assertEquals("", Waves.encode(emptyList()))
        assertNull(Waves.decode(""))
        assertNull(Waves.decode("不是 base64"))
    }

    @Test
    fun shrinkKeepsPeaks() {
        // 一长串安静里夹一个短促的响声：缩小后那个响声不能被平均掉
        val src = FloatArray(1000) { 0.05f }.also { it[503] = 0.9f }
        val out = Waves.resample(src, 20)
        assertEquals(1f, out.maxOrNull()!!, 0.001f)
        assertEquals(1, out.count { it > 0.5f })
    }

    @Test
    fun growInterpolates() {
        val out = Waves.resample(floatArrayOf(0f, 1f), 5)
        assertEquals(5, out.size)
        assertEquals(0f, out[0], 0.001f)
        assertEquals(0.5f, out[2], 0.001f)
        assertEquals(1f, out[4], 0.001f)
    }

    @Test
    fun placeholderIsStable() {
        val a = Waves.placeholder("abc")
        val b = Waves.placeholder("abc")
        assertTrue(a.contentEquals(b))
        assertEquals(Waves.SIZE, a.size)
    }
}
