package com.beiwang.memo.ui.theme

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.beiwang.memo.ui.AppState
import com.beiwang.memo.ui.common.Txt
import com.beiwang.memo.ui.glass.GlassButton
import com.beiwang.memo.ui.glass.GlassIconButton
import com.beiwang.memo.ui.glass.LocalBackdrop
import com.beiwang.memo.ui.glass.glass
import com.beiwang.memo.ui.icons.Icons
import com.kyant.shapes.Capsule
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 换背景前的缩放裁剪：整屏显示选中的图，双指缩放、单指拖动，双击复原；屏幕上看到的就是设好后的背景。
 * 图始终铺满屏幕（最小缩放 = 刚好盖住屏幕），不会露出空白边。
 * 手势在取景层（[BgCropContent]）里改这里的状态，悬浮层的「设为背景」（[AppState.applyBackgroundCrop]）读它。
 */
@Stable
class BgCrop(val image: Bitmap) {
    /** 原图像素 → 屏幕像素的缩放；0 = 还没量到屏幕尺寸 */
    var scale by mutableFloatStateOf(0f)
        private set
    /** 原图左上角在屏幕上的位置（像素） */
    var offset by mutableStateOf(Offset.Zero)
        private set
    /** 取景区（整屏）的像素尺寸 */
    var view = IntSize.Zero
        private set

    private fun cover() = max(view.width.toFloat() / image.width, view.height.toFloat() / image.height)

    fun layout(size: IntSize) {
        if (size.width <= 0 || size.height <= 0 || size == view) return
        view = size
        reset()
    }

    /** 回到刚好铺满、居中 */
    fun reset() {
        if (view.width <= 0) return
        scale = cover()
        offset = Offset((view.width - image.width * scale) / 2f, (view.height - image.height * scale) / 2f)
    }

    /** 以两指中点为中心缩放，再平移；缩放和位置都限制在「铺满屏幕」的范围内 */
    fun transform(centroid: Offset, pan: Offset, zoom: Float) {
        if (scale <= 0f) return
        val min = cover()
        val next = (scale * zoom).coerceIn(min, min * MAX_ZOOM)
        val k = next / scale
        scale = next
        offset = clamp(centroid + (offset - centroid) * k + pan)
    }

    private fun clamp(o: Offset): Offset {
        val w = image.width * scale
        val h = image.height * scale
        return Offset(o.x.coerceIn(min(view.width - w, 0f), 0f), o.y.coerceIn(min(view.height - h, 0f), 0f))
    }

    /** 屏幕上看到的那一块在原图里的范围（像素） */
    fun visibleRect(): Rect {
        val l = (-offset.x / scale).roundToInt().coerceIn(0, image.width - 1)
        val t = (-offset.y / scale).roundToInt().coerceIn(0, image.height - 1)
        val r = ((view.width - offset.x) / scale).roundToInt().coerceIn(l + 1, image.width)
        val b = ((view.height - offset.y) / scale).roundToInt().coerceIn(t + 1, image.height)
        return Rect(l, t, r, b)
    }

    companion object {
        /** 解码时的长边上限：放大挑细节够用，内存也不会太大（3000×2250 约 27MB） */
        const val MAX_SIDE = 3000
        /** 最多放大到「刚好铺满」的几倍 */
        const val MAX_ZOOM = 5f
    }
}

/** 取景层：整屏的图 + 手势 */
@Composable
fun BgCropContent(crop: BgCrop) {
    val image = remember(crop) { crop.image.asImageBitmap() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { crop.layout(it) }
            .pointerInput(crop) {
                detectTransformGestures { centroid, pan, zoom, _ -> crop.transform(centroid, pan, zoom) }
            }
            .pointerInput(crop) { detectTapGestures(onDoubleTap = { crop.reset() }) }
            .drawBehind {
                val s = crop.scale
                if (s > 0f) {
                    drawImage(
                        image,
                        dstOffset = IntOffset(crop.offset.x.roundToInt(), crop.offset.y.roundToInt()),
                        dstSize = IntSize((image.width * s).roundToInt(), (image.height * s).roundToInt()),
                        filterQuality = FilterQuality.Medium,
                    )
                }
            }
    )
}

/** 悬浮层：取消 / 提示 / 设为背景 */
@Composable
fun BgCropChrome(app: AppState) {
    val pal = LocalPalette.current
    Box(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(start = 14.dp, end = 14.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassIconButton(Icons.close, onClick = { app.bgCrop = null })
            Spacer(Modifier.width(10.dp))
            // 提示放在玻璃里：图是什么颜色都看得清
            Box(
                Modifier
                    .glass(LocalBackdrop.current, Capsule(), pal.glass, pal.glassFallback, blurRadius = 8.dp, refraction = 8.dp, depth = 14.dp)
                    .padding(horizontal = 14.dp, vertical = 9.dp)
            ) {
                Txt("双指缩放、拖动，双击复原", Type.small, color = pal.ink2, maxLines = 1)
            }
        }
        GlassButton(
            tint = pal.accent,
            onClick = { app.applyBackgroundCrop() },
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 22.dp),
        ) {
            Txt("设为背景", Type.label, color = pal.onAccent, modifier = Modifier.padding(horizontal = 34.dp, vertical = 15.dp))
        }
    }
}
