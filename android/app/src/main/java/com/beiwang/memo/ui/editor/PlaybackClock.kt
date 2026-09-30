package com.beiwang.memo.ui.editor

import kotlin.math.abs

/**
 * 语音条进度用的「表」：播放器大约 0.1 秒才报一次位置，波形每一帧都要一个平滑的位置，这块表在两次读数之间自己走。
 * - 开始放、继续放、拖动之后先不走（[hold]），等播放器报的位置真的动了才开始走：按下播放到出声有零点几秒延迟，
 *   这样进度不会比声音快；
 * - 之后每次对表（[sync]）：差一点就只调走速（0.5～1.5 倍），半秒左右追平，进度连续、只往前走；
 *   差太多（卡了一下、跳了一下）才直接对上。
 * 纯算法（时间由 [now] 给），有单元测试。
 */
class PlaybackClock(private val now: () -> Long) {
    private var anchorPos = 0L
    private var anchorAt = 0L
    private var rate = 0f

    /** 停在 [pos]，等播放器动起来再走 */
    fun hold(pos: Long) = set(pos, 0f)

    /** 此刻的位置（毫秒），不超过 [duration]（≤ 0 表示不知道总长） */
    fun position(duration: Long): Long {
        val p = anchorPos + ((now() - anchorAt) * rate).toLong()
        return if (duration > 0) p.coerceIn(0L, duration) else p.coerceAtLeast(0L)
    }

    /** 和播放器报的位置 [reported] 对一次表 */
    fun sync(reported: Long, duration: Long) {
        if (rate == 0f) {
            if (reported > anchorPos) set(reported, 1f)          // 声音出来了，开始走
            return
        }
        val live = position(duration)
        val err = reported - live
        if (abs(err) > JUMP_MS) set(reported, 1f) else set(live, (1f + err / CATCH_UP_MS).coerceIn(0.5f, 1.5f))
    }

    private fun set(pos: Long, speed: Float) {
        anchorPos = pos
        anchorAt = now()
        rate = speed
    }

    private companion object {
        /** 差这么多就直接对上 */
        const val JUMP_MS = 300L
        /** 差一点时，按「这么多毫秒追平」调走速 */
        const val CATCH_UP_MS = 400f
    }
}
