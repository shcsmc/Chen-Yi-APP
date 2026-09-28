package com.beiwang.memo.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class TransferProtoTest {

    @Test
    fun bothSidesDeriveSameKeyAndCode() {
        val s = TransferProto.newKeyPair()
        val r = TransferProto.newKeyPair()
        val sp = s.public.encoded
        val rp = r.public.encoded
        val (k1, c1) = TransferProto.derive(s, rp, sp, rp)
        val (k2, c2) = TransferProto.derive(r, sp, sp, rp)
        assertArrayEquals(k1, k2)
        assertEquals(c1, c2)
        assertTrue(Regex("\\d{3} \\d{3}").matches(c1))
    }

    @Test
    fun manInTheMiddleShowsDifferentCodes() {
        val s = TransferProto.newKeyPair()
        val r = TransferProto.newKeyPair()
        val m = TransferProto.newKeyPair()
        // 发送方以为在和 r 通话，实际拿到的是 m 的公钥
        val (_, codeAtSender) = TransferProto.derive(s, m.public.encoded, s.public.encoded, m.public.encoded)
        val (_, codeAtReceiver) = TransferProto.derive(r, m.public.encoded, m.public.encoded, r.public.encoded)
        assertNotEquals(codeAtSender, codeAtReceiver)
    }

    @Test
    fun encryptedFramesOverSocket() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val key = VaultCrypto.newContentKey()
        var got = ""
        val t = thread {
            val ch = TransferProto.Channel(server.accept())
            ch.key = key
            got = ch.readText()
            ch.sendText("收到：$got")
            ch.close()
        }
        val ch = TransferProto.Channel(Socket(InetAddress.getLoopbackAddress(), server.localPort))
        ch.key = key
        ch.sendText("你好 👋")
        assertEquals("收到：你好 👋", ch.readText())
        t.join()
        ch.close()
        server.close()
        assertEquals("你好 👋", got)
    }

    @Test
    fun messagesRoundTrip() {
        val m = TransferProto.Manifest(12, 3, 45678, "Pixel \"9\"")
        assertEquals(m, TransferProto.parseManifest(m.toJson()))
        assertEquals("manifest", TransferProto.type(m.toJson()))
        val o = TransferProto.Outcome(true, 1, 2, 3, 0, "好")
        assertEquals(o, TransferProto.parseOutcome(o.toJson()))
    }
}
