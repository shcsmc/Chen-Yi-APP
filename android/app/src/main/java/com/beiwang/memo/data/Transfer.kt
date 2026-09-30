package com.beiwang.memo.data

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 两台手机之间的传输协议（纯 Java 网络 + 加密，不含界面，可单元测试）。
 *
 * 发送方开一个端口，把「邀请」显示成二维码：地址、端口、一次性口令（16 字节随机数），开热点时还有热点名和密码。
 * 接收方扫码后连过来：
 * 1. 双方交换临时 EC 公钥（P-256），ECDH 算出共享密钥；
 * 2. 会话密钥 = HMAC(口令, 共享密钥 + 双方公钥)：没扫到二维码的人（同一 Wi-Fi 里扫端口的、冒充中间人的）
 *    算不出会话密钥，第一帧就解不开，发送方直接断开它、继续等；口令 128 位，也没法离线猜；
 * 3. 之后每一帧 AES-256-GCM 加密，随机数由「方向 + 帧序号」构成：帧被重放、调换顺序都解不开；
 *    内容依次是：问候（设备名）、清单（条数、图片数、字节数）、备份文件分块、导入结果。
 */
object TransferProto {

    const val CHUNK = 64 * 1024
    private const val MAX_FRAME = 1 shl 20
    private const val TOKEN_BYTES = 16

    /** 二维码内容的开头：一眼认出是不是备忘的传输码，也给以后改格式留余地 */
    private const val INVITE_PREFIX = "BWT1"

    fun newKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    fun newToken(): ByteArray = ByteArray(TOKEN_BYTES).also { SecureRandom().nextBytes(it) }

    /** 会话密钥。[senderPub]/[receiverPub] 两边顺序必须一致（发送方在前） */
    fun deriveKey(mine: KeyPair, peerPub: ByteArray, senderPub: ByteArray, receiverPub: ByteArray, token: ByteArray): ByteArray {
        val peer = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(peerPub))
        val shared = KeyAgreement.getInstance("ECDH").run {
            init(mine.private)
            doPhase(peer, true)
            generateSecret()
        }
        return Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(token, "HmacSHA256"))
            update("beiwang-transfer-v2".toByteArray())
            update(shared)
            update(senderPub)
            update(receiverPub)
            doFinal()
        }
    }

    // ============================== 二维码里的邀请 ==============================

    /** 发送方开的临时热点。[security]：0 开放，1 WPA2（含 WPA2/WPA3 过渡），3 WPA3，4 增强型开放（OWE） */
    data class Hotspot(val ssid: String, val pass: String, val security: Int)

    class Invite(val token: ByteArray, val port: Int, val hosts: List<String>, val hotspot: Hotspot?) {
        /** 例：BWT1;k=口令;p=40123;h=192.168.1.5,10.0.0.2;s=热点名;w=密码;t=1 */
        fun encode(): String = buildString {
            append(INVITE_PREFIX)
            append(";k=").append(Base64.getUrlEncoder().withoutPadding().encodeToString(token))
            append(";p=").append(port)
            append(";h=").append(hosts.joinToString(","))
            if (hotspot != null) {
                append(";s=").append(enc(hotspot.ssid))
                append(";w=").append(enc(hotspot.pass))
                append(";t=").append(hotspot.security)
            }
        }
    }

    private val IPV4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")

    /** 读二维码；不是备忘的传输码、或内容不完整，返回 null */
    fun parseInvite(text: String): Invite? = runCatching {
        val parts = text.trim().split(';')
        if (parts.first() != INVITE_PREFIX) return null
        val m = parts.drop(1).associate { it.substringBefore('=') to it.substringAfter('=', "") }
        val token = Base64.getUrlDecoder().decode(m.getValue("k"))
        require(token.size == TOKEN_BYTES)
        val port = m.getValue("p").toInt()
        require(port in 1..65535)
        val hosts = m.getValue("h").split(',').filter { h ->
            IPV4.matchEntire(h)?.groupValues?.drop(1)?.all { it.toInt() in 0..255 } == true
        }
        require(hosts.isNotEmpty())
        val ssid = m["s"]?.let(::dec).orEmpty()
        val hotspot = if (ssid.isEmpty()) null else Hotspot(ssid, m["w"]?.let(::dec).orEmpty(), m["t"]?.toIntOrNull() ?: 1)
        Invite(token, port, hosts, hotspot)
    }.getOrNull()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun dec(s: String) = URLDecoder.decode(s, "UTF-8")

    // ============================== 握手 ==============================

    /**
     * 发送方（开端口的一方）一侧的握手，返回对方的设备名。
     * 对方没有正确的口令时这里会抛异常（解密失败）：调用方关掉这个连接、继续等下一个。
     */
    fun handshakeAsSender(ch: Channel, token: ByteArray, device: String): String {
        val mine = newKeyPair()
        val receiverPub = ch.readRaw()
        ch.sendRaw(mine.public.encoded)
        ch.secure(deriveKey(mine, receiverPub, mine.public.encoded, receiverPub, token), sender = true)
        val hello = ch.readText()
        require(type(hello) == "hello") { "对方发来的数据不对" }
        ch.sendText(helloJson(device))
        return str(hello, "device")
    }

    /** 接收方（扫码连过去的一方）一侧的握手，返回发送方的设备名 */
    fun handshakeAsReceiver(ch: Channel, token: ByteArray, device: String): String {
        val mine = newKeyPair()
        ch.sendRaw(mine.public.encoded)
        val senderPub = ch.readRaw()
        ch.secure(deriveKey(mine, senderPub, senderPub, mine.public.encoded, token), sender = false)
        ch.sendText(helloJson(device))
        val hello = ch.readText()
        require(type(hello) == "hello") { "对方发来的数据不对" }
        return str(hello, "device")
    }

    private fun helloJson(device: String) = """{"type":"hello","device":${quote(device)}}"""

    // ============================== 帧 ==============================

    /** 带长度前缀的帧；[secure] 之后每帧都加密 */
    class Channel(val socket: Socket) {
        private val input = DataInputStream(socket.getInputStream().buffered())
        private val output = DataOutputStream(socket.getOutputStream().buffered())
        private var key: SecretKeySpec? = null
        private var sendDir: Byte = 0
        private var recvDir: Byte = 0
        private var sendSeq = 0L
        private var recvSeq = 0L

        /** 开始加密。两个方向用不同的随机数前缀，同一把密钥下随机数永不重复 */
        fun secure(key: ByteArray, sender: Boolean) {
            this.key = SecretKeySpec(key, "AES")
            sendDir = if (sender) DIR_FROM_SENDER else DIR_FROM_RECEIVER
            recvDir = if (sender) DIR_FROM_RECEIVER else DIR_FROM_SENDER
        }

        fun sendRaw(bytes: ByteArray) {
            output.writeInt(bytes.size)
            output.write(bytes)
            output.flush()
        }

        fun readRaw(): ByteArray {
            val n = input.readInt()
            require(n in 0..MAX_FRAME) { "对方发来的数据不对" }
            return ByteArray(n).also { input.readFully(it) }
        }

        fun send(bytes: ByteArray) {
            val k = key ?: return sendRaw(bytes)
            sendRaw(gcm(Cipher.ENCRYPT_MODE, k, nonce(sendDir, sendSeq++), bytes))
        }

        fun read(): ByteArray {
            val frame = readRaw()
            val k = key ?: return frame
            return gcm(Cipher.DECRYPT_MODE, k, nonce(recvDir, recvSeq++), frame)
        }

        fun sendText(s: String) = send(s.toByteArray(Charsets.UTF_8))
        fun readText(): String = String(read(), Charsets.UTF_8)

        fun close() = runCatching { socket.close() }

        private fun nonce(dir: Byte, seq: Long) = ByteArray(12).also {
            it[0] = dir
            for (i in 0 until 8) it[4 + i] = (seq ushr (56 - 8 * i)).toByte()
        }

        private fun gcm(mode: Int, k: SecretKeySpec, iv: ByteArray, data: ByteArray): ByteArray =
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(mode, k, GCMParameterSpec(128, iv))
                doFinal(data)
            }

        private companion object {
            const val DIR_FROM_SENDER: Byte = 1
            const val DIR_FROM_RECEIVER: Byte = 2
        }
    }

    // ---------- 消息（JSON 文本，字段少，用正则读） ----------

    data class Manifest(val notes: Int, val images: Int, val bytes: Long, val device: String) {
        fun toJson() = """{"type":"manifest","notes":$notes,"images":$images,"bytes":$bytes,"device":${quote(device)}}"""
    }

    data class Outcome(val ok: Boolean, val added: Int, val updated: Int, val skipped: Int, val failedImages: Int, val message: String) {
        fun toJson() =
            """{"type":"result","ok":$ok,"added":$added,"updated":$updated,"skipped":$skipped,"failedImages":$failedImages,"message":${quote(message)}}"""
    }

    fun parseManifest(s: String): Manifest? = runCatching {
        Manifest(int(s, "notes"), int(s, "images"), long(s, "bytes"), str(s, "device"))
    }.getOrNull()

    fun parseOutcome(s: String): Outcome? = runCatching {
        Outcome(
            Regex("\"ok\"\\s*:\\s*true").containsMatchIn(s),
            int(s, "added"), int(s, "updated"), int(s, "skipped"), int(s, "failedImages"), str(s, "message"),
        )
    }.getOrNull()

    fun type(s: String): String = Regex("\"type\"\\s*:\\s*\"(\\w+)\"").find(s)?.groupValues?.get(1).orEmpty()

    private fun int(s: String, k: String) = long(s, k).toInt()
    private fun long(s: String, k: String) = Regex("\"$k\"\\s*:\\s*(-?\\d+)").find(s)!!.groupValues[1].toLong()
    private fun str(s: String, k: String): String {
        val raw = Regex("\"$k\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(s)!!.groupValues[1]
        return raw.replace("\\\"", "\"").replace("\\\\", "\\")
    }

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\""
}
