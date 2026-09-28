package com.beiwang.memo.ui.sheets

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.beiwang.memo.ui.common.Txt
import com.beiwang.memo.ui.glass.Icon
import com.beiwang.memo.ui.glass.LocalBackdrop
import com.beiwang.memo.ui.glass.glass
import com.beiwang.memo.ui.theme.LocalPalette
import com.beiwang.memo.ui.theme.Type
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.highlight.Highlight
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle

private val PanelShape = RoundedRectangle(30.dp)
private val BlockShape = RoundedRectangle(18.dp)

/**
 * 大块玻璃面板（设置、回收站、对话框）。把自己画出来的样子导出给里面的玻璃按钮当背景，
 * 这样面板里的按钮折射的是面板本身，而不是面板后面的页面。
 */
@Composable
fun GlassPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val pal = LocalPalette.current
    val backdrop = LocalBackdrop.current
    val inner = rememberLayerBackdrop()
    Column(
        modifier.glass(
            backdrop = backdrop, shape = PanelShape, surface = pal.panel, fallback = pal.glassFallback,
            blurRadius = 22.dp, refraction = 16.dp, depth = 30.dp, highlight = Highlight.Plain, exported = inner,
        )
    ) {
        CompositionLocalProvider(LocalBackdrop provides inner) { content() }
    }
}

/**
 * 从底部弹出的面板容器：半透明遮罩（点一下关闭）+ 滑上来的玻璃面板。
 * [content] 在面板关闭动画期间仍会被调用，所以传进来的必须是“最后一次打开的内容”。
 */
@Composable
fun BottomSheet(visible: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val pal = LocalPalette.current
    val maxH = LocalConfiguration.current.screenHeightDp.dp * 0.84f
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier.fillMaxSize().background(pal.scrim)
                    .clickable(interactionSource = null, indication = null, onClick = onDismiss)
            )
        }
        AnimatedVisibility(
            visible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(spring(0.86f, 420f)) { it } + fadeIn(),
            exit = slideOutVertically(spring(1f, 520f)) { it } + fadeOut(),
        ) {
            GlassPanel(
                Modifier
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 10.dp, vertical = 10.dp)
                    .fillMaxWidth()
                    .heightIn(max = maxH)
            ) {
                Column(
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 18.dp, vertical = 18.dp),
                    content = content,
                )
            }
        }
    }
}

/** 面板标题行：大字标题 + 右侧可选的附加内容 */
@Composable
fun PanelTitle(text: String, trailing: @Composable (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(text, Type.title, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
fun SectionTitle(text: String) {
    Txt(text, Type.small, color = LocalPalette.current.ink3, modifier = Modifier.padding(start = 6.dp, top = 14.dp, bottom = 7.dp))
}

/** 一组设置行的圆角底 */
@Composable
fun Block(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(BlockShape).background(LocalPalette.current.card),
        content = content,
    )
}

/** 设置行：图标 + 文字 + 右侧说明/附件；按下时底色加深 */
@Composable
fun SettingRow(
    icon: ImageVector?,
    title: String,
    sub: String? = null,
    color: Color = LocalPalette.current.ink,
    enabled: Boolean = true,
    onClick: (() -> Unit)?,
    trailing: @Composable (() -> Unit)? = null,
) {
    val pal = LocalPalette.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (pressed) pal.cardPressed else Color.Transparent)
            .then(if (onClick != null) Modifier.clickable(source, indication = null, enabled = enabled, onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, if (color == pal.ink) pal.ink2 else color, Modifier.size(20.dp))
            Spacer(Modifier.width(14.dp))
        }
        Txt(title, Type.row, color = color, modifier = Modifier.weight(1f), maxLines = 1)
        if (sub != null) Txt(sub, Type.small, color = pal.ink3, modifier = Modifier.padding(start = 8.dp), maxLines = 1)
        trailing?.invoke()
    }
}

/** 开关：强调色胶囊 + 白色圆点，带弹簧 */
@Composable
fun Toggle(on: Boolean) {
    val pal = LocalPalette.current
    val x by animateDpAsState(if (on) 20.dp else 2.dp, spring(0.7f, 500f), label = "toggle")
    Box(
        Modifier
            .padding(start = 10.dp)
            .size(width = 46.dp, height = 28.dp)
            .clip(Capsule())
            .background(if (on) pal.accent else pal.ink3.copy(alpha = 0.35f))
    ) {
        Box(
            Modifier
                .offset(x = x, y = 2.dp)
                .size(24.dp)
                .clip(Capsule())
                .background(Color.White)
        )
    }
}

@Composable
fun VSpace(h: Int) = Spacer(Modifier.height(h.dp))
