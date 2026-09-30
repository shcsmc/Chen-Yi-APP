package com.beiwang.memo.ui.editor

import android.content.Context
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import android.view.Surface
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.beiwang.memo.data.Ids
import com.beiwang.memo.data.Media
import com.beiwang.memo.data.Store
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * 给播放器接数据：普通文件直接给路径；保险箱里的加密文件边读边解（只解要读的那一块，明文不落盘）。
 * 返回一个关闭函数（加密的要关掉读取器，文件密钥清零）。
 */
private fun MediaPlayer.attach(store: Store, m: Media, vault: Boolean): () -> Unit {
    if (!vault) {
        setDataSource(store.clips.plain(m.id).path)
        return {}
    }
    val reader = store.vault.reader(store.clips.sealed(m.id))
    setDataSource(object : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int =
            if (size == 0) 0 else reader.readAt(position, buffer, offset, size)
        override fun getSize(): Long = reader.size
        override fun close() = reader.close()
    })
    return { runCatching { reader.close() } }
}

/**
 * 编辑页里的语音条播放：同一时间只放一条，点另一条会先停掉前一条。
 * 在主线程用（MediaPlayer 的回调回到主线程）。
 *
 * 进度有两个：[position] 每 0.1 秒从播放器读一次（给时间文字用）；[livePosition] 来自一块自己走的表
 * （[PlaybackClock]，每 0.1 秒和播放器对一次），语音条的波形每一帧都用它画，屏幕是 120Hz 就一秒走 120 步，不会一跳一跳。
 */
@Stable
class VoicePlayer(private val store: Store) {
    /** 正在放（或暂停在）哪一条 */
    var current by mutableStateOf<String?>(null)
        private set
    var playing by mutableStateOf(false)
        private set
    /** 播放进度（毫秒），约 0.1 秒更新一次 */
    var position by mutableLongStateOf(0L)
        private set
    /** 这一条的总长（毫秒） */
    var duration by mutableLongStateOf(0L)
        private set

    private var player: MediaPlayer? = null
    private var closeSource: () -> Unit = {}
    private var ticker: Job? = null
    private val clock = PlaybackClock { SystemClock.uptimeMillis() }
    // 拖动后播放器还在跳的这一小会儿，有的机型报的还是跳之前的位置，不拿来对表（跳完或最多 1 秒）
    private var seekUntil = 0L
    // 准备好之前不能跳
    private var prepared = false

    /** 此刻的播放位置（毫秒）：播放中按表往前推，暂停时就是停下的位置 */
    fun livePosition(): Long = if (playing) clock.position(duration) else position

    fun toggle(m: Media, vault: Boolean, onError: (String) -> Unit) {
        val p = player
        if (current == m.id && p != null) {
            if (playing) {
                position = livePosition()
                p.pause()
                playing = false
            } else {
                p.start()
                clock.hold(position)
                playing = true
                tick()
            }
            return
        }
        stop()
        val mp = MediaPlayer()
        try {
            closeSource = mp.attach(store, m, vault)
            mp.setOnPreparedListener {
                if (it.duration > 0) duration = it.duration.toLong()
                prepared = true
                it.start()
                clock.hold(0)
                playing = true
                tick()
            }
            mp.setOnCompletionListener { stop() }
            mp.setOnSeekCompleteListener { seekUntil = 0L }
            mp.setOnErrorListener { _, _, _ ->
                stop()
                onError("这段语音放不了")
                true
            }
            player = mp
            current = m.id
            position = 0
            duration = m.dur
            mp.prepareAsync()
        } catch (e: Exception) {
            runCatching { mp.release() }
            closeSource()
            closeSource = {}
            player = null
            current = null
            onError("这段语音放不了")
        }
    }

    /** 拖波形跳到某处（只对正在放/暂停着的这一条） */
    fun seek(ms: Long) {
        val p = player?.takeIf { prepared } ?: return
        val t = if (duration > 0) ms.coerceIn(0, duration) else ms.coerceAtLeast(0)
        runCatching { p.seekTo(t, MediaPlayer.SEEK_CLOSEST) }
        seekUntil = SystemClock.uptimeMillis() + 1_000
        position = t
        clock.hold(t)
    }

    fun stop() {
        ticker?.cancel()
        ticker = null
        player?.let { runCatching { it.stop() }; runCatching { it.release() } }
        player = null
        closeSource()
        closeSource = {}
        current = null
        playing = false
        position = 0
        seekUntil = 0L
        prepared = false
    }

    private fun tick() {
        ticker?.cancel()
        ticker = store.scope.launch {
            while (isActive && playing) {
                val p = runCatching { player?.currentPosition?.toLong() }.getOrNull()
                if (p != null && SystemClock.uptimeMillis() >= seekUntil) {
                    position = p
                    clock.sync(p, duration)
                }
                delay(100)
            }
        }
    }
}

/**
 * 看视频：一个 MediaPlayer，画面交给 [Surface]（取景层里的 TextureView 提供），
 * 播放/暂停/拖进度由悬浮层的按钮控制。
 */
@Stable
class VideoPlayback(private val store: Store, val media: Media, private val vault: Boolean) {
    var playing by mutableStateOf(false)
        private set
    var position by mutableLongStateOf(0L)
        private set
    var duration by mutableLongStateOf(media.dur)
        private set
    var ready by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private val player = MediaPlayer()
    private var closeSource: () -> Unit = {}
    private var ticker: Job? = null
    private var released = false

    init {
        try {
            closeSource = player.attach(store, media, vault)
            player.setOnPreparedListener {
                ready = true
                if (it.duration > 0) duration = it.duration.toLong()
                play()
            }
            player.setOnCompletionListener {
                playing = false
                position = duration
            }
            player.setOnErrorListener { _, _, _ ->
                error = "这个视频放不了"
                playing = false
                true
            }
            player.prepareAsync()
        } catch (e: Exception) {
            error = "这个视频放不了"
        }
    }

    fun setSurface(surface: Surface?) {
        if (!released) runCatching { player.setSurface(surface) }
    }

    fun play() {
        if (!ready || released) return
        if (position >= duration - 200) runCatching { player.seekTo(0) }
        runCatching { player.start() }
        playing = true
        tick()
    }

    fun pause() {
        if (!ready || released) return
        runCatching { player.pause() }
        playing = false
    }

    fun toggle() = if (playing) pause() else play()

    fun seek(ms: Long) {
        if (!ready || released) return
        val t = ms.coerceIn(0, duration)
        position = t
        runCatching {
            if (Build.VERSION.SDK_INT >= 26) player.seekTo(t, MediaPlayer.SEEK_CLOSEST) else player.seekTo(t.toInt())
        }
    }

    fun release() {
        if (released) return
        released = true
        ticker?.cancel()
        playing = false
        runCatching { player.release() }
        closeSource()
    }

    private fun tick() {
        ticker?.cancel()
        ticker = store.scope.launch {
            while (isActive && playing && !released) {
                position = runCatching { player.currentPosition.toLong() }.getOrNull() ?: position
                delay(100)
            }
        }
    }
}

/**
 * 录音：录到临时文件（AAC，单声道 64kbps，一分钟约 0.5MB），最长 30 分钟。
 * 在主线程开始/结束；音量和时长每 80 毫秒更新一次，给录音条画波形。
 */
@Stable
class VoiceRecording private constructor(
    val id: String,
    private val recorder: MediaRecorder,
    private val store: Store,
) {
    private val startedAt = System.currentTimeMillis()
    /** 最近的音量（0..1） */
    var level by mutableFloatStateOf(0f)
        private set
    var elapsed by mutableLongStateOf(0L)
        private set
    /** 每 80 毫秒一个音量，录完压成波形存起来（见 Waves） */
    private val samples = ArrayList<Float>()
    val levels: List<Float> get() = samples
    private var ticker: Job? = null
    private var done = false

    private fun startTicking(onLimit: () -> Unit) {
        ticker = store.scope.launch {
            while (isActive && !done) {
                elapsed = System.currentTimeMillis() - startedAt
                val amp = runCatching { recorder.maxAmplitude }.getOrDefault(0)
                // 人耳对音量是对数感受：开方让小声也看得出起伏
                level = sqrt(amp / 32767f).coerceIn(0f, 1f)
                samples += level
                if (elapsed >= MAX_MS) {
                    onLimit()
                    return@launch
                }
                delay(80)
            }
        }
    }

    /** 停下；太短（不到半秒）或出错返回 null（临时文件已删），否则返回时长（毫秒） */
    fun stop(): Long? {
        if (done) return null
        done = true
        ticker?.cancel()
        val dur = System.currentTimeMillis() - startedAt
        val ok = runCatching { recorder.stop() }.isSuccess
        runCatching { recorder.release() }
        if (!ok || dur < 500) {
            store.clips.recordingFile(id).delete()
            return null
        }
        return dur
    }

    fun cancel() {
        if (done) return
        done = true
        ticker?.cancel()
        runCatching { recorder.stop() }
        runCatching { recorder.release() }
        store.clips.recordingFile(id).delete()
    }

    companion object {
        const val MAX_MS = 30 * 60 * 1000L

        /** 开始录；麦克风被占用等情况返回 null */
        fun start(context: Context, store: Store, onLimit: () -> Unit): VoiceRecording? {
            val id = Ids.next()
            val file = store.clips.recordingFile(id)
            @Suppress("DEPRECATION")
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
            return try {
                r.setAudioSource(MediaRecorder.AudioSource.MIC)
                r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioChannels(1)
                r.setAudioSamplingRate(44_100)
                r.setAudioEncodingBitRate(64_000)
                r.setMaxDuration(MAX_MS.toInt())
                r.setOutputFile(file.path)
                r.prepare()
                r.start()
                VoiceRecording(id, r, store).also { it.startTicking(onLimit) }
            } catch (e: Exception) {
                runCatching { r.release() }
                file.delete()
                null
            }
        }
    }
}

/** 毫秒 → 「1:05」 */
fun durationText(ms: Long): String {
    val s = (ms + 500) / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
