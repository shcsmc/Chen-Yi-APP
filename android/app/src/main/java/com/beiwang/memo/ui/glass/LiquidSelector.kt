/*
 * 液态玻璃滑动选择条：和底栏（LiquidTabBar）同一套做法，改编自 Kyant0/AndroidLiquidGlass 的 LiquidBottomTabs 示例
 * （Apache License 2.0，Copyright Kyant）。
 * 和底栏的区别：每格的内容由调用方画（现在用来选字号），没有最右的「＋」。底栏本身不改动。
 */
package com.beiwang.memo.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.beiwang.memo.ui.common.rememberHaptics
import com.beiwang.memo.ui.theme.LocalPalette
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.isRenderEffectSupported
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/** 透镜按下时每格内容放大的倍数 */
private val LocalSlotScale = staticCompositionLocalOf { { 1f } }

/**
 * [count] 格等宽，透镜停在选中的那一格：点哪格跳哪格；按住透镜左右拖，松手停在最近的一格。
 * 三层叠法和底栏一样：可见的玻璃条 → 看不见的强调色副本（只录成图层）→ 透镜（背景 = 页面 + 强调色副本），
 * 所以透镜盖到哪一格，那一格就变强调色。[item] 画第几格的内容，颜色由这里给（普通色 / 强调色两份）。
 */
@Composable
fun LiquidSelector(
    count: Int,
    selected: Int,
    onSelect: (Int) -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    height: Dp = 60.dp,
    item: @Composable BoxScope.(index: Int, color: Color) -> Unit,
) {
    val pal = LocalPalette.current
    val haptics = rememberHaptics()
    // 透镜和强调色那层比玻璃条上下各缩 4dp
    val inner = height - 8.dp
    val n = count.coerceAtLeast(1)
    val containerColor = if (pal.dark) Color(0xFF121212).copy(alpha = 0.4f) else Color(0xFFFAFAFA).copy(alpha = 0.4f)
    val fallback = pal.glassFallback
    val renderEffect = isRenderEffectSupported()
    val slotsBackdrop = rememberLayerBackdrop()
    val onSelectState = rememberUpdatedState(onSelect)

    BoxWithConstraints(modifier, contentAlignment = Alignment.CenterStart) {
        val density = LocalDensity.current
        val fullWidth = constraints.maxWidth.toFloat()
        val slotWidth = with(density) { (fullWidth - 8.dp.toPx()) / n }

        val scope = rememberCoroutineScope()
        // 整条跟着拖动方向轻轻偏一点（最多 4dp），松手弹回
        val panelDrag = remember { Animatable(0f) }
        val panelOffset by remember(density, fullWidth) {
            derivedStateOf {
                val f = (panelDrag.value / fullWidth).coerceIn(-1f, 1f)
                with(density) { 4.dp.toPx() * f.sign * EaseOut.transform(abs(f)) }
            }
        }

        var current by remember { mutableIntStateOf(selected.coerceIn(0, n - 1)) }
        val drag = remember(scope, n) {
            DampedDragAnimation(
                scope = scope,
                initialValue = current.coerceIn(0, n - 1).toFloat(),
                valueRange = 0f..(n - 1).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = 78f / 56f,
            )
        }
        val highlight = remember(scope, drag) {
            InteractiveHighlight(scope) { size, _ ->
                Offset((drag.value + 0.5f) * slotWidth + panelOffset, size.height / 2f)
            }
        }

        // 外面改了选中（比如换了一条笔记）→ 透镜滑过去；选中变化时通知外面
        LaunchedEffect(selected, n) { current = selected.coerceIn(0, n - 1) }
        LaunchedEffect(drag) {
            snapshotFlow { current }.collectLatest { i ->
                if (abs(drag.targetValue - i) > 0.001f || abs(drag.value - i) > 0.001f) drag.animateToValue(i.toFloat())
                onSelectState.value(i)
            }
        }

        val tap: (Int) -> Unit = { i ->
            if (i != current) {
                haptics.tick()
                current = i
            }
        }

        // 1. 可见的玻璃条
        Row(
            Modifier
                .graphicsLayer { translationX = panelOffset }
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { Capsule() },
                    effects = {
                        vibrancy()
                        blur(8.dp.toPx())
                        sharedLens(24.dp.toPx(), 24.dp.toPx())
                    },
                    layerBlock = {
                        val scale = lerp(1f, 1f + 16.dp.toPx() / size.width, drag.pressProgress)
                        scaleX = scale
                        scaleY = scale
                    },
                    onDrawSurface = { drawRect(if (renderEffect) containerColor else fallback) },
                )
                .then(highlight.modifier)
                .height(height)
                .fillMaxWidth()
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(n) { i -> Slot(onClick = { tap(i) }) { item(i, pal.ink) } }
        }

        // 2. 强调色的一层：透明度 0，只录进 slotsBackdrop 给透镜用
        CompositionLocalProvider(LocalSlotScale provides { lerp(1f, 1.2f, drag.pressProgress) }) {
            Row(
                Modifier
                    .clearAndSetSemantics {}
                    .alpha(0f)
                    .layerBackdrop(slotsBackdrop)
                    .graphicsLayer { translationX = panelOffset }
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { Capsule() },
                        effects = {
                            val p = drag.pressProgress
                            vibrancy()
                            blur(8.dp.toPx())
                            sharedLens(24.dp.toPx() * p, 24.dp.toPx() * p)
                        },
                        highlight = { GlassHighlight.copy(alpha = drag.pressProgress) },
                        onDrawSurface = { drawRect(containerColor) },
                    )
                    .height(inner)
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 这一层叠在可见层上面、会先收到点击，所以点击处理必须和可见层一样
                repeat(n) { i -> Slot(onClick = { tap(i) }) { item(i, pal.accent) } }
            }
        }

        // 3. 透镜
        Box(
            Modifier
                .padding(horizontal = 4.dp)
                .graphicsLayer { translationX = drag.value * slotWidth + panelOffset }
                .then(highlight.gestureModifier)
                .pointerInput(drag, slotWidth) {
                    detectLensGestures(
                        onPress = { drag.press() },
                        onDrag = { dx ->
                            drag.updateValue(drag.targetValue + dx / slotWidth)
                            scope.launch { panelDrag.snapTo(panelDrag.value + dx) }
                        },
                        onRelease = {
                            val target = drag.targetValue.roundToInt().coerceIn(0, n - 1)
                            if (target != current) {
                                haptics.tick()
                                current = target          // 由上面的 LaunchedEffect 负责滑过去
                            } else {
                                drag.animateToValue(target.toFloat())
                            }
                            scope.launch { panelDrag.animateTo(0f, spring(1f, 300f, 0.5f)) }
                        },
                    )
                }
                .drawBackdrop(
                    backdrop = rememberCombinedBackdrop(backdrop, slotsBackdrop),
                    shape = { Capsule() },
                    effects = {
                        val p = drag.pressProgress
                        sharedLens(10.dp.toPx() * p, 14.dp.toPx() * p, chromaticAberration = true)
                    },
                    highlight = { GlassHighlight.copy(alpha = drag.pressProgress) },
                    shadow = { Shadow(alpha = drag.pressProgress) },
                    innerShadow = { InnerShadow(radius = 8.dp * drag.pressProgress, alpha = drag.pressProgress) },
                    layerBlock = {
                        scaleX = drag.scaleX
                        scaleY = drag.scaleY
                        val v = drag.velocity / 10f
                        scaleX /= 1f - (v * 0.75f).coerceIn(-0.2f, 0.2f)
                        scaleY *= 1f - (v * 0.25f).coerceIn(-0.2f, 0.2f)
                    },
                    onDrawSurface = {
                        val p = drag.pressProgress
                        drawRect(if (pal.dark) Color.White.copy(0.1f) else Color.Black.copy(0.1f), alpha = 1f - p)
                        drawRect(Color.Black.copy(alpha = 0.03f * p))
                    },
                )
                .height(inner)
                .fillMaxWidth(1f / n),
        )
    }
}

/** 一格：等宽、占满高度、可点；强调色那层按下时内容跟着放大 */
@Composable
private fun RowScope.Slot(onClick: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    val scale = LocalSlotScale.current
    Box(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .clickable(interactionSource = null, indication = null, role = Role.Button, onClick = onClick)
            .graphicsLayer {
                val s = scale()
                scaleX = s
                scaleY = s
            },
        contentAlignment = Alignment.Center,
        content = content,
    )
}
