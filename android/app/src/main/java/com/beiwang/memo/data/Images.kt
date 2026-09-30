package com.beiwang.memo.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.util.LruCache
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/** 加密 / 解密函数（保险箱提供）；null 表示普通明文图片 */
typealias Crypt = (ByteArray) -> ByteArray

/**
 * 图片文件：每张图存两份 —— 原图（长边 ≤ 2048）和缩略图（长边 ≤ 480）。
 * 普通图片是 `id.jpg` / `id_t.jpg`；保险箱里的图片是加密后的 `id.vault` / `id_t.vault`。
 * 列表和编辑页只解码缩略图；看大图时才解码原图。
 * 所有 import/delete/load 都是阻塞调用，只能在 IO 线程上用。
 */
class Images(context: Context) {

    private val dir = File(context.filesDir, "img").apply { mkdirs() }

    fun full(id: String) = File(dir, "$id.jpg")
    fun thumb(id: String) = File(dir, "${id}_t.jpg")
    fun sealedFull(id: String) = File(dir, "$id.vault")
    fun sealedThumb(id: String) = File(dir, "${id}_t.vault")

    /** 从相册/文件导入；[seal] 非空时直接加密存（保险箱里加图）。读不了返回 null */
    fun importUri(context: Context, uri: Uri, seal: Crypt? = null): Media? = runCatching {
        val bmp = decode(context, uri, FULL) ?: return null
        store(bmp, seal)
    }.getOrNull()

    /** 从备份或旧数据导入（JPEG/PNG/WebP 字节） */
    fun importBytes(bytes: ByteArray): Media? = runCatching {
        val bmp = decode(bytes, FULL) ?: return null
        store(bmp, null)
    }.getOrNull()

    /** 导入别处传来的加密图片：原样存，不解密 */
    fun importSealed(full: ByteArray, thumb: ByteArray?, w: Int, h: Int): Media? = runCatching {
        val id = Ids.next()
        writeBytes(sealedFull(id), full)
        if (thumb != null) writeBytes(sealedThumb(id), thumb)
        Media(id, w, h)
    }.getOrNull()

    /** 视频的缩略图（抽出来的一帧）：和图片的缩略图存在同一个地方，[seal] 非空时加密存 */
    fun writeThumb(id: String, frame: Bitmap, seal: Crypt?) {
        val small = opaque(scaleDown(frame, THUMB))
        if (seal == null) writeJpeg(small, thumb(id), 82) else writeBytes(sealedThumb(id), seal(jpegBytes(small, 82)))
        forget(id)
    }

    fun delete(id: String) {
        full(id).delete()
        thumb(id).delete()
        sealedFull(id).delete()
        sealedThumb(id).delete()
        forget(id)
    }

    private fun forget(id: String) {
        for (t in listOf(true, false)) {
            cache.remove(key(id, t))
            sealedCache.remove(key(id, t))
        }
    }

    /** 明文图片 → 加密（移入保险箱） */
    fun sealInPlace(id: String, seal: Crypt) {
        for ((plain, sealed) in listOf(full(id) to sealedFull(id), thumb(id) to sealedThumb(id))) {
            if (!plain.exists()) continue
            writeBytes(sealed, seal(plain.readBytes()))
            plain.delete()
        }
        forget(id)
    }

    /** 加密图片 → 明文（移出保险箱） */
    fun openInPlace(id: String, open: Crypt) {
        for ((plain, sealed) in listOf(full(id) to sealedFull(id), thumb(id) to sealedThumb(id))) {
            if (!sealed.exists()) continue
            writeBytes(plain, open(sealed.readBytes()))
            sealed.delete()
        }
        forget(id)
    }

    /** 换一把密钥重新加密（别的设备传来的保险箱内容并进本机保险箱） */
    fun resealInPlace(id: String, open: Crypt, seal: Crypt) {
        for (f in listOf(sealedFull(id), sealedThumb(id))) {
            if (f.exists()) writeBytes(f, seal(open(f.readBytes())))
        }
        forget(id)
    }

    /** 导出用：加密图片的原始字节（不解密） */
    fun sealedBytes(id: String, thumb: Boolean): ByteArray? =
        (if (thumb) sealedThumb(id) else sealedFull(id)).takeIf { it.exists() }?.readBytes()

    /** 删掉没有任何笔记（含回收站）引用的图片文件：撤销删图、导入失败等情况留下的孤儿 */
    fun collectGarbage(referenced: Set<String>) {
        dir.listFiles()?.forEach { f ->
            val id = f.name.removeSuffix(".tmp").removeSuffix(".jpg").removeSuffix(".vault").removeSuffix("_t")
            if (id !in referenced) f.delete()
        }
    }

    // ---------- 显示用的解码 + 内存缓存 ----------

    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 6).coerceAtMost(96L shl 20).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    /** 保险箱图片单独一个缓存，上锁时整个清空 */
    private val sealedCache = object : LruCache<String, Bitmap>(24 shl 20) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    fun clearSealedCache() = sealedCache.evictAll()

    private fun key(id: String, thumb: Boolean) = (if (thumb) "t:" else "f:") + id

    fun cached(id: String, thumb: Boolean, sealed: Boolean = false): Bitmap? =
        (if (sealed) sealedCache else cache).get(key(id, thumb))

    /** [open] 非空表示这是保险箱里的加密图片 */
    fun load(id: String, thumb: Boolean, open: Crypt? = null): Bitmap? {
        if (open != null) return loadSealed(id, thumb, open)
        val k = key(id, thumb)
        cache.get(k)?.let { return it }
        var file = if (thumb) thumb(id) else full(id)
        if (thumb && !file.exists()) {
            // 缩略图丢了：用原图补一张
            val src = BitmapFactory.decodeFile(full(id).path) ?: return null
            writeJpeg(scaleDown(src, THUMB), file, 82)
        }
        if (!file.exists()) file = full(id)
        val bmp = BitmapFactory.decodeFile(file.path) ?: return null
        cache.put(k, bmp)
        return bmp
    }

    private fun loadSealed(id: String, thumb: Boolean, open: Crypt): Bitmap? {
        val k = key(id, thumb)
        sealedCache.get(k)?.let { return it }
        val file = if (thumb && sealedThumb(id).exists()) sealedThumb(id) else sealedFull(id)
        if (!file.exists()) return null
        val bytes = runCatching { open(file.readBytes()) }.getOrNull() ?: return null
        var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        if (thumb && file == sealedFull(id)) bmp = scaleDown(bmp, THUMB)
        sealedCache.put(k, bmp)
        return bmp
    }

    // ---------- 内部 ----------

    private fun store(src: Bitmap, seal: Crypt?): Media {
        val id = Ids.next()
        val big = opaque(scaleDown(src, FULL))
        val small = scaleDown(big, THUMB)
        if (seal == null) {
            writeJpeg(big, full(id), 86)
            writeJpeg(small, thumb(id), 82)
        } else {
            writeBytes(sealedFull(id), seal(jpegBytes(big, 86)))
            writeBytes(sealedThumb(id), seal(jpegBytes(small, 82)))
        }
        return Media(id, big.width, big.height)
    }

    companion object {
        const val FULL = 2048
        const val THUMB = 480

        /** 按长边上限解码，自动按 EXIF 方向摆正 */
        fun decode(context: Context, uri: Uri, maxSide: Int): Bitmap? =
            if (Build.VERSION.SDK_INT >= 28) {
                decodeP(ImageDecoder.createSource(context.contentResolver, uri), maxSide)
            } else {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
                decodeLegacy(bytes, maxSide)
            }

        fun decode(bytes: ByteArray, maxSide: Int): Bitmap? =
            if (Build.VERSION.SDK_INT >= 28) decodeP(ImageDecoder.createSource(ByteBuffer.wrap(bytes)), maxSide)
            else decodeLegacy(bytes, maxSide)

        private fun decodeP(source: ImageDecoder.Source, maxSide: Int): Bitmap =
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val w = info.size.width
                val h = info.size.height
                val s = maxSide.toFloat() / max(w, h)
                if (s < 1f) decoder.setTargetSize((w * s).roundToInt().coerceAtLeast(1), (h * s).roundToInt().coerceAtLeast(1))
            }

        private fun decodeLegacy(bytes: ByteArray, maxSide: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0) return null
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            val rotation = runCatching {
                when (ExifInterface(bytes.inputStream()).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            }.getOrDefault(0f)
            if (rotation == 0f) return bmp
            return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation) }, true)
        }

        fun scaleDown(src: Bitmap, maxSide: Int): Bitmap {
            val s = maxSide.toFloat() / max(src.width, src.height)
            if (s >= 1f) return src
            return Bitmap.createScaledBitmap(
                src, (src.width * s).roundToInt().coerceAtLeast(1), (src.height * s).roundToInt().coerceAtLeast(1), true
            )
        }

        /** JPEG 没有透明：透明底先垫白，否则会变成黑底 */
        private fun opaque(src: Bitmap): Bitmap {
            if (!src.hasAlpha()) return src
            val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            Canvas(out).apply { drawColor(Color.WHITE); drawBitmap(src, 0f, 0f, null) }
            return out
        }

        fun writeJpeg(bmp: Bitmap, file: File, quality: Int) = writeBytes(file, jpegBytes(bmp, quality))

        fun jpegBytes(bmp: Bitmap, quality: Int): ByteArray =
            ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()

        /** 先写临时文件再改名：写到一半断电也不会留下半张图 */
        fun writeBytes(file: File, bytes: ByteArray) {
            val tmp = File(file.path + ".tmp")
            FileOutputStream(tmp).use { it.write(bytes) }
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }
    }
}
