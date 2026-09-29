package com.beiwang.memo.ui.editor

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.beiwang.memo.data.Media
import com.beiwang.memo.ui.AppState
import com.beiwang.memo.ui.common.Txt
import com.beiwang.memo.ui.glass.GlassIconButton
import com.beiwang.memo.ui.glass.Icon
import com.beiwang.memo.ui.glass.LocalBackdrop
import com.beiwang.memo.ui.glass.glass
import com.beiwang.memo.ui.icons.Icons
import com.beiwang.memo.ui.theme.LocalPalette
import com.beiwang.memo.ui.theme.Type
import com.kyant.shapes.Capsule

/**
 * 看视频（取景层）：黑底，画面按视频比例放在中间，点一下画面暂停/继续。
 * 画面用 TextureView：能被悬浮层的玻璃「透过去」看到（SurfaceView 是单独一层，录不进取景层）。
 */
@Composable
fun VideoViewerContent(app: AppState, m: Media) {
    val vault = app.editor?.vault == true
    val playback = remember(m.id) { VideoPlayback(app.store, m, vault) }
    DisposableEffect(playback) {
        app.video = playback
        onDispose {
            if (app.video === playback) app.video = null
            playback.release()
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(playback) { detectTapGestures(onTap = { playback.toggle() }) },
        contentAlignment = Alignment.Center,
    ) {
        val ratio = if (m.w > 0 && m.h > 0) m.w.toFloat() / m.h else 16f / 9f
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val screen = maxWidth / maxHeight
            AndroidView(
                factory = { ctx ->
                    TextureView(ctx).apply {
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            private var surface: Surface? = null
                            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                                surface = Surface(st).also { playback.setSurface(it) }
                            }
                            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {}
                            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                playback.setSurface(null)
                                surface?.release()
                                surface = null
                                return true
                            }
                            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                        }
                    }
                },
                modifier = Modifier.aspectRatio(ratio, matchHeightConstraintsFirst = ratio < screen),
            )
        }
        playback.error?.let {
            Txt(it, Type.row.copy(textAlign = TextAlign.Center), color = Color.White.copy(alpha = 0.8f), modifier = Modifier.padding(40.dp))
        }
    }
}

/** 看视频（悬浮层）：左上关闭；底部一条玻璃：播放/暂停、时间、进度条 */
@Composable
fun VideoViewerChrome(app: AppState) {
    val pal = LocalPalette.current
    val v = app.video
    Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 14.dp, end = 14.dp, top = 6.dp)) {
            GlassIconButton(Icons.close, onClick = { app.viewer = null }, iconTint = Color.White)
        }
        if (v != null) {
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(start = 14.dp, end = 14.dp, bottom = 16.dp)
                    .height(56.dp)
                    .glass(
                        backdrop = LocalBackdrop.current, shape = Capsule(), surface = pal.glass, fallback = pal.glassFallback,
                        blurRadius = 10.dp, refraction = 12.dp, depth = 20.dp,
                    )
                    .padding(start = 6.dp, end = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(pal.accent).clickable { v.toggle() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(if (v.playing) Icons.pause else Icons.play, pal.onAccent, Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Txt(durationText(v.position), Type.small, color = pal.ink2, maxLines = 1)
                Spacer(Modifier.width(10.dp))
                SeekBar(v, Modifier.weight(1f).fillMaxHeight())
                Spacer(Modifier.width(10.dp))
                Txt(durationText(v.duration), Type.small, color = pal.ink2, maxLines = 1)
            }
        }
    }
}

/** 进度条：点哪跳哪；拖动时只挪显示，松手再跳（边拖边跳会卡） */
@Composable
private fun SeekBar(v: VideoPlayback, modifier: Modifier = Modifier) {
    val pal = LocalPalette.current
    var dragging by remember { mutableStateOf<Float?>(null) }
    val fraction = dragging ?: if (v.duration > 0) (v.position.toFloat() / v.duration).coerceIn(0f, 1f) else 0f
    Box(
        modifier
            .pointerInput(v) {
                detectTapGestures { p -> v.seek((p.x / size.width * v.duration).toLong()) }
            }
            .pointerInput(v) {
                detectHorizontalDragGestures(
                    onDragStart = { p -> dragging = (p.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = {
                        dragging?.let { v.seek((it * v.duration).toLong()) }
                        dragging = null
                    },
                    onDragCancel = { dragging = null },
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        dragging = ((dragging ?: fraction) + amount / size.width).coerceIn(0f, 1f)
                    },
                )
            }
            .drawBehind {
                val h = 4.dp.toPx()
                val y = size.height / 2
                val r = CornerRadius(h / 2, h / 2)
                drawRoundRect(pal.ink3, topLeft = Offset(0f, y - h / 2), size = Size(size.width, h), cornerRadius = r)
                drawRoundRect(pal.accent, topLeft = Offset(0f, y - h / 2), size = Size(size.width * fraction, h), cornerRadius = r)
                drawCircle(pal.accent, radius = 7.dp.toPx(), center = Offset(size.width * fraction, y))
            }
    )
}
