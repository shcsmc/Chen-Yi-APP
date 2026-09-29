package com.beiwang.memo.ui.editor

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.beiwang.memo.data.Blocks
import com.beiwang.memo.data.Media
import com.beiwang.memo.data.MediaKind
import com.beiwang.memo.ui.AppState
import com.beiwang.memo.ui.EditorSession
import com.beiwang.memo.ui.GroupEdit
import com.beiwang.memo.ui.TextEdit
import com.beiwang.memo.ui.common.Haptics
import com.beiwang.memo.ui.common.Txt
import com.beiwang.memo.ui.common.rememberHaptics
import com.beiwang.memo.ui.common.rememberImage
import com.beiwang.memo.ui.glass.Icon
import com.beiwang.memo.ui.icons.Icons
import com.beiwang.memo.ui.theme.LocalPalette
import com.beiwang.memo.ui.theme.Type
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

private val TileShape = RoundedRectangle(14.dp)
/** 一组附件之间的间距 */
val MediaGap = 8.dp

/**
 * 长按拖附件：拖的时候原来那个变淡，手指下面浮着一份；松手放到最近的「段落之间」或「某个附件旁边」。
 * 所有坐标都相对编辑区最外层（[root]），滚动后重新量，不会错位。
 */
@Stable
class MediaDrag {
    var root: LayoutCoordinates? = null
    /** 每个附件的位置（按 id） */
    val itemCoords = HashMap<String, LayoutCoordinates>()

    var item by mutableStateOf<Media?>(null)
        private set
    /** 手指在 [root] 里的位置 */
    var pointer by mutableStateOf(Offset.Zero)
        private set
    /** 手指按在附件里的哪个位置（浮起来的那份跟着手指走，保持这个相对位置） */
    var grab = Offset.Zero
        private set
    var size = Size.Zero
        private set
    var target: Blocks.Target? = null
        private set
    /** 放下去的位置示意：一条强调色的线 */
    var indicator by mutableStateOf<Rect?>(null)
        private set

    fun start(m: Media, local: Offset) {
        val r = root ?: return
        val c = itemCoords[m.id]?.takeIf { it.isAttached } ?: return
        item = m
        grab = local
        size = Size(c.size.width.toFloat(), c.size.height.toFloat())
        pointer = r.localPositionOf(c, local)
    }

    /** [local] 是手指在被拖附件里的位置（附件本身可能随自动滚动移动，所以每次都重新换算） */
    fun move(m: Media, local: Offset) {
        val r = root ?: return
        val c = itemCoords[m.id]?.takeIf { it.isAttached } ?: return
        pointer = r.localPositionOf(c, local)
    }

    fun end() {
        item = null
        target = null
        indicator = null
    }

    /** 找离手指最近的放置点 */
    fun retarget(e: EditorSession, gapPx: Float, linePx: Float) {
        val r = root ?: return
        val p = pointer
        var best: Blocks.Target? = null
        var bestRect: Rect? = null
        var bestDist = Float.MAX_VALUE
        for (b in e.blocks) {
            when (b) {
                is GroupEdit -> {
                    b.items.forEachIndexed { i, m ->
                        val c = itemCoords[m.id]?.takeIf { it.isAttached } ?: return@forEachIndexed
                        val box = r.localBoundingBoxOf(c, clipBounds = false)
                        val d = distance(p, box)
                        if (d < bestDist) {
                            val before = p.x < box.center.x
                            val x = if (before) box.left - gapPx / 2 else box.right + gapPx / 2
                            bestDist = d
                            best = Blocks.IntoGroup(b.key, if (before) i else i + 1)
                            bestRect = Rect(x - linePx / 2, box.top, x + linePx / 2, box.bottom)
                        }
                    }
                }
                is TextEdit -> {
                    val c = b.coords?.takeIf { it.isAttached } ?: continue
                    val box = r.localBoundingBoxOf(c, clipBounds = false)
                    val text = b.state.text.toString()
                    val layout = b.layout?.invoke()
                    // 段落边界：开头、每个换行之后、结尾
                    val offsets = buildList {
                        add(0)
                        text.forEachIndexed { i, ch -> if (ch == '\n') add(i + 1) }
                        add(text.length)
                    }.distinct()
                    for (o in offsets) {
                        val y = box.top + when {
                            text.isEmpty() -> box.height / 2
                            layout == null -> if (o == 0) 0f else box.height
                            o >= text.length -> layout.size.height.toFloat()
                            else -> layout.getLineTop(layout.getLineForOffset(o))
                        }
                        // 横向不在这段文字范围里的，稍微吃点亏（优先放到手指正下方的附件旁）
                        val d = abs(p.y - y) + (if (p.x < box.left || p.x > box.right) 24f else 0f)
                        if (d < bestDist) {
                            bestDist = d
                            best = Blocks.IntoText(b.key, o)
                            bestRect = Rect(box.left, y - linePx / 2, box.right, y + linePx / 2)
                        }
                    }
                }
            }
        }
        target = best
        indicator = bestRect
    }

    private fun distance(p: Offset, r: Rect): Float {
        val dx = max(max(r.left - p.x, 0f), p.x - r.right)
        val dy = max(max(r.top - p.y, 0f), p.y - r.bottom)
        return sqrt(dx * dx + dy * dy)
    }
}

/**
 * 附件的手势：点一下 [onTap]；长按 [onLongPress] 后不松手接着拖（[onDrag] 给的是手指在附件里的位置）。
 * 自己写而不是叠两个现成的：长按后原地松手不能再算一次「点」。
 */
private fun Modifier.mediaGestures(
    key: Any,
    onTap: () -> Unit,
    onLongPress: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
): Modifier = pointerInput(key) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val long = awaitLongPressOrCancellation(down.id)
        if (long == null) {
            val up = currentEvent.changes.firstOrNull { it.id == down.id }
            if (up != null && !up.pressed && !up.isConsumed) {
                up.consume()
                onTap()
            }
            return@awaitEachGesture
        }
        long.consume()
        onLongPress(long.position)
        var finished = false
        try {
            finished = drag(long.id) { change ->
                if (change.positionChange() != Offset.Zero) onDrag(change.position)
                change.consume()
            }
        } finally {
            if (finished) onDragEnd() else onDragCancel()
        }
    }
}

/** 一组附件：按各自的宽度从左到右排，排满换行；对齐方式整组一样 */
@Composable
fun MediaGroup(app: AppState, e: EditorSession, g: GroupEdit, drag: MediaDrag) {
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .onGloballyPositioned { g.coords = it }
    ) {
        val full = maxWidth
        val align = when (g.items.firstOrNull()?.align ?: 0) {
            1 -> Alignment.CenterHorizontally
            2 -> Alignment.End
            else -> Alignment.Start
        }
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MediaGap, align),
            verticalArrangement = Arrangement.spacedBy(MediaGap),
        ) {
            for (m in g.items) {
                key(m.id) {
                    // 宽度按「每个都带一份间距」分：两个 50% 正好一行，三个 33% 也正好一行（减半个点防止舍入挤掉）
                    val w = (full + MediaGap) * (m.displayWidth / 100f) - MediaGap - 0.5.dp
                    MediaItem(app, e, m, w, full, drag)
                }
            }
        }
    }
}

@Composable
private fun MediaItem(app: AppState, e: EditorSession, m: Media, width: Dp, full: Dp, drag: MediaDrag) {
    val pal = LocalPalette.current
    val haptics = rememberHaptics()
    val density = LocalDensity.current
    val selected = e.selected == m.id
    val dragging = drag.item?.id == m.id
    Box(
        Modifier
            .width(width)
            .onGloballyPositioned { drag.itemCoords[m.id] = it }
            .alpha(if (dragging) 0.3f else 1f)
            .mediaGestures(
                key = m.id,
                onTap = {
                    e.selected = null
                    if (m.kind == MediaKind.Audio) app.voice.toggle(m, e.vault) { app.showToast(it) } else app.openViewer(m)
                },
                onLongPress = { pos ->
                    haptics.longPress()
                    e.selected = m.id
                    drag.start(m, pos)
                    with(density) { drag.retarget(e, MediaGap.toPx(), 3.dp.toPx()) }
                },
                onDrag = { pos ->
                    drag.move(m, pos)
                    with(density) { drag.retarget(e, MediaGap.toPx(), 3.dp.toPx()) }
                },
                onDragEnd = {
                    val t = drag.target
                    drag.end()
                    if (t != null) {
                        val before = e.pieces()
                        val after = Blocks.move(before, m.id, t)
                        if (Blocks.join(after) != Blocks.join(before) || after.size != before.size) {
                            e.apply(after)
                            haptics.confirm()
                        }
                    }
                },
                onDragCancel = { drag.end() },
            ),
    ) {
        when (m.kind) {
            MediaKind.Audio -> VoiceBar(app, e, m)
            else -> VisualTile(app, e, m)
        }
        if (selected) {
            Box(
                Modifier
                    .matchParentSize()
                    .border(2.5.dp, pal.accent, if (m.kind == MediaKind.Audio) Capsule() else TileShape)
            )
            if (m.kind != MediaKind.Audio) ResizeHandle(e, m, width, full, haptics, Modifier.align(Alignment.BottomEnd))
        }
    }
}

/** 图片/视频的方块：宽度小的裁成正方形（整齐），大的按原比例；中间平滑过渡，拖角改大小时不会跳 */
@Composable
fun VisualTile(app: AppState, e: EditorSession, m: Media, modifier: Modifier = Modifier) {
    val pal = LocalPalette.current
    val open = if (e.vault) app.store.vault::openBytes else null
    // 图片显示得大就用原图（缩略图只有 480 像素宽，放满屏会糊）；视频只有一张封面
    val big = m.kind == MediaKind.Image && m.displayWidth >= 50
    val bmp = rememberImage(app.store.images, m.id, thumb = !big, open = open)
    val natural = if (m.w > 0 && m.h > 0) (m.w.toFloat() / m.h).coerceIn(0.45f, 2.4f) else 4f / 3f
    val t = ((m.displayWidth - 33) / 17f).coerceIn(0f, 1f)
    val ratio = lerp(1f, natural, t)
    Box(modifier.fillMaxWidth().aspectRatio(ratio).clip(TileShape).background(pal.card)) {
        if (bmp != null) Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        if (m.kind == MediaKind.Video) {
            Box(
                Modifier.align(Alignment.Center).size(46.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.42f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.play, Color.White, Modifier.size(24.dp).offset(x = 1.5.dp))
            }
            if (m.dur > 0) {
                Txt(
                    durationText(m.dur), Type.small, color = Color.White,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(7.dp)
                        .background(Color.Black.copy(alpha = 0.45f), Capsule()).padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/** 语音条：▶ + 时长；播放时进度从左往右铺强调色 */
@Composable
fun VoiceBar(app: AppState, e: EditorSession?, m: Media, modifier: Modifier = Modifier) {
    val pal = LocalPalette.current
    val voice = app.voice
    val mine = voice.current == m.id
    val playing = mine && voice.playing
    val progress = if (mine && m.dur > 0) (voice.position.toFloat() / m.dur).coerceIn(0f, 1f) else 0f
    Row(
        modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(Capsule())
            .background(pal.accent.copy(alpha = 0.16f))
            .drawBehind {
                if (progress > 0f) drawRect(pal.accent.copy(alpha = 0.22f), size = Size(size.width * progress, size.height))
            }
            .padding(start = 6.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(pal.accent), contentAlignment = Alignment.Center) {
            Icon(if (playing) Icons.pause else Icons.play, pal.onAccent, Modifier.size(18.dp).offset(x = if (playing) 0.dp else 1.dp))
        }
        Spacer(Modifier.width(8.dp))
        // 示意的声波：按 id 生成固定的高低，不是真实波形
        Waveform(m.id, pal.accent, Modifier.weight(1f).height(22.dp))
        Spacer(Modifier.width(8.dp))
        Txt(durationText(if (mine && voice.position > 0) voice.position else m.dur), Type.label, color = pal.ink2, maxLines = 1)
    }
}

@Composable
private fun Waveform(seed: String, color: Color, modifier: Modifier) {
    val bars = remember(seed) {
        val r = java.util.Random(seed.hashCode().toLong())
        List(28) { 0.25f + r.nextFloat() * 0.75f }
    }
    Box(
        modifier.drawBehind {
            val n = bars.size
            val step = size.width / n
            val w = (step * 0.45f).coerceAtLeast(1f)
            bars.forEachIndexed { i, h ->
                val bh = size.height * h
                drawRoundRect(
                    color.copy(alpha = 0.55f),
                    topLeft = Offset(i * step + (step - w) / 2, (size.height - bh) / 2),
                    size = Size(w, bh),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(w / 2, w / 2),
                )
            }
        }
    )
}

/** 选中的图片/视频右下角的拖角：左右拖改宽度，靠近常用宽度（1/4、1/3、1/2…）会吸过去并轻震一下 */
@Composable
private fun ResizeHandle(e: EditorSession, m: Media, width: Dp, full: Dp, haptics: Haptics, modifier: Modifier) {
    val pal = LocalPalette.current
    val density = LocalDensity.current
    Box(
        modifier
            .offset(x = 7.dp, y = 7.dp)
            .size(30.dp)
            .pointerInput(m.id, full) {
                var startPx = 0f
                var moved = 0f
                var last = m.displayWidth
                val fullPx = with(density) { full.toPx() }
                val gapPx = with(density) { MediaGap.toPx() }
                detectDragGestures(
                    onDragStart = {
                        startPx = with(density) { width.toPx() }
                        moved = 0f
                        last = e.media.firstOrNull { it.id == m.id }?.displayWidth ?: m.displayWidth
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        moved += amount.x
                        val pct = (startPx + moved + gapPx) / (fullPx + gapPx) * 100f
                        val next = Blocks.snapWidth(pct)
                        if (next != last) {
                            if (next in Blocks.SNAPS) haptics.tick()
                            last = next
                            e.apply(Blocks.update(e.pieces(), m.id) { it.copy(width = next) })
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(22.dp).clip(CircleShape).background(pal.accent).border(2.dp, Color.White, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.resize, pal.onAccent, Modifier.size(12.dp))
        }
    }
}

/** 拖动时浮在手指下面的那一份，和放下位置的示意线（画在编辑区最上层） */
@Composable
fun DragOverlay(app: AppState, e: EditorSession, drag: MediaDrag) {
    val pal = LocalPalette.current
    val m = drag.item ?: return
    val density = LocalDensity.current
    drag.indicator?.let { r ->
        Box(
            Modifier
                .offset { IntOffset(r.left.roundToInt(), r.top.roundToInt()) }
                .size(with(density) { r.width.toDp() }, with(density) { r.height.toDp() })
                .clip(Capsule())
                .background(pal.accent)
        )
    }
    val w = with(density) { drag.size.width.toDp() }
    Box(
        Modifier
            .offset { IntOffset((drag.pointer.x - drag.grab.x).roundToInt(), (drag.pointer.y - drag.grab.y).roundToInt()) }
            .width(w)
            .graphicsLayer {
                scaleX = 1.04f
                scaleY = 1.04f
                alpha = 0.92f
                shadowElevation = 18.dp.toPx()
                shape = if (m.kind == MediaKind.Audio) Capsule() else TileShape
                clip = true
            }
    ) {
        if (m.kind == MediaKind.Audio) VoiceBar(app, e, m) else VisualTile(app, e, m)
    }
}

/** 编辑区最底下的空白：点一下把光标放到最后一段末尾 */
@Composable
fun EndSpace(onTap: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(180.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val up = awaitLongPressOrCancellation(down.id)
                    if (up == null) {
                        val c = currentEvent.changes.firstOrNull { it.id == down.id }
                        if (c != null && !c.pressed && !c.isConsumed) onTap()
                    }
                }
            }
    )
}
