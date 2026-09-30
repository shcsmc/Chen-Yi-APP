package com.beiwang.memo.legacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** 测试向量由 Node 的 WebCrypto 按旧网页版同样的参数生成 */
class LegacyCryptoTest {

    private val salt = "AQIDBAUGBwgJCgsMDQ4PEA=="
    private val iv = "FRYXGBkaGxwdHh8g"
    private val chk = "9+KF+Rg/Fhr/jr1lLznZazybzFOvcc0="
    private val body = "v1:KSorLC0uLzAxMjM0:i1mAjBNwKNt/WY8sByhyXb88UK7Lq6nhq8P8llLcfEWrdtXmnhD3nTwXn2Z50dbP"

    private fun lock() = java.util.Base64.getDecoder().let {
        LegacyCrypto.Lock(it.decode(salt), it.decode(iv), it.decode(chk))
    }

    @Test
    fun rightPatternDecrypts() {
        val key = LegacyCrypto.unlock("0-1-2-5-8", lock())
        assertNotNull(key)
        assertEquals("买菜：西红柿、鸡蛋 🍅", LegacyCrypto.decrypt(key!!, body))
    }

    @Test
    fun wrongPatternRejected() {
        assertNull(LegacyCrypto.unlock("0-1-2-5", lock()))
    }

    @Test
    fun plainBodyPassesThrough() {
        assertEquals("明文", LegacyCrypto.decrypt(ByteArray(32), "明文"))
    }

    @Test
    fun parsesLockJson() {
        assertNotNull(LegacyCrypto.parseLock("""{"salt":"$salt","iv":"$iv","chk":"$chk"}"""))
    }
}
