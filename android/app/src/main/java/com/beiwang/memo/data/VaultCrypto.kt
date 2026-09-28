package com.beiwang.memo.data

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 保险箱用到的纯算法（不碰安卓系统，能在电脑上跑单元测试）。
 *
 * - 内容密钥 K：随机 256 位，真正加密笔记和图片（AES-256-GCM）。
 * - 密码密钥 P = PBKDF2-HMAC-SHA256("pin:123456" / "pattern:0-1-2-5", salt, 次数)，只用来包住 K。
 *   在本机上，包好的 K 还会再用系统安全芯片里的密钥包一层（见 Vault），拷走文件也没法离线猜密码；
 *   备份/传输时只带「P 包住的 K」（便携头），换到别的手机输原密码就能打开。
 */
object VaultCrypto {

    const val ITERATIONS = 210_000
    private val rnd = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { rnd.nextBytes(it) }

    fun newContentKey(): ByteArray = randomBytes(32)

    /** 密码串：带上类型，图案和数字不会互相撞 */
    fun secretString(kind: String, secret: String) = "$kind:$secret"

    fun derive(kind: String, secret: String, salt: ByteArray, iterations: Int = ITERATIONS): ByteArray {
        val spec = PBEKeySpec(secretString(kind, secret).toCharArray(), salt, iterations, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    // ---------- AES-GCM：输出 = iv(12) || 密文+标签 ----------

    fun seal(key: ByteArray, plain: ByteArray): ByteArray {
        val iv = randomBytes(12)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return iv + c.doFinal(plain)
    }

    /** 密钥不对或数据被改过会抛异常 */
    fun open(key: ByteArray, sealed: ByteArray): ByteArray {
        require(sealed.size > 12 + 16) { "数据太短" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, sealed, 0, 12))
        return c.doFinal(sealed, 12, sealed.size - 12)
    }

    // ---------- 文字：存成 "v2:" + base64(iv||密文) ----------

    private const val TEXT_PREFIX = "v2:"

    fun sealText(key: ByteArray, text: String): String =
        if (text.isEmpty()) "" else TEXT_PREFIX + b64(seal(key, text.toByteArray(Charsets.UTF_8)))

    fun openText(key: ByteArray, stored: String): String =
        if (stored.isEmpty()) "" else String(open(key, unb64(stored.removePrefix(TEXT_PREFIX))), Charsets.UTF_8)

    // ---------- 便携头：P 包住的 K，随备份/传输走 ----------

    /** 保险箱的便携描述：id 用来识别「是不是同一个保险箱」 */
    data class Header(val id: String, val kind: String, val salt: ByteArray, val iterations: Int, val wrapped: ByteArray) {
        fun toJson(): String =
            """{"id":"$id","kind":"$kind","salt":"${b64(salt)}","iter":$iterations,"key":"${b64(wrapped)}"}"""

        companion object {
            fun parse(json: String): Header? = runCatching {
                fun f(k: String) = Regex("\"$k\"\\s*:\\s*\"([^\"]*)\"").find(json)!!.groupValues[1]
                val iter = Regex("\"iter\"\\s*:\\s*(\\d+)").find(json)!!.groupValues[1].toInt()
                Header(f("id"), f("kind"), unb64(f("salt")), iter, unb64(f("key")))
            }.getOrNull()
        }
    }

    fun makeHeader(id: String, kind: String, secret: String, contentKey: ByteArray): Header {
        val salt = randomBytes(16)
        val p = derive(kind, secret, salt)
        return Header(id, kind, salt, ITERATIONS, seal(p, contentKey))
    }

    /** 用密码打开便携头拿到 K；密码不对返回 null */
    fun unwrap(header: Header, secret: String): ByteArray? = runCatching {
        open(derive(header.kind, secret, header.salt, header.iterations), header.wrapped)
    }.getOrNull()

    fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
    fun unb64(s: String): ByteArray = Base64.getDecoder().decode(s)
}
