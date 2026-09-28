package com.beiwang.memo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle

/* 第一次提交只用来验证构建链（AGP 9 + Compose + 液态玻璃库），后续提交换成完整界面 */
class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalFoundationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val backdrop = rememberLayerBackdrop()
            val second = rememberLayerBackdrop()
            val combined = rememberCombinedBackdrop(backdrop, second)
            val state = rememberTextFieldState("")
            Box(Modifier.fillMaxSize()) {
                LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize().layerBackdrop(backdrop).background(Color(0xFF0A0B14))
                ) {
                    items(40) { i ->
                        BasicText(
                            "卡片 $i",
                            Modifier.padding(6.dp).background(Color(0x22FFFFFF), RoundedRectangle(16.dp))
                                .combinedClickable(onClick = {}, onLongClick = {}).padding(20.dp)
                        )
                    }
                }
                BasicTextField(state, Modifier.align(Alignment.TopCenter).padding(40.dp))
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(16.dp)
                        .fillMaxWidth()
                        .height(64.dp)
                        .drawBackdrop(
                            backdrop = combined,
                            shape = { Capsule() },
                            effects = {
                                vibrancy()
                                blur(8.dp.toPx())
                                lens(24.dp.toPx(), 24.dp.toPx(), chromaticAberration = true)
                            },
                            highlight = { Highlight.Default },
                            shadow = { Shadow(alpha = 1f) },
                            innerShadow = { InnerShadow(radius = 8.dp) },
                            onDrawSurface = { drawRect(Color(0x33121212)) }
                        )
                )
            }
        }
    }
}
