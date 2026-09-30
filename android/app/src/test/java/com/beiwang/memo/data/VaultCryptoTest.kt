package com.beiwang.memo.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultCryptoTest {

    @Test
    fun textRoundTrip() {
        val k = VaultCrypto.newContentKey()
        val s = VaultCrypto.sealText(k, "银行卡：6222 **** 1234 🔒")
        assertTrue(s.startsWith("v2:"))
        assertEquals("银行卡：6222 **** 1234 🔒", VaultCrypto.openText(k, s))
        assertEquals("", VaultCrypto.sealText(k, ""))
    }

    @Test
    fun sameTextDifferentCipher() {
        val k = VaultCrypto.newContentKey()
        assertNotEquals(VaultCrypto.sealText(k, "a"), VaultCrypto.sealText(k, "a"))
    }

    @Test(expected = Exception::class)
    fun wrongKeyFails() {
        val s = VaultCrypto.sealText(VaultCrypto.newContentKey(), "秘密")
        VaultCrypto.openText(VaultCrypto.newContentKey(), s)
    }

    @Test
    fun headerRoundTrip() {
        val k = VaultCrypto.newContentKey()
        val h = VaultCrypto.makeHeader("vid", "pin", "123456", k)
        val parsed = VaultCrypto.Header.parse(h.toJson())!!
        assertEquals("vid", parsed.id)
        assertEquals("pin", parsed.kind)
        assertArrayEquals(k, VaultCrypto.unwrap(parsed, "123456"))
        assertNull(VaultCrypto.unwrap(parsed, "123457"))
    }

    @Test
    fun patternAndPinDoNotCollide() {
        val salt = VaultCrypto.randomBytes(16)
        assertNotEquals(
            VaultCrypto.derive("pin", "0125", salt, 1000).toList(),
            VaultCrypto.derive("pattern", "0125", salt, 1000).toList(),
        )
    }
}
