package com.beiwang.memo.data

import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min

/**
 * 保险箱里的大文件（视频、语音）：分块 AES-256-GCM。边读边写，多大的文件都不用整个读进内存；
 * 能随机读（播放时拖进度条只解那一块）。纯算法，不碰安卓系统，有单元测试。
 *
 * 文件格式：「BWS1」(4) | 块大小 (4, 大端) | 盐 (16) | 块 0 | 块 1 | …
 * - 每块 = AES-GCM(文件密钥, 明文) = 密文 + 16 字节标签；明文每块满 [CHUNK]，最后一块可以更短（可以是 0 字节）。
 * - 文件密钥 = HMAC-SHA256(内容密钥, "beiwang-stream-v1" || 盐)：每个文件一把，所以块序号当随机数不会重复。
 * - 随机数（12 字节）= 7 个 0 | 块序号 (4, 大端) | 是否最后一块 (1)：调换、删掉、截断任何一块都解不开。
 *
 * 小的图片仍然用 [VaultCrypto.seal]（整个文件一次加密），和这里不是一种格式。
 */
object StreamCrypto {

    const val CHUNK = 64 * 1024
    private val MAGIC = "BWS1".toByteArray(Charsets.US_ASCII)
    private const val HEADER = 4 + 4 + 16
    private const val TAG = 16

    /** 加密：[input] 读到头；返回明文字节数。不关闭两个流 */
    fun seal(key: ByteArray, input: InputStream, out: OutputStream): Long {
        val salt = VaultCrypto.randomBytes(16)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val fk = fileKey(key, salt)
        try {
            val sk = SecretKeySpec(fk, "AES")
            out.write(MAGIC)
            out.write(int(CHUNK))
            out.write(salt)
            var cur = ByteArray(CHUNK)
            var next = ByteArray(CHUNK)
            var curLen = readFully(input, cur)
            var index = 0
            var total = 0L
            while (true) {
                // 先读下一块，才知道这一块是不是最后一块（正好读满一块时文件可能也到头了）
                val nextLen = if (curLen == CHUNK) readFully(input, next) else 0
                val last = nextLen == 0
                cipher.init(Cipher.ENCRYPT_MODE, sk, GCMParameterSpec(128, nonce(index, last)))
                out.write(cipher.doFinal(cur, 0, curLen))
                total += curLen
                if (last) return total
                val t = cur; cur = next; next = t
                curLen = nextLen
                index++
            }
        } finally {
            fk.fill(0)
        }
    }

    /** 解密：写出明文，返回字节数。密钥不对、数据被改过或被截断都会抛异常（这时 [out] 里可能已经写了一部分） */
    fun open(key: ByteArray, input: InputStream, out: OutputStream): Long {
        val (cs, salt) = readHeader(input)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val fk = fileKey(key, salt)
        try {
            val sk = SecretKeySpec(fk, "AES")
            var total = 0L
            forEachChunk(input, cs) { index, buf, len, last ->
                cipher.init(Cipher.DECRYPT_MODE, sk, GCMParameterSpec(128, nonce(index, last)))
                val plain = cipher.doFinal(buf, 0, len)
                out.write(plain)
                total += plain.size
            }
            return total
        } finally {
            fk.fill(0)
        }
    }

    /** 换一把内容密钥重新加密（别的设备传来的保险箱内容并进本机）：一块一块解开再加上，明文不落盘 */
    fun reseal(from: ByteArray, to: ByteArray, input: InputStream, out: OutputStream) {
        val (cs, salt) = readHeader(input)
        val newSalt = VaultCrypto.randomBytes(16)
        val dec = Cipher.getInstance("AES/GCM/NoPadding")
        val enc = Cipher.getInstance("AES/GCM/NoPadding")
        val fk1 = fileKey(from, salt)
        val fk2 = fileKey(to, newSalt)
        try {
            val k1 = SecretKeySpec(fk1, "AES")
            val k2 = SecretKeySpec(fk2, "AES")
            out.write(MAGIC)
            out.write(int(cs))
            out.write(newSalt)
            forEachChunk(input, cs) { index, buf, len, last ->
                dec.init(Cipher.DECRYPT_MODE, k1, GCMParameterSpec(128, nonce(index, last)))
                val plain = dec.doFinal(buf, 0, len)
                enc.init(Cipher.ENCRYPT_MODE, k2, GCMParameterSpec(128, nonce(index, last)))
                out.write(enc.doFinal(plain))
                plain.fill(0)
            }
        } finally {
            fk1.fill(0)
            fk2.fill(0)
        }
    }

    /** 文件开头是不是这种格式 */
    fun isSealed(file: File): Boolean = runCatching {
        file.inputStream().use { i -> ByteArray(4).also { readFully(i, it) }.contentEquals(MAGIC) }
    }.getOrDefault(false)

    // ---------- 文件版：先写临时文件再改名，写到一半断电不会留下半个文件 ----------

    fun sealFile(key: ByteArray, src: File, dst: File): Long = writeAtomically(dst) { out -> src.inputStream().buffered().use { seal(key, it, out) } }

    fun openFile(key: ByteArray, src: File, dst: File): Long = writeAtomically(dst) { out -> src.inputStream().buffered().use { open(key, it, out) } }

    fun resealFile(from: ByteArray, to: ByteArray, file: File) {
        writeAtomically(file) { out -> file.inputStream().buffered().use { reseal(from, to, it, out) } }
    }

    fun <T> writeAtomically(dst: File, write: (OutputStream) -> T): T {
        val tmp = File(dst.path + ".tmp")
        try {
            val r = FileOutputStream(tmp).buffered().use { write(it) }
            if (!tmp.renameTo(dst)) {
                tmp.copyTo(dst, overwrite = true)
                tmp.delete()
            }
            return r
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        }
    }

    /**
     * 随机读（给播放器用）：只解要读的那一块，最近一块缓存着。线程安全。
     * 用完要 [close]：会把文件密钥清零。
     */
    class Reader(key: ByteArray, file: File) : Closeable {
        private val raf = RandomAccessFile(file, "r")
        private val cs: Int
        private val fk: ByteArray
        private val sk: SecretKeySpec
        private val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        private val count: Long
        private val lastEnc: Int
        /** 明文总长 */
        val size: Long

        init {
            try {
                val head = ByteArray(HEADER)
                raf.readFully(head)
                require(head.copyOfRange(0, 4).contentEquals(MAGIC)) { "不是加密的媒体文件" }
                cs = readInt(head, 4)
                require(cs in 1..(16 shl 20)) { "块大小不对" }
                fk = fileKey(key, head.copyOfRange(8, HEADER))
                sk = SecretKeySpec(fk, "AES")
                val body = raf.length() - HEADER
                require(body >= TAG) { "文件不完整" }
                val stride = cs.toLong() + TAG
                count = (body + stride - 1) / stride
                lastEnc = (body - (count - 1) * stride).toInt()
                require(lastEnc >= TAG) { "文件不完整" }
                size = (count - 1) * cs + (lastEnc - TAG)
            } catch (e: Throwable) {
                raf.close()
                throw e
            }
        }

        private var cachedIndex = -1L
        private var cached = ByteArray(0)

        private fun chunk(i: Long): ByteArray {
            if (i == cachedIndex) return cached
            val last = i == count - 1
            val buf = ByteArray(if (last) lastEnc else cs + TAG)
            raf.seek(HEADER + i * (cs.toLong() + TAG))
            raf.readFully(buf)
            cipher.init(Cipher.DECRYPT_MODE, sk, GCMParameterSpec(128, nonce(i.toInt(), last)))
            cached = cipher.doFinal(buf)
            cachedIndex = i
            return cached
        }

        /** 从明文的 [position] 读最多 [length] 字节；读到头返回 -1 */
        @Synchronized
        fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            if (position < 0 || position >= size) return -1
            var pos = position
            var off = offset
            var left = min(length.toLong(), size - position).toInt()
            var done = 0
            while (left > 0) {
                val c = chunk(pos / cs)
                val within = (pos % cs).toInt()
                val n = min(left, c.size - within)
                if (n <= 0) break
                System.arraycopy(c, within, buffer, off, n)
                pos += n
                off += n
                left -= n
                done += n
            }
            return if (done == 0) -1 else done
        }

        @Synchronized
        override fun close() {
            raf.close()
            fk.fill(0)
            cached.fill(0)
            cachedIndex = -1
        }
    }

    // ---------- 内部 ----------

    private fun readHeader(input: InputStream): Pair<Int, ByteArray> {
        val head = ByteArray(HEADER)
        if (readFully(input, head) != HEADER) throw EOFException("文件不完整")
        require(head.copyOfRange(0, 4).contentEquals(MAGIC)) { "不是加密的媒体文件" }
        val cs = readInt(head, 4)
        require(cs in 1..(16 shl 20)) { "块大小不对" }
        return cs to head.copyOfRange(8, HEADER)
    }

    /** 逐块读密文，告诉调用方每块的序号和是不是最后一块 */
    private inline fun forEachChunk(input: InputStream, cs: Int, block: (index: Int, buf: ByteArray, len: Int, last: Boolean) -> Unit) {
        val enc = cs + TAG
        var cur = ByteArray(enc)
        var next = ByteArray(enc)
        var curLen = readFully(input, cur)
        var index = 0
        while (true) {
            if (curLen < TAG) throw EOFException("文件不完整")
            val nextLen = if (curLen == enc) readFully(input, next) else 0
            val last = nextLen == 0
            block(index, cur, curLen, last)
            if (last) return
            val t = cur; cur = next; next = t
            curLen = nextLen
            index++
        }
    }

    private fun fileKey(key: ByteArray, salt: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        mac.update("beiwang-stream-v1".toByteArray(Charsets.US_ASCII))
        return mac.doFinal(salt)
    }

    private fun nonce(index: Int, last: Boolean): ByteArray = ByteArray(12).also {
        it[7] = (index ushr 24).toByte()
        it[8] = (index ushr 16).toByte()
        it[9] = (index ushr 8).toByte()
        it[10] = index.toByte()
        it[11] = if (last) 1 else 0
    }

    private fun int(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private fun readInt(b: ByteArray, at: Int) =
        ((b[at].toInt() and 0xff) shl 24) or ((b[at + 1].toInt() and 0xff) shl 16) or
            ((b[at + 2].toInt() and 0xff) shl 8) or (b[at + 3].toInt() and 0xff)

    /** 读满 [buf] 或读到头；返回读到的字节数 */
    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var n = 0
        while (n < buf.size) {
            val r = input.read(buf, n, buf.size - n)
            if (r < 0) break
            n += r
        }
        return n
    }
}
