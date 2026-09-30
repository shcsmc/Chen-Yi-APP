package com.beiwang.memo.legacy

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 解开旧网页版「图案锁」加密过的备忘（旧版已关闭这个功能，这里只负责把遗留密文转回明文）。
 *
 * 旧版算法（WebCrypto）：key = PBKDF2-SHA256(图案串, salt, 150000 次, 256 位)，
 * 正文 = "v1:" + base64(iv) + ":" + base64(AES-256-GCM 密文+标签)；
 * 校验块 chk = 用同一把 key 加密 "MEMO-OK"。图案串形如 "0-1-2-5-8"（点位 0..8）。
 */
object LegacyCrypto {

    class Lock(val salt: ByteArray, val iv: ByteArray, val chk: ByteArray)

    /** 配置是个只有三个字符串字段的小 JSON：{"salt":"…","iv":"…","chk":"…"} */
    fun parseLock(json: String): Lock? = runCatching {
        fun field(k: String) = Regex("\"$k\"\\s*:\\s*\"([^\"]+)\"").find(json)!!.groupValues[1]
        Lock(b64(field("salt")), b64(field("iv")), b64(field("chk")))
    }.getOrNull()

    /** 图案对就返回密钥，不对返回 null。PBKDF2 15 万次，手机上约半秒，要放后台线程 */
    fun unlock(pattern: String, lock: Lock): ByteArray? {
        val key = pbkdf2(pattern.toByteArray(Charsets.UTF_8), lock.salt, 150_000, 32)
        val ok = runCatching { String(aesGcm(key, lock.iv, lock.chk), Charsets.UTF_8) }.getOrNull()
        return if (ok == "MEMO-OK") key else null
    }

    /** 解一条 "v1:iv:ct"；解不开返回 null */
    fun decrypt(key: ByteArray, body: String): String? {
        if (!body.startsWith("v1:")) return body
        val p = body.split(':')
        if (p.size != 3) return null
        return runCatching { String(aesGcm(key, b64(p[1]), b64(p[2])), Charsets.UTF_8) }.getOrNull()
    }

    private fun aesGcm(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            doFinal(data)
        }

    /** 手写 PBKDF2-HMAC-SHA256：和 WebCrypto 逐字节一致，不依赖各家系统对 SecretKeyFactory 的实现差异 */
    internal fun pbkdf2(password: ByteArray, salt: ByteArray, iterations: Int, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(password, "HmacSHA256")) }
        val out = ByteArray(length)
        var block = 1
        var off = 0
        while (off < length) {
            mac.update(salt)
            mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
            var u = mac.doFinal()
            val t = u.copyOf()
            for (i in 1 until iterations) {
                u = mac.doFinal(u)
                for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
            }
            val n = minOf(t.size, length - off)
            System.arraycopy(t, 0, out, off, n)
            off += n
            block++
        }
        return out
    }

    private fun b64(s: String): ByteArray = Base64.getDecoder().decode(s)
}
