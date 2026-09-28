package com.beiwang.memo.data

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 两台手机之间的局域网传输协议（纯 Java 网络 + 加密，不含界面，可单元测试）。
 *
 * 1. 连接后双方交换临时 EC 公钥（P-256），ECDH 算出共享密钥；
 * 2. 由共享密钥和双方公钥算出 6 位数字，两台手机同时显示，用户确认一样才继续 ——
 *    同一 Wi-Fi 里有人冒充中间人时，两边的数字一定不同；
 * 3. 之后每一帧都用 AES-256-GCM 加密：先是清单（条数、图片数、字节数），再是备份文件分块，最后是结果。
 */
object TransferProto {

    const val SERVICE_TYPE = "_beiwang._tcp."
    const val CHUNK = 64 * 1024
    private const val MAX_FRAME = 1 shl 20

    fun newKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    /**
     * 算会话密钥和确认码。[senderPub]/[receiverPub] 顺序两边必须一致（发送方在前）。
     * 返回（32 字节密钥，"482 913" 形式的确认码）
     */
    fun derive(mine: KeyPair, peerPub: ByteArray, senderPub: ByteArray, receiverPub: ByteArray): Pair<ByteArray, String> {
        val peer = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(peerPub))
        val shared = KeyAgreement.getInstance("ECDH").run {
            init(mine.private)
            doPhase(peer, true)
            generateSecret()
        }
        fun hmac(label: String): ByteArray = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(shared, "HmacSHA256"))
            update(label.toByteArray())
            update(senderPub)
            update(receiverPub)
            doFinal()
        }
        val key = hmac("beiwang-transfer-key")
        val c = hmac("beiwang-transfer-code")
        val n = ((c[0].toLong() and 0xff) shl 24 or ((c[1].toLong() and 0xff) shl 16) or
            ((c[2].toLong() and 0xff) shl 8) or (c[3].toLong() and 0xff)) % 1_000_000
        val code = "%06d".format(n)
        return key to code.substring(0, 3) + " " + code.substring(3)
    }

    /** 带长度前缀的帧；设了 key 之后每帧都加密 */
    class Channel(private val socket: Socket) {
        private val input = DataInputStream(socket.getInputStream().buffered())
        private val output = DataOutputStream(socket.getOutputStream().buffered())
        var key: ByteArray? = null

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

        fun send(bytes: ByteArray) = sendRaw(key?.let { VaultCrypto.seal(it, bytes) } ?: bytes)
        fun read(): ByteArray = readRaw().let { f -> key?.let { VaultCrypto.open(it, f) } ?: f }

        fun sendText(s: String) = send(s.toByteArray(Charsets.UTF_8))
        fun readText(): String = String(read(), Charsets.UTF_8)

        fun close() = runCatching { socket.close() }
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
