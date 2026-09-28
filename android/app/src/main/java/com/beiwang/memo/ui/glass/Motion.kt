/*
 * 改编自 Kyant0/AndroidLiquidGlass 的示例代码（catalog/utils/DampedDragAnimation.kt、
 * InteractiveHighlight.kt），Apache License 2.0，Copyright Kyant。
 * 改动：去掉内置手势（由调用方接自己的手势）、帧等待改用 withFrameNanos、高光着色器直接用系统 RuntimeShader。
 */
package com.beiwang.memo.ui.glass

import android.graphics.RuntimeShader
import android.os.Build
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.foundation.MutatorMutex
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 带阻尼的拖动值：底栏透镜的位置、按下后的放大、拖动时按速度拉伸，都由它驱动。
 * value 在 [valueRange] 内（底栏里就是分类序号，可以是小数）。
 */
class DampedDragAnimation(
    private val scope: CoroutineScope,
    initialValue: Float,
    val valueRange: ClosedFloatingPointRange<Float>,
    private val visibilityThreshold: Float = 0.001f,
    private val initialScale: Float = 1f,
    private val pressedScale: Float = 1f,
) {
    private val valueSpec = spring(1f, 1000f, visibilityThreshold)
    private val velocitySpec = spring(0.5f, 300f, visibilityThreshold * 10f)
    private val pressSpec = spring(1f, 1000f, 0.001f)
    private val scaleXSpec = spring(0.6f, 250f, 0.001f)
    private val scaleYSpec = spring(0.7f, 250f, 0.001f)

    private val valueAnim = Animatable(initialValue, visibilityThreshold)
    private val velocityAnim = Animatable(0f, 5f)
    private val pressAnim = Animatable(0f, 0.001f)
    private val scaleXAnim = Animatable(initialScale, 0.001f)
    private val scaleYAnim = Animatable(initialScale, 0.001f)

    private val mutex = MutatorMutex()
    private val tracker = VelocityTracker()

    val value: Float get() = valueAnim.value
    val targetValue: Float get() = valueAnim.targetValue
    val pressProgress: Float get() = pressAnim.value
    val scaleX: Float get() = scaleXAnim.value
    val scaleY: Float get() = scaleYAnim.value
    val velocity: Float get() = velocityAnim.value

    fun press() {
        tracker.resetTracking()
        scope.launch {
            launch { pressAnim.animateTo(1f, pressSpec) }
            launch { scaleXAnim.animateTo(pressedScale, scaleXSpec) }
            launch { scaleYAnim.animateTo(pressedScale, scaleYSpec) }
        }
    }

    /** 松手：等位置基本到位再收起，避免透镜还在半路就缩回去 */
    fun release() {
        scope.launch {
            withFrameNanos { }
            if (value != targetValue) {
                val threshold = (valueRange.endInclusive - valueRange.start) * 0.025f
                snapshotFlow { valueAnim.value }
                    .filter { abs(it - valueAnim.targetValue) < threshold }
                    .first()
            }
            launch { pressAnim.animateTo(0f, pressSpec) }
            launch { scaleXAnim.animateTo(initialScale, scaleXSpec) }
            launch { scaleYAnim.animateTo(initialScale, scaleYSpec) }
        }
    }

    /** 拖动中：跟手 */
    fun updateValue(v: Float) {
        val target = v.coerceIn(valueRange)
        scope.launch { valueAnim.animateTo(target, valueSpec) { updateVelocity() } }
    }

    /** 点选：按下 → 滑过去 → 收起，完整走一遍 */
    fun animateToValue(v: Float) {
        scope.launch {
            mutex.mutate {
                press()
                val target = v.coerceIn(valueRange)
                launch { valueAnim.animateTo(target, valueSpec) }
                if (velocity != 0f) launch { velocityAnim.animateTo(0f, velocitySpec) }
                release()
            }
        }
    }

    fun snapTo(v: Float) {
        scope.launch { valueAnim.snapTo(v.coerceIn(valueRange)) }
    }

    private fun updateVelocity() {
        tracker.addPosition(SystemClock.uptimeMillis(), Offset(value, 0f))
        val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(1e-3f)
        val v = tracker.calculateVelocity().x / span
        scope.launch { velocityAnim.animateTo(v, velocitySpec) }
    }
}

/**
 * 玻璃按下时的高光：整体提亮一点，并在手指位置放一团柔光（安卓 13+ 用着色器，低版本只整体提亮）。
 * [modifier] 负责画，[gestureModifier] 负责跟手；[offset] 是手指相对按下点的位移，用来让玻璃微微跟着走。
 */
class InteractiveHighlight(
    private val scope: CoroutineScope,
    private val position: (size: Size, offset: Offset) -> Offset = { _, offset -> offset },
) {
    private val pressSpec = spring(0.5f, 300f, 0.001f)
    private val positionSpec = spring(0.5f, 300f, Offset.VisibilityThreshold)

    private val pressAnim = Animatable(0f, 0.001f)
    private val positionAnim = Animatable(Offset.Zero, Offset.VectorConverter, Offset.VisibilityThreshold)

    private var start = Offset.Zero
    val pressProgress: Float get() = pressAnim.value
    val offset: Offset get() = positionAnim.value - start

    private val shader: RuntimeShader? =
        if (Build.VERSION.SDK_INT >= 33) RuntimeShader(
            """
            uniform float2 size;
            layout(color) uniform half4 color;
            uniform float radius;
            uniform float2 position;
            half4 main(float2 coord) {
                float dist = distance(coord, position);
                float intensity = smoothstep(radius, radius * 0.5, dist);
                return color * intensity;
            }
            """.trimIndent()
        ) else null

    val modifier: Modifier = Modifier.drawWithContent {
        val p = pressAnim.value
        if (p > 0f) {
            val s = shader
            if (s != null && Build.VERSION.SDK_INT >= 33) {
                drawRect(Color.White.copy(0.08f * p), blendMode = BlendMode.Plus)
                val pos = position(size, positionAnim.value)
                s.setFloatUniform("size", size.width, size.height)
                s.setColorUniform("color", Color.White.copy(0.15f * p).toArgb())
                s.setFloatUniform("radius", size.minDimension * 1.5f)
                s.setFloatUniform("position", pos.x.coerceIn(0f, size.width), pos.y.coerceIn(0f, size.height))
                drawRect(ShaderBrush(s), blendMode = BlendMode.Plus)
            } else {
                drawRect(Color.White.copy(0.2f * p), blendMode = BlendMode.Plus)
            }
        }
        drawContent()
    }

    val gestureModifier: Modifier = Modifier.pointerInput(Unit) {
        observeDrag(
            onStart = { down ->
                start = down
                scope.launch {
                    launch { pressAnim.animateTo(1f, pressSpec) }
                    launch { positionAnim.snapTo(down) }
                }
            },
            onMove = { pos -> scope.launch { positionAnim.snapTo(pos) } },
            onEnd = {
                scope.launch {
                    launch { pressAnim.animateTo(0f, pressSpec) }
                    launch { positionAnim.animateTo(start, positionSpec) }
                }
            },
        )
    }
}
