package com.beiwang.memo.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class TransferProtoTest {

    @Test
    fun bothSidesDeriveSameKey() {
        val s = TransferProto.newKeyPair()
        val r = TransferProto.newKeyPair()
        val token = TransferProto.newToken()
        val sp = s.public.encoded
        val rp = r.public.encoded
        assertArrayEquals(
            TransferProto.deriveKey(s, rp, sp, rp, token),
            TransferProto.deriveKey(r, sp, sp, rp, token),
        )
    }

    @Test
    fun keyDependsOnToken() {
        val s = TransferProto.newKeyPair()
        val r = TransferProto.newKeyPair()
        val sp = s.public.encoded
        val rp = r.public.encoded
        assertFalse(
            TransferProto.deriveKey(s, rp, sp, rp, TransferProto.newToken())
                .contentEquals(TransferProto.deriveKey(r, sp, sp, rp, TransferProto.newToken()))
        )
    }

    /** 扫到二维码的一方：握手成功，互相拿到设备名，之后的加密帧双向可用 */
    @Test
    fun handshakeWithRightToken() {
        val token = TransferProto.newToken()
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        var receiverName = ""
        var got = ""
        val t = thread {
            val ch = TransferProto.Channel(server.accept())
            receiverName = TransferProto.handshakeAsSender(ch, token, "发送方 \"A\"")
            ch.sendText("清单")
            got = ch.readText()
            ch.close()
        }
        val ch = TransferProto.Channel(Socket(InetAddress.getLoopbackAddress(), server.localPort))
        val senderName = TransferProto.handshakeAsReceiver(ch, token, "接收方 B")
        assertEquals("清单", ch.readText())
        ch.sendText("结果 👍")
        t.join()
        ch.close()
        server.close()
        assertEquals("发送方 \"A\"", senderName)
        assertEquals("接收方 B", receiverName)
        assertEquals("结果 👍", got)
    }

    /** 没扫到二维码（口令不对）的连接：发送方第一帧就解不开，不会发出任何数据 */
    @Test
    fun handshakeWithWrongTokenFails() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        var senderError: Exception? = null
        val t = thread {
            val ch = TransferProto.Channel(server.accept())
            try {
                TransferProto.handshakeAsSender(ch, TransferProto.newToken(), "A")
            } catch (e: Exception) {
                senderError = e
            }
            ch.close()
        }
        val ch = TransferProto.Channel(Socket(InetAddress.getLoopbackAddress(), server.localPort))
        try {
            TransferProto.handshakeAsReceiver(ch, TransferProto.newToken(), "冒充者")
            fail("口令不对也握手成功了")
        } catch (e: Exception) {
            // 发送方断开：读不到回复
        }
        t.join()
        ch.close()
        server.close()
        assertNotNull(senderError)
    }

    /** 帧被调换顺序（或重放）：随机数和帧序号对不上，解密失败 */
    @Test
    fun reorderedFramesAreRejected() {
        val key = VaultCrypto.newContentKey()
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val frames = mutableListOf<ByteArray>()
        val t = thread {
            // 发送方正常发两帧；中间人抓下来，调换顺序转给接收方
            val raw = server.accept()
            val tap = DataInputStream(raw.getInputStream())
            repeat(2) { frames += ByteArray(tap.readInt()).also { tap.readFully(it) } }
            raw.close()
        }
        val sender = TransferProto.Channel(Socket(InetAddress.getLoopbackAddress(), server.localPort))
        sender.secure(key, sender = true)
        sender.sendText("第一帧")
        sender.sendText("第二帧")
        t.join()
        sender.close()
        server.close()

        val relay = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val t2 = thread {
            val out = DataOutputStream(relay.accept().getOutputStream())
            for (f in frames.reversed()) { out.writeInt(f.size); out.write(f) }
            out.flush()
        }
        val receiver = TransferProto.Channel(Socket(InetAddress.getLoopbackAddress(), relay.localPort))
        receiver.secure(key, sender = false)
        try {
            receiver.readText()
            fail("顺序被调换也解开了")
        } catch (e: javax.crypto.AEADBadTagException) {
            // 预期
        }
        t2.join()
        receiver.close()
        relay.close()
    }

    @Test
    fun inviteRoundTrip() {
        val token = TransferProto.newToken()
        val hot = TransferProto.Hotspot("AndroidShare_1234;x=y 你好", "p@ss;w=rd+ %", 3)
        val inv = TransferProto.Invite(token, 40123, listOf("192.168.43.1", "10.0.0.7"), hot)
        val text = inv.encode()
        assertTrue(text.startsWith("BWT1;"))
        assertTrue("二维码内容应是纯 ASCII：${text}", text.all { it.code < 128 })
        val back = TransferProto.parseInvite(text)!!
        assertArrayEquals(token, back.token)
        assertEquals(40123, back.port)
        assertEquals(listOf("192.168.43.1", "10.0.0.7"), back.hosts)
        assertEquals(hot, back.hotspot)

        val plain = TransferProto.parseInvite(TransferProto.Invite(token, 5000, listOf("192.168.1.5"), null).encode())!!
        assertNull(plain.hotspot)
    }

    @Test
    fun foreignCodesAreRejected() {
        assertNull(TransferProto.parseInvite("https://example.com"))
        assertNull(TransferProto.parseInvite("WIFI:S:home;T:WPA;P:12345678;;"))
        assertNull(TransferProto.parseInvite("BWT1;k=abc;p=1;h=1.2.3.4"))                 // 口令长度不对
        val token = TransferProto.newToken()
        val ok = TransferProto.Invite(token, 5000, listOf("192.168.1.5"), null).encode()
        assertNull(TransferProto.parseInvite(ok.replace("192.168.1.5", "example.com")))  // 只接受 IP，不做域名解析
        assertNull(TransferProto.parseInvite(ok.replace("192.168.1.5", "300.1.1.1")))
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
