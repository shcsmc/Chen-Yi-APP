package com.beiwang.memo.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** 流式加密（保险箱提供）：从输入读明文、往输出写密文，返回明文字节数 */
typealias StreamSeal = (InputStream, OutputStream) -> Long

/**
 * 视频、语音文件（`files/media/`）：
 * - 普通的是 `id.clip`（原文件原样，不转码、不压缩）；
 * - 保险箱里的是 `id.vclip`（[StreamCrypto] 分块加密，能边解边播）。
 * 视频的缩略图（抽一帧）放在 [Images] 那边（`img/id_t.jpg` / `id_t.vault`），列表和编辑页取缩略图的路子和图片一样。
 *
 * 录音先录到 `cache/rec/`：普通笔记录完挪过来；保险箱里的录完马上加密、删掉临时文件
 * （启动时和上锁时也会清一遍这个目录，不留明文）。
 * 所有方法都是阻塞调用，只能在 IO 线程上用。
 */
class Clips(context: Context) {

    private val dir = File(context.filesDir, "media").apply { mkdirs() }
    private val recDir = File(context.cacheDir, "rec")

    fun plain(id: String) = File(dir, "$id.clip")
    fun sealed(id: String) = File(dir, "$id.vclip")

    /** 录音用的临时文件 */
    fun recordingFile(id: String): File = File(recDir.apply { mkdirs() }, "$id.m4a")

    fun clearTemp() {
        recDir.listFiles()?.forEach { it.delete() }
    }

    fun delete(id: String) {
        plain(id).delete()
        sealed(id).delete()
    }

    /** 删掉没有任何笔记（含回收站）引用的文件 */
    fun collectGarbage(referenced: Set<String>) {
        dir.listFiles()?.forEach { f ->
            val id = f.name.removeSuffix(".tmp").removeSuffix(".clip").removeSuffix(".vclip")
            if (id !in referenced || f.name.endsWith(".tmp")) f.delete()
        }
    }

    // ---------------- 导入视频 ----------------

    class VideoInfo(val w: Int, val h: Int, val dur: Long, val mime: String, val frame: Bitmap?)

    /** 读视频信息并抽一帧：宽高按旋转摆正。读不了返回 null */
    fun probeVideo(context: Context, uri: Uri): VideoInfo? = runCatching {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val mime = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                ?: context.contentResolver.getType(uri) ?: "video/mp4"
            val (vw, vh) = if (rot == 90 || rot == 270) h to w else w to h
            // 第一帧常常是黑的：取 1 秒处（短视频取中间）
            val at = (if (dur in 1..1999) dur / 2 else 1000L) * 1000
            var frame = runCatching { r.getFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) }.getOrNull()
                ?: runCatching { r.frameAtTime }.getOrNull()
            // 多数系统抽出来的帧已经摆正；没摆正的（横竖和视频不一致）自己转
            if (frame != null && rot != 0 && vw > 0 && vh > 0 && (frame.width > frame.height) != (vw > vh)) {
                frame = Bitmap.createBitmap(frame, 0, 0, frame.width, frame.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
            }
            if (vw <= 0 || vh <= 0) {
                if (frame == null) return null
                VideoInfo(frame.width, frame.height, dur, mime, frame)
            } else {
                VideoInfo(vw, vh, dur, mime, frame)
            }
        } finally {
            runCatching { r.release() }
        }
    }.getOrNull()

    /**
     * 把视频复制进来（[seal] 非空时边读边加密），抽一帧做缩略图。
     * [progress] 报告（已复制字节，总字节；总数不知道时为 0）。失败返回 null，不留半个文件。
     */
    fun importVideo(
        context: Context,
        uri: Uri,
        images: Images,
        seal: StreamSeal?,
        sealThumb: Crypt?,
        progress: (Long, Long) -> Unit,
    ): Media? {
        val info = probeVideo(context, uri) ?: return null
        val id = Ids.next()
        val total = sizeOf(context, uri)
        val target = if (seal == null) plain(id) else sealed(id)
        return try {
            val size = context.contentResolver.openInputStream(uri)?.use { raw ->
                val input = CountingInput(raw) { progress(it, total) }
                StreamCrypto.writeAtomically(target) { out ->
                    if (seal == null) input.copyTo(out, 256 * 1024) else seal(input, out)
                }
            } ?: return null
            info.frame?.let { images.writeThumb(id, it, sealThumb) }
            Media(id, info.w, info.h, MediaKind.Video, dur = info.dur, size = size, mime = info.mime)
        } catch (e: Exception) {
            delete(id)
            images.delete(id)
            null
        }
    }

    /**
     * 录好的音频（[recordingFile]）收进来：普通笔记直接挪过来；保险箱里的加密后删掉临时文件。
     * [dur] 用录音时自己计的时长（读文件时长在个别机型上不准）。失败返回 null。
     */
    fun adoptRecording(id: String, dur: Long, sealFile: ((File, File) -> Long)?): Media? {
        val src = recordingFile(id)
        if (!src.exists() || src.length() == 0L) {
            src.delete()
            return null
        }
        val size = src.length()
        return try {
            if (sealFile == null) {
                if (!src.renameTo(plain(id))) {
                    src.copyTo(plain(id), overwrite = true)
                }
            } else {
                sealFile(src, sealed(id))
            }
            Media(id, 0, 0, MediaKind.Audio, dur = dur, size = size, mime = "audio/mp4")
        } catch (e: Exception) {
            delete(id)
            null
        } finally {
            src.delete()
        }
    }

    // ---------------- 进出保险箱 ----------------

    /** 明文 → 加密（移入保险箱） */
    fun sealInPlace(id: String, sealFile: (File, File) -> Long) {
        val p = plain(id)
        if (!p.exists()) return
        sealFile(p, sealed(id))
        p.delete()
    }

    /** 加密 → 明文（移出保险箱） */
    fun openInPlace(id: String, openFile: (File, File) -> Long) {
        val s = sealed(id)
        if (!s.exists()) return
        openFile(s, plain(id))
        s.delete()
    }

    /** 换一把密钥（别的设备传来的保险箱内容并进本机保险箱） */
    fun resealInPlace(id: String, reseal: (File) -> Unit) {
        val s = sealed(id)
        if (s.exists()) reseal(s)
    }

    companion object {
        /** 文件大小（字节）；拿不到返回 0 */
        fun sizeOf(context: Context, uri: Uri): Long = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
            } ?: context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length.takeIf { l -> l > 0 } } ?: 0L
        }.getOrDefault(0L)

        /** 导出时的扩展名 */
        fun extension(m: Media): String = when (m.mime.lowercase()) {
            "video/mp4", "video/mpeg4" -> "mp4"
            "video/3gpp" -> "3gp"
            "video/webm" -> "webm"
            "video/quicktime" -> "mov"
            "video/x-matroska" -> "mkv"
            "audio/mp4", "audio/m4a", "audio/x-m4a" -> "m4a"
            "audio/aac" -> "aac"
            "audio/mpeg" -> "mp3"
            "audio/amr" -> "amr"
            "audio/ogg" -> "ogg"
            else -> if (m.kind == MediaKind.Audio) "m4a" else "mp4"
        }
    }

    /** 数着读了多少字节（报进度用），每 256KB 报一次 */
    private class CountingInput(private val src: InputStream, private val report: (Long) -> Unit) : InputStream() {
        private var count = 0L
        private var lastReport = 0L
        override fun read(): Int = src.read().also { if (it >= 0) bump(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = src.read(b, off, len).also { if (it > 0) bump(it.toLong()) }
        private fun bump(n: Long) {
            count += n
            if (count - lastReport >= 256 * 1024) {
                lastReport = count
                report(count)
            }
        }
        override fun close() = src.close()
    }
}
