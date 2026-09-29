package com.beiwang.memo.ui.sheets

import android.graphics.Bitmap
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.beiwang.memo.data.Qr
import com.beiwang.memo.ui.common.Txt
import com.beiwang.memo.ui.common.findActivity
import com.beiwang.memo.ui.theme.LocalPalette
import com.beiwang.memo.ui.theme.Type
import com.kyant.shapes.RoundedRectangle
import java.util.concurrent.Executors

/*
 * 二维码必须是黑白的：扫码靠明暗对比识别，深色主题下反色或带颜色都会让很多手机扫不出来。
 * 所以这里是全应用唯一不从 LocalPalette 取颜色的地方。
 */
private const val QR_DARK = 0xFF000000.toInt()
private const val QR_LIGHT = 0xFFFFFFFF.toInt()

/** 显示二维码：白底圆角卡片，四周留足空白（扫码器需要） */
@Composable
fun QrImage(text: String, modifier: Modifier = Modifier) {
    val image = remember(text) {
        val m = Qr.encode(text)
        val quiet = 3
        val n = m.size + quiet * 2
        val pixels = IntArray(n * n) { i ->
            val y = i / n - quiet
            val x = i % n - quiet
            if (y in m.indices && x in m.indices && m[y][x]) QR_DARK else QR_LIGHT
        }
        Bitmap.createBitmap(pixels, n, n, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    Box(modifier.clip(RoundedRectangle(20.dp)).background(Color(QR_LIGHT)).padding(10.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            // 一个模块一个像素，放大时不插值（FilterQuality.None），边缘锐利
            drawImage(image, dstSize = IntSize(size.width.toInt(), size.height.toInt()), filterQuality = FilterQuality.None)
        }
    }
}

/**
 * 相机取景 + 识别二维码（CameraX + ZXing，不依赖谷歌服务）。每认出一个码就回调一次 [onText]（在主线程），
 * 是不是自己要的码由调用方判断。调用前要已经拿到相机权限。
 */
@Composable
fun QrScanner(onText: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val pal = LocalPalette.current
    val latest by rememberUpdatedState(onText)
    var error by remember { mutableStateOf<String?>(null) }
    val preview = remember {
        PreviewView(context).apply {
            // TextureView 实现：能被圆角裁剪，也能和玻璃面板正常叠在一起
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val owner = context.findActivity() as? LifecycleOwner

    DisposableEffect(owner) {
        if (owner == null) {
            error = "相机打不开"
            return@DisposableEffect onDispose { }
        }
        val main = ContextCompat.getMainExecutor(context)
        val worker = Executors.newSingleThreadExecutor()
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        var disposed = false
        future.addListener({
            if (disposed) return@addListener
            val p = runCatching { future.get() }.getOrNull()
            if (p == null) {
                error = "相机打不开"
                return@addListener
            }
            provider = p
            val usePreview = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                        )
                        .build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)   // 识别慢时丢掉旧帧，只看最新的
                .build()
            val reader = Qr.Reader()
            var buffer = ByteArray(0)
            analysis.setAnalyzer(worker) { image ->
                val text = try {
                    val plane = image.planes[0]              // Y 平面 = 亮度，识别二维码只要它
                    val src = plane.buffer
                    val n = src.remaining()
                    if (buffer.size != n) buffer = ByteArray(n)
                    src.get(buffer, 0, n)
                    reader.decode(buffer, plane.rowStride, image.width, image.height)
                } catch (e: Exception) {
                    null
                } finally {
                    image.close()
                }
                if (text != null) main.execute { if (!disposed) latest(text) }
            }
            try {
                p.unbindAll()
                p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, usePreview, analysis)
            } catch (e: Exception) {
                error = "相机打不开：${e.message ?: e.javaClass.simpleName}"
            }
        }, main)
        onDispose {
            disposed = true
            runCatching { provider?.unbindAll() }
            worker.shutdown()
        }
    }

    Box(modifier.clip(RoundedRectangle(24.dp)).background(pal.card), contentAlignment = Alignment.Center) {
        AndroidView(factory = { preview }, modifier = Modifier.fillMaxSize())
        // 取景框：四个角
        Canvas(Modifier.fillMaxSize().padding(28.dp)) {
            val l = size.minDimension * 0.16f
            val w = 4.dp.toPx()
            val c = pal.accent
            val corners = listOf(
                Offset(0f, 0f) to (1f to 1f), Offset(size.width, 0f) to (-1f to 1f),
                Offset(0f, size.height) to (1f to -1f), Offset(size.width, size.height) to (-1f to -1f),
            )
            for ((o, d) in corners) {
                drawLine(c, o, Offset(o.x + d.first * l, o.y), w, StrokeCap.Round)
                drawLine(c, o, Offset(o.x, o.y + d.second * l), w, StrokeCap.Round)
            }
        }
        error?.let {
            Txt(
                it, Type.row.copy(textAlign = TextAlign.Center), color = pal.danger,
                modifier = Modifier.padding(24.dp),
            )
        }
    }
}

