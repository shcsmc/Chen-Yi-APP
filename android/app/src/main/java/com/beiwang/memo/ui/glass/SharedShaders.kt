/*
 * 改编自 Kyant0/AndroidLiquidGlass（backdrop/effects/Lens.kt、backdrop/highlight/HighlightStyle.kt、
 * backdrop/internal/Shaders.kt，版本 2.0.1），Apache License 2.0，Copyright 2025 Kyant。
 * 改动：着色器整个进程只编译一次、所有玻璃件共用。原版每个玻璃件各编译一份折射和棱边高光着色器，
 * 一排玻璃按钮同时出现时（保险箱数字键盘、进入多选、打开编辑页、设置切页）第一帧要编译一堆着色器，会卡一下。
 *
 * 共用是安全的：每次用之前先设好参数，而生成 RenderEffect、录制绘制时系统会把当时的参数拷一份，
 * 之后再改参数不影响已经生成的效果；这些都只发生在主线程上。
 */
package com.beiwang.memo.ui.glass

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.RuntimeShader
import com.kyant.backdrop.RuntimeShaderCache
import com.kyant.backdrop.asAndroidRuntimeShader
import com.kyant.backdrop.effects.effect
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.shapes.RoundedRectangularShape
import kotlin.math.PI

/**
 * 边缘折射，参数含义同 backdrop 的 lens()。只支持圆角矩形/胶囊（本应用只用这两种形状）。
 * 必须放在 blur() 之后调用（和原版一样：模糊留出的边距要按折射带宽扣掉）。
 */
fun BackdropEffectScope.sharedLens(
    refractionHeight: Float,
    refractionAmount: Float,
    chromaticAberration: Boolean = false,
) {
    if (Build.VERSION.SDK_INT < 33) return
    if (refractionHeight <= 0f || refractionAmount <= 0f) return
    if (padding > 0f) padding = (padding - refractionHeight).coerceAtLeast(0f)
    val c = (shape as? RoundedRectangularShape)?.corners(size, layoutDirection, this) ?: return
    val shader = if (chromaticAberration) SharedShaders.dispersion else SharedShaders.refraction
    shader.setFloatUniform("size", size.width, size.height)
    shader.setFloatUniform("offset", -padding, -padding)
    shader.setFloatUniform("cornerRadii", floatArrayOf(c.topLeft, c.topRight, c.bottomRight, c.bottomLeft))
    shader.setFloatUniform("refractionHeight", refractionHeight)
    shader.setFloatUniform("refractionAmount", -refractionAmount)
    shader.setFloatUniform("depthEffect", 0f)
    if (chromaticAberration) shader.setFloatUniform("chromaticAberration", 1f)
    effect(
        android.graphics.RenderEffect.createRuntimeShaderEffect(shader.asAndroidRuntimeShader(), "content")
            .asComposeRenderEffect()
    )
}

/** 玻璃件的棱边高光：和 Highlight.Default 一样，只是着色器共用 */
val GlassHighlight = Highlight(style = SharedHighlightStyle)

@Immutable
object SharedHighlightStyle : HighlightStyle {
    override val color: Color = Color.White.copy(alpha = 0.5f)
    override val blendMode: BlendMode = BlendMode.Plus

    override fun DrawScope.createShader(shape: Shape, runtimeShaderCache: RuntimeShaderCache): RuntimeShader? {
        if (Build.VERSION.SDK_INT < 33) return null
        // 原版拿到的形状不是 CornerBasedShape 时四个角一律按胶囊算；本应用的形状都走这条，照原样
        val r = size.minDimension / 2f
        return SharedShaders.highlight.apply {
            setFloatUniform("size", size.width, size.height)
            setFloatUniform("cornerRadii", floatArrayOf(r, r, r, r))
            setColorUniform("color", Color.White)
            setFloatUniform("angle", 45f * (PI / 180f).toFloat())
            setFloatUniform("falloff", 1f)
        }
    }
}

/** 按下玻璃时手指处那团柔光（见 InteractiveHighlight） */
@RequiresApi(33)
internal fun pressGlowShader(): android.graphics.RuntimeShader = SharedShaders.pressGlow

@RequiresApi(33)
private object SharedShaders {
    val refraction: RuntimeShader by lazy { RuntimeShader(REFRACTION) }
    val dispersion: RuntimeShader by lazy { RuntimeShader(DISPERSION) }
    val highlight: RuntimeShader by lazy { RuntimeShader(HIGHLIGHT) }
    val pressGlow: android.graphics.RuntimeShader by lazy { android.graphics.RuntimeShader(PRESS_GLOW) }
}

// ---------------- 着色器源码（AGSL，照抄 backdrop 2.0.1） ----------------

private const val ROUNDED_RECT_SDF = """
float radiusAt(float2 coord, float4 radii) {
    if (coord.x >= 0.0) {
        if (coord.y <= 0.0) return radii.y;
        else return radii.z;
    } else {
        if (coord.y <= 0.0) return radii.x;
        else return radii.w;
    }
}

float sdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    float outside = length(max(cornerCoord, 0.0)) - radius;
    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);
    return outside + inside;
}

float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {
        return sign(coord) * normalize(max(cornerCoord, 0.0));
    } else {
        float gradX = step(cornerCoord.y, cornerCoord.x);
        return sign(coord) * float2(gradX, 1.0 - gradX);
    }
}"""

private const val REFRACTION = """
uniform shader content;

uniform float2 size;
uniform float2 offset;
uniform float4 cornerRadii;
uniform float refractionHeight;
uniform float refractionAmount;
uniform float depthEffect;

$ROUNDED_RECT_SDF

float circleMap(float x) {
    return 1.0 - sqrt(1.0 - x * x);
}

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centeredCoord = (coord + offset) - halfSize;
    float radius = radiusAt(coord, cornerRadii);

    float sd = sdRoundedRect(centeredCoord, halfSize, radius);
    if (-sd >= refractionHeight) {
        return content.eval(coord);
    }
    sd = min(sd, 0.0);

    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;
    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = normalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius) + depthEffect * normalize(centeredCoord));

    float2 refractedCoord = coord + d * grad;
    return content.eval(refractedCoord);
}"""

private const val DISPERSION = """
uniform shader content;

uniform float2 size;
uniform float2 offset;
uniform float4 cornerRadii;
uniform float refractionHeight;
uniform float refractionAmount;
uniform float depthEffect;
uniform float chromaticAberration;

$ROUNDED_RECT_SDF

float circleMap(float x) {
    return 1.0 - sqrt(1.0 - x * x);
}

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centeredCoord = (coord + offset) - halfSize;
    float radius = radiusAt(coord, cornerRadii);

    float sd = sdRoundedRect(centeredCoord, halfSize, radius);
    if (-sd >= refractionHeight) {
        return content.eval(coord);
    }
    sd = min(sd, 0.0);

    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;
    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = normalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius) + depthEffect * normalize(centeredCoord));

    float2 refractedCoord = coord + d * grad;
    float dispersionIntensity = chromaticAberration * ((centeredCoord.x * centeredCoord.y) / (halfSize.x * halfSize.y));
    float2 dispersedCoord = d * grad * dispersionIntensity;

    half4 color = half4(0.0);

    half4 red = content.eval(refractedCoord + dispersedCoord);
    color.r += red.r / 3.5;
    color.a += red.a / 7.0;

    half4 orange = content.eval(refractedCoord + dispersedCoord * (2.0 / 3.0));
    color.r += orange.r / 3.5;
    color.g += orange.g / 7.0;
    color.a += orange.a / 7.0;

    half4 yellow = content.eval(refractedCoord + dispersedCoord * (1.0 / 3.0));
    color.r += yellow.r / 3.5;
    color.g += yellow.g / 3.5;
    color.a += yellow.a / 7.0;

    half4 green = content.eval(refractedCoord);
    color.g += green.g / 3.5;
    color.a += green.a / 7.0;

    half4 cyan = content.eval(refractedCoord - dispersedCoord * (1.0 / 3.0));
    color.g += cyan.g / 3.5;
    color.b += cyan.b / 3.0;
    color.a += cyan.a / 7.0;

    half4 blue = content.eval(refractedCoord - dispersedCoord * (2.0 / 3.0));
    color.b += blue.b / 3.0;
    color.a += blue.a / 7.0;

    half4 purple = content.eval(refractedCoord - dispersedCoord);
    color.r += purple.r / 7.0;
    color.b += purple.b / 3.0;
    color.a += purple.a / 7.0;

    return color;
}"""

private const val HIGHLIGHT = """
uniform float2 size;
uniform float4 cornerRadii;
layout(color) uniform half4 color;
uniform float angle;
uniform float falloff;

$ROUNDED_RECT_SDF

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centeredCoord = coord - halfSize;
    float radius = radiusAt(coord, cornerRadii);

    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = gradSdRoundedRect(centeredCoord, halfSize, gradRadius);
    float2 normal = float2(cos(angle), sin(angle));
    float d = dot(grad, normal);
    float intensity = pow(abs(d), falloff);
    return color * intensity;
}"""

private const val PRESS_GLOW = """
uniform float2 size;
layout(color) uniform half4 color;
uniform float radius;
uniform float2 position;
half4 main(float2 coord) {
    float dist = distance(coord, position);
    float intensity = smoothstep(radius, radius * 0.5, dist);
    return color * intensity;
}"""
