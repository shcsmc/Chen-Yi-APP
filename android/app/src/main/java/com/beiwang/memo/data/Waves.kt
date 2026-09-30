package com.beiwang.memo.data

import java.util.Base64
import kotlin.math.max
import kotlin.math.min

/**
 * 语音条的波形：录音时每 80 毫秒记一次音量（0..1），录完压成 [SIZE] 个数存进 [Media.wave]
 * （每个数一个字节，base64，几十个字符）。显示时再按语音条的宽度重新取样。纯算法，有单元测试。
 * 没有波形的（旧版录的、从别处导入的）用按 id 生成的固定起伏代替，看起来也像一段话。
 */
object Waves {

    const val SIZE = 64

    /** 录音时的音量序列 → 存储用的字符串；没有数据返回空串 */
    fun encode(levels: List<Float>): String {
        if (levels.isEmpty()) return ""
        val packed = resample(levels.toFloatArray(), SIZE)
        val bytes = ByteArray(packed.size) { i -> (packed[i].coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte() }
        return Base64.getEncoder().encodeToString(bytes)
    }

    /** 存储的字符串 → 0..1 的数组；空的或坏的返回 null */
    fun decode(s: String): FloatArray? {
        if (s.isEmpty()) return null
        val bytes = runCatching { Base64.getDecoder().decode(s) }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null
        return FloatArray(bytes.size) { i -> (bytes[i].toInt() and 0xff) / 255f }
    }

    /**
     * 重新取样成 [n] 个：变少时每段取最大值（短促的字音不会被抹平），变多时线性插值。
     * 最后按最响的那个拉满，安静的录音也看得出起伏。
     */
    fun resample(src: FloatArray, n: Int): FloatArray {
        if (n <= 0) return FloatArray(0)
        if (src.isEmpty()) return FloatArray(n)
        val out = FloatArray(n)
        if (src.size >= n) {
            for (i in 0 until n) {
                val a = (i.toLong() * src.size / n).toInt()
                val b = max(a + 1, ((i + 1).toLong() * src.size / n).toInt())
                var m = 0f
                for (j in a until min(b, src.size)) m = max(m, src[j])
                out[i] = m
            }
        } else {
            for (i in 0 until n) {
                val pos = if (n == 1) 0f else i * (src.size - 1f) / (n - 1f)
                val a = pos.toInt().coerceIn(0, src.size - 1)
                val b = min(a + 1, src.size - 1)
                val t = pos - a
                out[i] = src[a] * (1 - t) + src[b] * t
            }
        }
        val peak = out.maxOrNull() ?: 0f
        if (peak > 0.05f) for (i in out.indices) out[i] = (out[i] / peak).coerceIn(0f, 1f)
        return out
    }

    /** 没有真实波形时的替代：按 [seed] 生成固定的高低（同一条语音每次看到的一样） */
    fun placeholder(seed: String): FloatArray {
        val r = java.util.Random(seed.hashCode().toLong())
        return FloatArray(SIZE) { 0.25f + r.nextFloat() * 0.75f }
    }
}
