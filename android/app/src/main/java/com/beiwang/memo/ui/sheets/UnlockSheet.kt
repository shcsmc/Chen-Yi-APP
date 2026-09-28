package com.beiwang.memo.ui.sheets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.beiwang.memo.data.Snapshot
import com.beiwang.memo.legacy.LegacyCrypto
import com.beiwang.memo.ui.AppState
import com.beiwang.memo.ui.common.Haptics
import com.beiwang.memo.ui.common.Txt
import com.beiwang.memo.ui.common.rememberHaptics
import com.beiwang.memo.ui.glass.GlassButton
import com.beiwang.memo.ui.theme.LocalPalette
import com.beiwang.memo.ui.theme.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 用旧版图案把遗留的加密备忘解成明文（旧版图案锁已停用，新版不再加密） */
@Composable
fun UnlockSheet(app: AppState, snap: Snapshot) {
    val pal = LocalPalette.current
    val store = app.store
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val lockJson by store.prefs.legacyLock.collectAsState()
    val count = snap.notes.count { it.encrypted }
    var status by remember { mutableStateOf("画出旧版设置过的图案") }
    var bad by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var reset by remember { mutableIntStateOf(0) }

    PanelTitle("解开旧版加密备忘")
    Txt(
        "旧版的图案锁已经停用。最后画一次当时的图案，把 $count 条加密备忘转回普通文本，之后就不再需要图案。",
        Type.small.copy(lineHeight = Type.small.fontSize * 1.6f), color = pal.ink2,
        modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 14.dp),
    )

    val lock = remember(lockJson) { lockJson?.let { LegacyCrypto.parseLock(it) } }
    if (lock == null) {
        Txt("缺少图案配置，这些备忘无法解开。", Type.row, color = pal.danger, modifier = Modifier.padding(start = 4.dp, bottom = 12.dp))
    } else {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            PatternPad(enabled = !busy, resetKey = reset, haptics = haptics) { seq ->
                if (seq.size < 4) {
                    status = "至少连 4 个点"; bad = true; haptics.reject(); reset++
                    return@PatternPad
                }
                busy = true; bad = false; status = "正在验证…"
                scope.launch {
                    val key = withContext(Dispatchers.Default) { LegacyCrypto.unlock(seq.joinToString("-"), lock) }
                    if (key == null) {
                        status = "图案不对"; bad = true; busy = false; reset++
                        haptics.reject()
                        return@launch
                    }
                    val enc = store.data.value.notes.filter { it.encrypted }
                    val fixed = withContext(Dispatchers.Default) {
                        enc.mapNotNull { n -> LegacyCrypto.decrypt(key, n.body)?.let { n.copy(body = it, encrypted = false) } }
                    }
                    store.putNotes(fixed)
                    if (fixed.size == enc.size) store.prefs.setLegacyLock(null)
                    haptics.confirm()
                    app.sheet = null
                    app.showToast(
                        if (fixed.size == enc.size) "已解开 ${fixed.size} 条，以后不再需要图案"
                        else "已解开 ${fixed.size} 条，${enc.size - fixed.size} 条不是这个图案加密的"
                    )
                }
            }
        }
        Txt(status, Type.label, color = if (bad) pal.danger else pal.ink2,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp), maxLines = 1)
    }
    Spacer(Modifier.height(14.dp))
    Row {
        Spacer(Modifier.weight(1f))
        GlassButton(onClick = { app.sheet = null }) {
            Txt("先不解", Type.label, color = pal.ink2, modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp))
        }
    }
}

/** 3×3 图案盘：按顺序连点，松手回调点位序列（0..8） */
@Composable
private fun PatternPad(enabled: Boolean, resetKey: Int, haptics: Haptics, onDone: (List<Int>) -> Unit) {
    val pal = LocalPalette.current
    var seq by remember { mutableStateOf(listOf<Int>()) }
    var finger by remember { mutableStateOf<Offset?>(null) }
    LaunchedEffect(resetKey) { if (resetKey > 0) { kotlinx.coroutines.delay(420); seq = emptyList() } }

    Canvas(
        Modifier
            .size(252.dp)
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
