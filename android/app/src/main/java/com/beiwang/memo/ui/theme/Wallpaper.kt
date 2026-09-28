package com.beiwang.memo.ui.theme

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.beiwang.memo.data.Background
import com.beiwang.memo.data.BgPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 整页背景。内置背景是静态的几团柔和色光（不做动画，省电也不拖累玻璃）；
 * 自定义背景铺满裁切，上面压一层很淡的遮罩保证字看得清。
 */
@Composable
fun Wallpaper(bg: BgPrefs, background: Background, modifier: Modifier = Modifier) {
    val pal = LocalPalette.current
    if (!bg.custom) {
        DefaultWallpaper(pal.dark, modifier)
        return
    }
    val bmp by produceState<Bitmap?>(null, bg.version) {
        value = withContext(Dispatchers.IO) { background.load() }
    }
    val image = remember(bmp) { bmp?.asImageBitmap() }
    Box(modifier.fillMaxSize().background(pal.base)) {
        if (image != null) {
            Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Box(Modifier.fillMaxSize().background(if (pal.dark) Color.Black.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.10f)))
    }
}

@Composable
private fun DefaultWallpaper(dark: Boolean, modifier: Modifier) {
    val base = if (dark) Color(0xFF0B0C12) else Color(0xFFEEF0F6)
    val blobs = if (dark) listOf(
        Blob(Color(0xFF5B5BFF), 0.12f, 0.06f, 0.95f, 0.55f),
        Blob(Color(0xFFFF4F8B), 1.02f, 0.58f, 0.80f, 0.30f),
        Blob(Color(0xFF19D3C5), 0.10f, 1.00f, 0.90f, 0.28f),
    ) else listOf(
        Blob(Color(0xFF7488FF), 0.10f, 0.05f, 0.95f, 0.36f),
        Blob(Color(0xFFFF88B2), 1.02f, 0.55f, 0.80f, 0.26f),
        Blob(Color(0xFF56D6CC), 0.12f, 1.00f, 0.90f, 0.30f),
    )
    Box(
        modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(base)
                for (b in blobs) {
                    val center = Offset(size.width * b.x, size.height * b.y)
                    val r = size.maxDimension * 0.5f * b.r
                    drawCircle(
                        Brush.radialGradient(
                            0f to b.color.copy(alpha = b.a),
                            0.45f to b.color.copy(alpha = b.a * 0.45f),
                            1f to b.color.copy(alpha = 0f),
                            center = center, radius = r,
                        ),
                        radius = r, center = center,
                    )
                }
            }
    )
}

private class Blob(val color: Color, val x: Float, val y: Float, val r: Float, val a: Float)
