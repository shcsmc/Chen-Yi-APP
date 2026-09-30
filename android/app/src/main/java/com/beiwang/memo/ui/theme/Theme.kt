package com.beiwang.memo.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * 全局配色。只有两个输入：深/浅 + 强调色（来自背景）。其余颜色都由这两个推出来，
 * 界面里不要写死颜色，一律从 LocalPalette 取。
 */
@Immutable
class Palette(
    val dark: Boolean,
    val accent: Color,
    /** 强调色底上的文字/图标 */
    val onAccent: Color,
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    val card: Color,
    val cardPressed: Color,
    val hairline: Color,
    /** 小玻璃件（按钮、底栏）表面叠的一层色 */
    val glass: Color,
    /** 大面板（设置、回收站、对话框）表面：更实，保证字看得清 */
    val panel: Color,
    /** 安卓 12 以下没有模糊：玻璃退化成接近实色 */
    val glassFallback: Color,
    val danger: Color,
    val base: Color,
    val scrim: Color,
)

fun palette(dark: Boolean, accent: Color): Palette {
    val onAccent = if (accent.luminance() > 0.5f) Color(0xFF111217) else Color.White
    return if (dark) {
        val ink = Color(0xFFF2F3F8)
        Palette(
            dark = true, accent = accent, onAccent = onAccent,
            ink = ink, ink2 = ink.copy(alpha = 0.64f), ink3 = ink.copy(alpha = 0.38f),
            card = Color.White.copy(alpha = 0.075f), cardPressed = Color.White.copy(alpha = 0.13f),
            hairline = Color.White.copy(alpha = 0.10f),
            glass = Color(0xFF16171D).copy(alpha = 0.30f),
            panel = Color(0xFF15161C).copy(alpha = 0.66f),
            glassFallback = Color(0xFF24252D).copy(alpha = 0.94f),
            danger = Color(0xFFFF6B82), base = Color(0xFF0B0C12), scrim = Color.Black.copy(alpha = 0.40f),
        )
    } else {
        val ink = Color(0xFF15171F)
        Palette(
            dark = false, accent = accent, onAccent = onAccent,
            ink = ink, ink2 = ink.copy(alpha = 0.62f), ink3 = ink.copy(alpha = 0.40f),
            card = Color.White.copy(alpha = 0.66f), cardPressed = Color.White.copy(alpha = 0.85f),
            hairline = Color.Black.copy(alpha = 0.06f),
            glass = Color.White.copy(alpha = 0.36f),
            panel = Color(0xFFF7F8FB).copy(alpha = 0.78f),
            glassFallback = Color(0xFFF6F7FA).copy(alpha = 0.96f),
            danger = Color(0xFFE2394F), base = Color(0xFFEEF0F6), scrim = Color.Black.copy(alpha = 0.22f),
        )
    }
}

val LocalPalette = staticCompositionLocalOf { palette(true, Color(0xFF8FA2FF)) }

/** 字号表：全应用只用这几档 */
object Type {
    val largeTitle = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.01.em)
    val title = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp)
    val cardTitle = TextStyle(fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp)
    val body = TextStyle(fontSize = 16.sp, lineHeight = 26.sp)
    val cardBody = TextStyle(fontSize = 13.5.sp, lineHeight = 21.sp)
    val row = TextStyle(fontSize = 15.sp, lineHeight = 21.sp)
    val label = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium)
    val small = TextStyle(fontSize = 11.5.sp)
    val tab = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium)
}
