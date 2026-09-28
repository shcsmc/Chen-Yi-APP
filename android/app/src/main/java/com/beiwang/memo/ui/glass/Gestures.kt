package com.beiwang.memo.ui.glass

import android.os.SystemClock
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.positionChange

/**
 * 只“看”不“抢”的手势：跟踪第一根手指的按下、移动、抬起，不消费事件，
 * 所以和 clickable、滚动等可以同时生效。给按压高光用。
 */
suspend fun PointerInputScope.observeDrag(
    onStart: (Offset) -> Unit,
    onMove: (Offset) -> Unit,
    onEnd: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        onStart(down.position)
        var id = down.id
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == id } ?: break
            if (change.changedToUpIgnoreConsumed()) {
                // 第一根抬起但还有别的手指按着：接着跟那一根
                val other = event.changes.firstOrNull { it.pressed && it.id != id } ?: break
                id = other.id
                continue
            }
            onMove(change.position)
        }
        onEnd()
    }
}

/**
 * 底栏透镜的手势：按下即浮起；移动超过触摸阈值才算拖动（避免手指轻微抖动带着透镜晃）；
 * 按住不动超过长按时间算长按（拖动开始后不再触发长按）。
 */
suspend fun PointerInputScope.detectLensGestures(
    onPress: () -> Unit,
    onDrag: (dx: Float) -> Unit,
    onRelease: () -> Unit,
    onLongPress: () -> Unit,
) {
    val slop = viewConfiguration.touchSlop
    val longPressMs = viewConfiguration.longPressTimeoutMillis
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        onPress()
        val downAt = SystemClock.uptimeMillis()
        val id = down.id
        var travelled = Offset.Zero
        var dragging = false
        var longPressed = false
        while (true) {
            val event = if (!dragging && !longPressed) {
                val left = longPressMs - (SystemClock.uptimeMillis() - downAt)
                if (left <= 0) null else withTimeoutOrNull(left) { awaitPointerEvent() }
            } else {
                awaitPointerEvent()
            }
            if (event == null) {
                longPressed = true
                onLongPress()
                continue
            }
            val change = event.changes.firstOrNull { it.id == id } ?: break
            if (change.changedToUpIgnoreConsumed() || !change.pressed) break
            if (longPressed) continue
            val delta = change.positionChange()
            if (dragging) {
                onDrag(delta.x)
            } else {
                travelled += delta
                if (travelled.getDistance() > slop) {
                    dragging = true
                    onDrag(travelled.x)
                }
            }
        }
        onRelease()
    }
}
