package com.beiwang.memo.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.beiwang.memo.ui.theme.LocalPalette

/** 3×3 图案盘：按顺序连点，松手回调点位序列（0..8） */
@Composable
fun PatternPad(
    enabled: Boolean,
    resetKey: Int,
    haptics: Haptics,
    modifier: Modifier = Modifier,
    padSize: Dp = 252.dp,
    onDone: (List<Int>) -> Unit,
) {
    val pal = LocalPalette.current
    var seq by remember { mutableStateOf(listOf<Int>()) }
    var finger by remember { mutableStateOf<Offset?>(null) }
    LaunchedEffect(resetKey) { if (resetKey > 0) { kotlinx.coroutines.delay(420); seq = emptyList() } }

    Canvas(
        modifier
            .size(padSize)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                val cell = size.width / 3f
                fun center(i: Int) = Offset(cell * (i % 3) + cell / 2f, cell * (i / 3) + cell / 2f)
                fun hit(p: Offset) {
                    for (i in 0..8) {
                        if (i !in seq && (p - center(i)).getDistance() < cell * 0.34f) {
                            seq = seq + i
                            haptics.tick()
                        }
                    }
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    seq = emptyList()
                    hit(down.position)
                    finger = down.position
                    while (true) {
                        val ev = awaitPointerEvent()
                        val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) break
                        hit(c.position)
                        finger = c.position
                        c.consume()
                    }
                    finger = null
                    onDone(seq)
                }
            }
    ) {
        val cell = size.width / 3f
        fun center(i: Int) = Offset(cell * (i % 3) + cell / 2f, cell * (i / 3) + cell / 2f)
        if (seq.isNotEmpty()) {
            val path = Path().apply {
                moveTo(center(seq[0]).x, center(seq[0]).y)
                for (i in seq.drop(1)) lineTo(center(i).x, center(i).y)
                finger?.let { lineTo(it.x, it.y) }
            }
            drawPath(path, pal.accent.copy(alpha = 0.8f), style = Stroke(4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        for (i in 0..8) {
            val on = i in seq
            drawCircle(if (on) pal.accent else pal.ink3, radius = (if (on) 11.dp else 7.dp).toPx(), center = center(i))
        }
    }
}
