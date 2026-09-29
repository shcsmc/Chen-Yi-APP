/*
 * 底栏透镜的做法改编自 Kyant0/AndroidLiquidGlass 的 LiquidBottomTabs 示例（Apache License 2.0，Copyright Kyant）。
 * 改动：分类数可变、最右固定一个「＋」、透镜只在分类之间移动、拖动有触摸阈值。
 */
package com.beiwang.memo.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.beiwang.memo.data.Category
import com.beiwang.memo.ui.common.Txt
import com.beiwang.memo.ui.common.rememberHaptics
import com.beiwang.memo.ui.icons.Icons
import com.beiwang.memo.ui.theme.LocalPalette
import com.beiwang.memo.ui.theme.Type
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

/** 透镜按下时每格内容放大的倍数（让透镜下的图标“浮”起来） */
private val LocalTabScale = staticCompositionLocalOf { { 1f } }

/**
 * 液态玻璃底栏：分类若干格 + 最右一格「＋」，每格等宽。
 *
 * 三层叠起来：
 * 1. 可见的底栏：玻璃条 + 普通颜色的图标文字；
 * 2. 看不见的一层：同样的内容染成强调色，只录成图层（tabsBackdrop），不直接显示；
 * 3. 透镜：它的背景 = 页面 + 第 2 层。所以透镜盖到哪里，哪里的图标就变成强调色，
 *    按下时透镜放大、边缘折射，拖动时按速度被拉长，松手弹回灰色小胶囊。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LiquidTabBar(
    categories: List<Category>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onAdd: () -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
) {
    val pal = LocalPalette.current
    val haptics = rememberHaptics()
    val catCount = categories.size.coerceAtLeast(1)
    val slots = catCount + 1
    val containerColor = if (pal.dark) Color(0xFF121212).copy(alpha = 0.4f) else Color(0xFFFAFAFA).copy(alpha = 0.4f)
    val fallback = pal.glassFallback
    val renderEffect = isRenderEffectSupported()
    val tabsBackdrop = rememberLayerBackdrop()
    val onSelectState = rememberUpdatedState(onSelect)

    BoxWithConstraints(modifier, contentAlignment = Alignment.CenterStart) {
        val density = LocalDensity.current
        val fullWidth = constraints.maxWidth.toFloat()
        val tabWidth = with(density) { (fullWidth - 8.dp.toPx()) / slots }

        val scope = rememberCoroutineScope()
        // 整条底栏跟着拖动方向轻轻偏一点（最多 4dp），松手弹回
        val panelDrag = remember { Animatable(0f) }
        val panelOffset by remember(density, fullWidth) {
            derivedStateOf {
                val f = (panelDrag.value / fullWidth).coerceIn(-1f, 1f)
                with(density) { 4.dp.toPx() * f.sign * EaseOut.transform(abs(f)) }
            }
        }

        var current by remember { mutableIntStateOf(selected.coerceIn(0, catCount - 1)) }
        val drag = remember(scope, catCount) {
            DampedDragAnimation(
                scope = scope,
                initialValue = current.coerceIn(0, catCount - 1).toFloat(),
                valueRange = 0f..(catCount - 1).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = 78f / 56f,
            )
        }
        val highlight = remember(scope, drag) {
            InteractiveHighlight(scope) { size, _ ->
                Offset((drag.value + 0.5f) * tabWidth + panelOffset, size.height / 2f)
            }
        }

        // 外部选中变化（点了某格、新建/删除了分类）→ 透镜滑过去。
        // 不用 drop(1) 跳过首个值：分类数变化时 drag 会重建，首个值可能正是新位置
        LaunchedEffect(selected, catCount) { current = selected.coerceIn(0, catCount - 1) }
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

        // 1. 可见底栏
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
                .height(64.dp)
                .fillMaxWidth()
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            categories.forEachIndexed { i, c ->
                TabItem(label = c.name, iconKey = c.icon, color = pal.ink, onClick = { tap(i) })
            }
            AddSlot(onAdd)
        }

        // 2. 强调色的一层：透明度 0，只录进 tabsBackdrop 给透镜用
        CompositionLocalProvider(LocalTabScale provides { lerp(1f, 1.2f, drag.pressProgress) }) {
            Row(
                Modifier
                    .clearAndSetSemantics {}
                    .alpha(0f)
                    .layerBackdrop(tabsBackdrop)
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
                    .height(56.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 这一层叠在可见底栏上面、会先收到点击，所以点击处理必须和可见层一样
                categories.forEachIndexed { i, c ->
                    TabItem(label = c.name, iconKey = c.icon, color = pal.accent, onClick = { tap(i) })
                }
                AddSlot(onAdd)
            }
        }

        // 3. 透镜
        Box(
            Modifier
                .padding(horizontal = 4.dp)
                .graphicsLayer { translationX = drag.value * tabWidth + panelOffset }
                .then(highlight.gestureModifier)
                .pointerInput(drag, tabWidth) {
                    detectLensGestures(
                        onPress = { drag.press() },
                        onDrag = { dx ->
                            drag.updateValue(drag.targetValue + dx / tabWidth)
                            scope.launch { panelDrag.snapTo(panelDrag.value + dx) }
                        },
                        onRelease = {
                            val target = drag.targetValue.roundToInt().coerceIn(0, catCount - 1)
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
                    backdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop),
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
                .height(56.dp)
                .fillMaxWidth(1f / slots),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowScope.TabItem(
    label: String,
    iconKey: String,
    color: Color,
    onClick: () -> Unit,
) {
    val scale = LocalTabScale.current
    Column(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .clickable(interactionSource = null, indication = null, role = Role.Tab, onClick = onClick)
            .graphicsLayer {
                val s = scale()
                scaleX = s
                scaleY = s
            },
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.category(iconKey), color, Modifier.size(24.dp))
        Txt(label, Type.tab, color = color, maxLines = 1)
    }
}

/** 最右一格：强调色小胶囊 + 白色加号（和参考里中间那颗一样的做法） */
@Composable
private fun RowScope.AddSlot(onAdd: () -> Unit) {
    val pal = LocalPalette.current
    Box(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .clickable(interactionSource = null, indication = null, role = Role.Button, onClick = onAdd),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = 52.dp, height = 36.dp)
                .background(pal.accent, Capsule()),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.plusFill, pal.onAccent, Modifier.size(22.dp))
        }
    }
}
