package com.beiwang.memo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrTest {

    /** 把模块矩阵画成一帧「相机画面」：每个模块 [scale]×[scale] 像素，四周留白，每行末尾有多余字节（模拟 rowStride） */
    private fun render(m: Array<BooleanArray>, scale: Int, margin: Int, padding: Int): Triple<ByteArray, Int, Pair<Int, Int>> {
        val w = (m[0].size + margin * 2) * scale
        val h = (m.size + margin * 2) * scale
        val stride = w + padding
        val data = ByteArray(stride * h) { 0xE0.toByte() }   // 浅色底
        for (y in 0 until h) for (x in 0 until w) {
            val my = y / scale - margin
            val mx = x / scale - margin
            val dark = my in m.indices && mx in m[0].indices && m[my][mx]
            data[y * stride + x] = if (dark) 0x18 else 0xE0.toByte()
        }
        return Triple(data, stride, w to h)
    }

    @Test
    fun encodeThenDecode() {
        val inv = TransferProto.Invite(
            TransferProto.newToken(), 40123, listOf("192.168.43.1", "10.12.0.7", "192.168.1.23"),
            TransferProto.Hotspot("AndroidShare_4821", "k3v9-xq2m-81pz", 1),
        ).encode()
        val m = Qr.encode(inv)
        val (data, stride, size) = render(m, scale = 5, margin = 4, padding = 24)
        assertEquals(inv, Qr.Reader().decode(data, stride, size.first, size.second))
    }

    @Test
    fun blankFrameGivesNull() {
        val w = 320
        val h = 240
        assertNull(Qr.Reader().decode(ByteArray(w * h) { 0x80.toByte() }, w, w, h))
        assertNull(Qr.Reader().decode(ByteArray(10), w, w, h))   // 数据不够一帧
    }
}
