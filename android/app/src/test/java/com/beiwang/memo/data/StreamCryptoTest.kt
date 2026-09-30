package com.beiwang.memo.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.random.Random

class StreamCryptoTest {

    private val key = VaultCrypto.newContentKey()
    private val c = StreamCrypto.CHUNK

    private fun seal(plain: ByteArray, k: ByteArray = key): ByteArray =
        ByteArrayOutputStream().also { StreamCrypto.seal(k, ByteArrayInputStream(plain), it) }.toByteArray()

    private fun open(sealed: ByteArray, k: ByteArray = key): ByteArray =
        ByteArrayOutputStream().also { StreamCrypto.open(k, ByteArrayInputStream(sealed), it) }.toByteArray()

    private fun expectFailure(block: () -> Unit) {
        try {
            block()
            fail("应该解不开")
        } catch (e: Exception) {
            // 预期
        }
    }

    @Test
    fun roundTripAtChunkBoundaries() {
        for (n in listOf(0, 1, 100, c - 1, c, c + 1, 2 * c, 3 * c + 17)) {
            val plain = Random(n).nextBytes(n)
            val sealed = seal(plain)
            assertArrayEquals("长度 $n", plain, open(sealed))
            // 块数 = 满块 + 最后一块（正好满块时不多出空块）
            val chunks = if (n == 0) 1 else (n + c - 1) / c
            assertEquals(24L + n + 16L * chunks, sealed.size.toLong())
        }
    }

    @Test
    fun wrongKeyFails() {
        val sealed = seal(Random(1).nextBytes(5000))
        expectFailure { open(sealed, VaultCrypto.newContentKey()) }
    }

    @Test
    fun tamperingIsDetected() {
        val sealed = seal(Random(2).nextBytes(2 * c + 10))
        // 改一个字节
        expectFailure { open(sealed.copyOf().also { it[24 + c + 50] = (it[24 + c + 50] + 1).toByte() }) }
        // 截掉最后一块（剩下的正好是完整的块）
        expectFailure { open(sealed.copyOf(24 + 2 * (c + 16))) }
        // 调换前两块
        val a = sealed.copyOfRange(24, 24 + c + 16)
        val b = sealed.copyOfRange(24 + c + 16, 24 + 2 * (c + 16))
        val swapped = sealed.copyOf()
        System.arraycopy(b, 0, swapped, 24, b.size)
        System.arraycopy(a, 0, swapped, 24 + c + 16, a.size)
        expectFailure { open(swapped) }
    }

    @Test
    fun randomAccessReads() {
        val plain = Random(3).nextBytes(3 * c + 1234)
        val f = File.createTempFile("bws", ".bin")
        try {
            f.writeBytes(seal(plain))
            StreamCrypto.Reader(key, f).use { r ->
                assertEquals(plain.size.toLong(), r.size)
                val rnd = Random(4)
                repeat(300) {
                    val pos = rnd.nextLong(0, plain.size.toLong())
                    val len = rnd.nextInt(1, 3 * c)
                    val buf = ByteArray(len + 7)
                    val n = r.readAt(pos, buf, 7, len)
                    val expect = minOf(len.toLong(), plain.size - pos).toInt()
                    assertEquals(expect, n)
                    assertArrayEquals(plain.copyOfRange(pos.toInt(), pos.toInt() + n), buf.copyOfRange(7, 7 + n))
                }
                assertEquals(-1, r.readAt(plain.size.toLong(), ByteArray(10), 0, 10))
            }
        } finally {
            f.delete()
        }
    }

    @Test
    fun emptyFileReader() {
        val f = File.createTempFile("bws", ".bin")
        try {
            f.writeBytes(seal(ByteArray(0)))
            StreamCrypto.Reader(key, f).use { r ->
                assertEquals(0L, r.size)
                assertEquals(-1, r.readAt(0, ByteArray(4), 0, 4))
            }
        } finally {
            f.delete()
        }
    }

    @Test
    fun resealWithAnotherKey() {
        val plain = Random(5).nextBytes(2 * c + 99)
        val other = VaultCrypto.newContentKey()
        val sealed = seal(plain, other)
        val out = ByteArrayOutputStream()
        StreamCrypto.reseal(other, key, ByteArrayInputStream(sealed), out)
        assertArrayEquals(plain, open(out.toByteArray()))
        expectFailure { open(out.toByteArray(), other) }
    }

    @Test
    fun filesAreWrittenAtomically() {
        val dir = kotlin.io.path.createTempDirectory("bws").toFile()
        try {
            val src = File(dir, "a.bin").also { it.writeBytes(Random(6).nextBytes(c + 5)) }
            val sealed = File(dir, "a.sealed")
            val back = File(dir, "a.back")
            assertEquals(src.length(), StreamCrypto.sealFile(key, src, sealed))
            assertTrue(StreamCrypto.isSealed(sealed))
            assertTrue(!StreamCrypto.isSealed(src))
            StreamCrypto.openFile(key, sealed, back)
            assertArrayEquals(src.readBytes(), back.readBytes())
            // 失败时不留临时文件，也不动目标文件
            val wrong = File(dir, "wrong")
            expectFailure { StreamCrypto.openFile(VaultCrypto.newContentKey(), sealed, wrong) }
            assertTrue(!wrong.exists() && !File(dir, "wrong.tmp").exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
