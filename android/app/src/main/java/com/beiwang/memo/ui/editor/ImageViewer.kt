package com.beiwang.memo.ui.editor

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.beiwang.memo.data.Media
import com.beiwang.memo.ui.AppState
import com.beiwang.memo.ui.common.rememberImage
import com.beiwang.memo.ui.glass.GlassIconButton
import com.beiwang.memo.ui.icons.Icons
import kotlinx.coroutines.launch

/**
 * 看大图：双指缩放/平移，双击在 1 倍和 2.5 倍之间切换，旋转按钮每次转 90°。
 * 先显示缩略图，原图解码好了再换上，打开不等待。
 */
@Composable
fun ImageViewerContent(app: AppState, image: Media) {
    val open = if (app.editor?.vault == true) app.store.vault::openBytes else null
    val full = rememberImage(app.store.images, image.id, thumb = false, open = open)
    val thumb = rememberImage(app.store.images, image.id, thumb = true, open = open)
    val scope = rememberCoroutineScope()
    val scale = remember(image.id) { Animatable(1f) }
    val offset = remember(image.id) { Animatable(Offset.Zero, Offset.VectorConverter) }
    val rotation = remember(image.id) { Animatable(0f) }
    LaunchedEffect(app.viewerTurns) { rotation.animateTo(app.viewerTurns * 90f, spring(0.8f, 300f)) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.94f))
            .pointerInput(image.id) {
                detectTapGestures(onDoubleTap = { tap ->
                    scope.launch {
                        if (scale.value > 1.05f) {
                            launch { scale.animateTo(1f, spring(0.8f, 400f)) }
                            launch { offset.animateTo(Offset.Zero, spring(0.8f, 400f)) }
                        } else {
                            val center = Offset(size.width / 2f, size.height / 2f)
                            launch { scale.animateTo(2.5f, spring(0.8f, 400f)) }
                            launch { offset.animateTo((center - tap) * 1.5f, spring(0.8f, 400f)) }
                        }
                    }
                })
            }
            .pointerInput(image.id) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scope.launch {
                        val s = (scale.value * zoom).coerceIn(0.6f, 8f)
                        scale.snapTo(s)
                        offset.snapTo(offset.value + pan)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val bmp = full ?: thumb
        if (bmp != null) {
            Image(
                bmp, null,
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale.value
                        scaleY = scale.value
                        translationX = offset.value.x
                        translationY = offset.value.y
                        rotationZ = rotation.value
                    },
                contentScale = ContentScale.Fit,
            )
        }
    }
}

@Composable
fun ImageViewerChrome(app: AppState) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 14.dp, end = 14.dp, top = 6.dp)) {
        GlassIconButton(Icons.close, onClick = { app.viewer = null }, iconTint = Color.White)
        Spacer(Modifier.weight(1f))
        GlassIconButton(Icons.rotate, onClick = { app.viewerTurns += 1 }, iconTint = Color.White)
    }
}
