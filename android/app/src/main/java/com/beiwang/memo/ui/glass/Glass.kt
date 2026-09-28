package com.beiwang.memo.ui.glass

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.beiwang.memo.ui.common.rememberHaptics
import com.beiwang.memo.ui.theme.LocalPalette
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.emptyBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.isRenderEffectSupported
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh

/** 玻璃取景的来源：页面内容录成的图层。玻璃件从这里拿“背后的东西”来模糊、折射 */
val LocalBackdrop = staticCompositionLocalOf { emptyBackdrop() }

private val GlassShadow = Shadow(radius = 18.dp, color = Color.Black.copy(alpha = 0.14f))

/**
 * 液态玻璃的基础修饰：背景模糊 + 边缘透镜折射 + 棱边高光 + 投影。
 *
 * @param blurRadius 背景模糊半径
 * @param refraction 折射带宽（离边缘多远以内会“掰弯”背景）；0 = 不折射
 * @param depth 折射强度
 * @param surface 叠在背景上的表面色（安卓 12 以下没有模糊，自动换成 [fallback]）
 * @param tint 有色玻璃（强调色按钮等）；Unspecified = 无色
 * @param exported 非空时把本玻璃画出来的样子导出，给压在它上面的玻璃当背景
 */
fun Modifier.glass(
    backdrop: Backdrop,
    shape: Shape,
    surface: Color,
    fallback: Color,
    tint: Color = Color.Unspecified,
    blurRadius: Dp = 6.dp,
    refraction: Dp = 12.dp,
    depth: Dp = 20.dp,
    shadow: Boolean = true,
    highlight: Highlight? = Highlight.Default,
    exported: LayerBackdrop? = null,
    layerBlock: (GraphicsLayerScope.() -> Unit)? = null,
): Modifier {
    val supported = isRenderEffectSupported()
    val onSurface: DrawScope.() -> Unit = {
        if (tint.isSpecified) {
            if (supported) drawRect(tint, blendMode = BlendMode.Hue)
            drawRect(tint.copy(alpha = if (supported) 0.78f else 0.95f))
        } else {
            drawRect(if (supported) surface else fallback)
        }
    }
    return drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            blur(blurRadius.toPx())
            if (refraction > 0.dp) lens(refraction.toPx(), depth.toPx())
        },
        highlight = if (highlight != null) ({ highlight }) else null,
        shadow = if (shadow) ({ GlassShadow }) else null,
        layerBlock = layerBlock,
        exportedBackdrop = exported,
        onDrawSurface = onSurface,
    )
}

/**
 * 玻璃按钮：按下时玻璃微微鼓起、跟着手指挪一点，手指处有一团柔光。所有按钮都用它。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = Capsule(),
    tint: Color = Color.Unspecified,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val pal = LocalPalette.current
    val backdrop = LocalBackdrop.current
    val scope = rememberCoroutineScope()
    val hl = remember(scope) { InteractiveHighlight(scope) }
    val haptics = rememberHaptics()
    Box(
        modifier
            .glass(
                backdrop = backdrop, shape = shape, surface = pal.glass, fallback = pal.glassFallback, tint = tint,
                blurRadius = 4.dp, refraction = 10.dp, depth = 18.dp,
                layerBlock = { pressTransform(hl) },
            )
            .combinedClickable(
                interactionSource = null,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onLongClick = onLongClick,   // 长按自带震动
                onClick = { haptics.tick(); onClick() },
            )
            .then(hl.modifier)
            .then(hl.gestureModifier),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/** 圆形图标按钮 */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    iconSize: Dp = 22.dp,
    iconTint: Color = LocalPalette.current.ink,
    tint: Color = Color.Unspecified,
    enabled: Boolean = true,
) {
    GlassButton(onClick = onClick, modifier = modifier.size(size), tint = tint, enabled = enabled) {
        Icon(icon, iconTint, Modifier.size(iconSize))
    }
}

@Composable
fun Icon(icon: ImageVector, tint: Color, modifier: Modifier = Modifier) {
    Image(rememberVectorPainter(icon), contentDescription = null, modifier = modifier, colorFilter = ColorFilter.tint(tint))
}

/** 按压形变：鼓起 4dp，并按手指位移做一点点拉伸和跟随（tanh 限幅，拖多远都不会跑出去） */
fun GraphicsLayerScope.pressTransform(hl: InteractiveHighlight) {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return
    val progress = hl.pressProgress
    val scale = lerp(1f, 1f + 4.dp.toPx() / h, progress)
    val maxOffset = size.minDimension
    val k = 0.05f
    val off = hl.offset
    translationX = maxOffset * tanh(k * off.x / maxOffset)
    translationY = maxOffset * tanh(k * off.y / maxOffset)
    val maxDragScale = 4.dp.toPx() / h
    val angle = atan2(off.y, off.x)
    scaleX = scale + maxDragScale * abs(cos(angle) * off.x / size.maxDimension) * (w / h).coerceAtMost(1f)
    scaleY = scale + maxDragScale * abs(sin(angle) * off.y / size.maxDimension) * (h / w).coerceAtMost(1f)
}
