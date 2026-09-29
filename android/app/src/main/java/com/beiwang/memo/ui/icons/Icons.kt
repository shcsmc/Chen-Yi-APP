package com.beiwang.memo.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * 全部图标：24×24 网格，路径手写（没有引用任何第三方图标库）。
 * 画成黑色，用的时候由 ColorFilter.tint 染成需要的颜色。
 *
 * 三种画法：F = 实心（evenodd 挖空）；FS = 实心 + 1.6 圆角描边（把尖角磨圆）；S = 1.9 线条。
 * 预览/修改：路径就是 SVG 的 d 属性，可以直接贴进 <svg viewBox="0 0 24 24"> 里看。
 */
object Icons {

    /** 分类可选的图标（前两个是「笔记」「备忘」自带的），顺序即选择面板里的顺序 */
    val categoryKeys: List<String> = listOf("doc", "list", "star", "heart", "bolt", "bulb", "book", "bag", "work", "home", "plane", "cup", "leaf", "moon", "music", "camera", "gift", "flag")

    fun category(key: String): ImageVector = cat[key] ?: cat.getValue("doc")

    private val cat: Map<String, ImageVector> by lazy {
        mapOf(
            "doc" to icon(F("M7.5 2.8h9a3 3 0 0 1 3 3v12.4a3 3 0 0 1-3 3h-9a3 3 0 0 1-3-3V5.8a3 3 0 0 1 3-3zM8.6 8.2a1 1 0 0 0 0 2h6.8a1 1 0 0 0 0-2zM8.6 12a1 1 0 0 0 0 2h6.8a1 1 0 0 0 0-2zM8.6 15.8a1 1 0 0 0 0 2h3.9a1 1 0 0 0 0-2z")),
            "list" to icon(F("M5.6 4.2a1.9 1.9 0 1 1 0 3.8 1.9 1.9 0 0 1 0-3.8zM10.2 4.95h9.2a1.15 1.15 0 0 1 0 2.3h-9.2a1.15 1.15 0 0 1 0-2.3zM5.6 10.1a1.9 1.9 0 1 1 0 3.8 1.9 1.9 0 0 1 0-3.8zM10.2 10.85h9.2a1.15 1.15 0 0 1 0 2.3h-9.2a1.15 1.15 0 0 1 0-2.3zM5.6 16a1.9 1.9 0 1 1 0 3.8 1.9 1.9 0 0 1 0-3.8zM10.2 16.75h6.2a1.15 1.15 0 0 1 0 2.3h-6.2a1.15 1.15 0 0 1 0-2.3z")),
            "star" to icon(FS("M12 3.4l2.62 5.3 5.86.86-4.24 4.13 1 5.83L12 16.77l-5.24 2.75 1-5.83L3.52 9.56l5.86-.86z")),
            "heart" to icon(FS("M12 20.2C8.4 17.9 3.8 14.3 3.8 9.6a4.3 4.3 0 0 1 8.2-1.9 4.3 4.3 0 0 1 8.2 1.9c0 4.7-4.6 8.3-8.2 10.6z")),
            "bolt" to icon(FS("M13.6 2.9L5.3 13.4h5.9l-1.1 7.7 8.6-10.9h-5.9z")),
            "bulb" to icon(F("M12 2.6a6.9 6.9 0 0 0-4.2 12.4c.7.5 1.1 1.2 1.1 2v.3h6.2V17c0-.8.4-1.5 1.1-2A6.9 6.9 0 0 0 12 2.6zM9 18.6h6v.6a2.2 2.2 0 0 1-2.2 2.2h-1.6A2.2 2.2 0 0 1 9 19.2z")),
            "book" to icon(FS("M3.4 5.3c2.9-1 6-.7 7.9.9v13.4c-2-1.3-5-1.6-7.9-.7zM20.6 5.3c-2.9-1-6-.7-7.9.9v13.4c2-1.3 5-1.6 7.9-.7z")),
            "bag" to icon(F("M6.2 8.2h11.6a1.2 1.2 0 0 1 1.2 1.3l-.8 9.9a2 2 0 0 1-2 1.8H7.8a2 2 0 0 1-2-1.8L5 9.5a1.2 1.2 0 0 1 1.2-1.3z"), S("M8.8 9.6V7.2a3.2 3.2 0 0 1 6.4 0v2.4")),
            "work" to icon(F("M5.5 7h13a2.5 2.5 0 0 1 2.5 2.5v8.5a2.5 2.5 0 0 1-2.5 2.5h-13A2.5 2.5 0 0 1 3 18V9.5A2.5 2.5 0 0 1 5.5 7z"), S("M9 6.8V5.6a1.4 1.4 0 0 1 1.4-1.4h3.2A1.4 1.4 0 0 1 15 5.6v1.2")),
            "home" to icon(FS("M12 3.6l8.2 6.8v8.4a2 2 0 0 1-2 2h-3.3v-4.6a2.9 2.9 0 0 0-5.8 0v4.6H5.8a2 2 0 0 1-2-2v-8.4z")),
            "plane" to icon(FS("M10.6 3.9a1.4 1.4 0 0 1 2.8 0v5.2l7.1 4.4v1.9l-7.1-2.1v4.3l2.1 1.6v1.6L12 19.8l-3.5 1v-1.6l2.1-1.6v-4.3l-7.1 2.1v-1.9l7.1-4.4z")),
            "cup" to icon(F("M4.8 8.3h10.6v6.2a4.7 4.7 0 0 1-4.7 4.7H9.5a4.7 4.7 0 0 1-4.7-4.7z"), S("M15.4 10h1.2a2.3 2.3 0 0 1 0 4.6h-1.3M8 3.4v2.2M11.6 3.4v2.2")),
            "leaf" to icon(F("M19.7 4.3C11.5 4.1 4.8 7.6 4.8 14.4c0 3.4 2.4 5.6 5.8 5.6 6.8 0 9.3-7 9.1-15.7zM7.3 18.9c1.8-4.8 5.1-7.8 9.1-9.7l.5.8c-3.7 2.1-6.5 5-8.2 9.4z")),
            "moon" to icon(FS("M19.9 14.6A8.3 8.3 0 1 1 9.4 4.1a6.6 6.6 0 0 0 10.5 10.5z")),
            "music" to icon(F("M9.3 7.1l10.2-2.8v11.4a3 3 0 1 1-1.9-2.8V8.1l-6.4 1.8v7.9a3 3 0 1 1-1.9-2.8z")),
            "camera" to icon(F("M9.2 4.2h5.6l1.4 2H19a2.2 2.2 0 0 1 2.2 2.2v9.2a2.2 2.2 0 0 1-2.2 2.2H5a2.2 2.2 0 0 1-2.2-2.2V8.4A2.2 2.2 0 0 1 5 6.2h2.8zM12 9.1a3.9 3.9 0 1 0 0 7.8 3.9 3.9 0 0 0 0-7.8zM12 11a2 2 0 1 1 0 4 2 2 0 0 1 0-4z")),
            "gift" to icon(F("M4.4 8.3h6.6v3.6H3.6V9.1a.8.8 0 0 1 .8-.8zM13 8.3h6.6a.8.8 0 0 1 .8.8v2.8H13zM5 13.4h6v7.2H7a2 2 0 0 1-2-2zM13 13.4h6v5.2a2 2 0 0 1-2 2h-4z"), S("M12 7.9c-1.3-2.7-4.8-3.6-4.8-1.6 0 1.2 2.3 1.6 4.8 1.6zm0 0c1.3-2.7 4.8-3.6 4.8-1.6 0 1.2-2.3 1.6-4.8 1.6z")),
            "flag" to icon(S("M5.6 20.6V4.2"), FS("M5.6 4.6c3.6-1.8 6.6 1.6 10.2 0l2.7-1.1v9.6l-2.7 1.1c-3.6 1.6-6.6-1.8-10.2 0z")),
        )
    }

    val search: ImageVector by lazy { icon(S("M10.8 4.2a6.6 6.6 0 1 1 0 13.2 6.6 6.6 0 0 1 0-13.2zM15.6 15.6l4.4 4.4")) }
    val compose: ImageVector by lazy { icon(S("M4.5 19.5h3.9L19 8.9a2.75 2.75 0 0 0-3.9-3.9L4.5 15.6zM13.6 6.5l3.9 3.9")) }
    val gear: ImageVector by lazy { icon(F("M12.00 1.80 L12.25 1.80 L12.50 1.81 L12.75 1.83 L13.00 1.85 L13.25 1.89 L13.49 1.96 L13.69 2.23 L13.77 3.12 L13.79 4.01 L13.93 4.29 L14.11 4.38 L14.29 4.44 L14.48 4.50 L14.66 4.56 L14.84 4.63 L15.02 4.70 L15.20 4.78 L15.38 4.86 L15.55 4.94 L15.72 5.03 L15.90 5.12 L16.09 5.18 L16.38 5.08 L17.03 4.48 L17.71 3.89 L18.05 3.85 L18.27 3.97 L18.47 4.12 L18.66 4.28 L18.85 4.44 L19.03 4.61 L19.21 4.79 L19.39 4.97 L19.56 5.15 L19.72 5.34 L19.88 5.53 L20.03 5.73 L20.15 5.95 L20.11 6.29 L19.52 6.97 L18.92 7.62 L18.82 7.91 L18.88 8.10 L18.97 8.28 L19.06 8.45 L19.14 8.62 L19.22 8.80 L19.30 8.98 L19.37 9.16 L19.44 9.34 L19.50 9.52 L19.56 9.71 L19.62 9.89 L19.71 10.07 L19.99 10.21 L20.88 10.23 L21.77 10.31 L22.04 10.51 L22.11 10.75 L22.15 11.00 L22.17 11.25 L22.19 11.50 L22.20 11.75 L22.20 12.00 L22.20 12.25 L22.19 12.50 L22.17 12.75 L22.15 13.00 L22.11 13.25 L22.04 13.49 L21.77 13.69 L20.88 13.77 L19.99 13.79 L19.71 13.93 L19.62 14.11 L19.56 14.29 L19.50 14.48 L19.44 14.66 L19.37 14.84 L19.30 15.02 L19.22 15.20 L19.14 15.38 L19.06 15.55 L18.97 15.72 L18.88 15.90 L18.82 16.09 L18.92 16.38 L19.52 17.03 L20.11 17.71 L20.15 18.05 L20.03 18.27 L19.88 18.47 L19.72 18.66 L19.56 18.85 L19.39 19.03 L19.21 19.21 L19.03 19.39 L18.85 19.56 L18.66 19.72 L18.47 19.88 L18.27 20.03 L18.05 20.15 L17.71 20.11 L17.03 19.52 L16.38 18.92 L16.09 18.82 L15.90 18.88 L15.72 18.97 L15.55 19.06 L15.38 19.14 L15.20 19.22 L15.02 19.30 L14.84 19.37 L14.66 19.44 L14.48 19.50 L14.29 19.56 L14.11 19.62 L13.93 19.71 L13.79 19.99 L13.77 20.88 L13.69 21.77 L13.49 22.04 L13.25 22.11 L13.00 22.15 L12.75 22.17 L12.50 22.19 L12.25 22.20 L12.00 22.20 L11.75 22.20 L11.50 22.19 L11.25 22.17 L11.00 22.15 L10.75 22.11 L10.51 22.04 L10.31 21.77 L10.23 20.88 L10.21 19.99 L10.07 19.71 L9.89 19.62 L9.71 19.56 L9.52 19.50 L9.34 19.44 L9.16 19.37 L8.98 19.30 L8.80 19.22 L8.62 19.14 L8.45 19.06 L8.28 18.97 L8.10 18.88 L7.91 18.82 L7.62 18.92 L6.97 19.52 L6.29 20.11 L5.95 20.15 L5.73 20.03 L5.53 19.88 L5.34 19.72 L5.15 19.56 L4.97 19.39 L4.79 19.21 L4.61 19.03 L4.44 18.85 L4.28 18.66 L4.12 18.47 L3.97 18.27 L3.85 18.05 L3.89 17.71 L4.48 17.03 L5.08 16.38 L5.18 16.09 L5.12 15.90 L5.03 15.72 L4.94 15.55 L4.86 15.38 L4.78 15.20 L4.70 15.02 L4.63 14.84 L4.56 14.66 L4.50 14.48 L4.44 14.29 L4.38 14.11 L4.29 13.93 L4.01 13.79 L3.12 13.77 L2.23 13.69 L1.96 13.49 L1.89 13.25 L1.85 13.00 L1.83 12.75 L1.81 12.50 L1.80 12.25 L1.80 12.00 L1.80 11.75 L1.81 11.50 L1.83 11.25 L1.85 11.00 L1.89 10.75 L1.96 10.51 L2.23 10.31 L3.12 10.23 L4.01 10.21 L4.29 10.07 L4.38 9.89 L4.44 9.71 L4.50 9.52 L4.56 9.34 L4.63 9.16 L4.70 8.98 L4.78 8.80 L4.86 8.62 L4.94 8.45 L5.03 8.28 L5.12 8.10 L5.18 7.91 L5.08 7.62 L4.48 6.97 L3.89 6.29 L3.85 5.95 L3.97 5.73 L4.12 5.53 L4.28 5.34 L4.44 5.15 L4.61 4.97 L4.79 4.79 L4.97 4.61 L5.15 4.44 L5.34 4.28 L5.53 4.12 L5.73 3.97 L5.95 3.85 L6.29 3.89 L6.97 4.48 L7.62 5.08 L7.91 5.18 L8.10 5.12 L8.28 5.03 L8.45 4.94 L8.62 4.86 L8.80 4.78 L8.98 4.70 L9.16 4.63 L9.34 4.56 L9.52 4.50 L9.71 4.44 L9.89 4.38 L10.07 4.29 L10.21 4.01 L10.23 3.12 L10.31 2.23 L10.51 1.96 L10.75 1.89 L11.00 1.85 L11.25 1.83 L11.50 1.81 L11.75 1.80Z M12 8.7a3.3 3.3 0 1 0 0.001 0Z")) }
    val back: ImageVector by lazy { icon(S("M15 5l-7 7 7 7")) }
    val pin: ImageVector by lazy { icon(S("M9.6 3.8h4.8l-.8 5.1 3.2 3H7.2l3.2-3zM12 11.9v8.3")) }
    val pinFill: ImageVector by lazy { icon(FS("M9.6 3.8h4.8l-.8 5.1 3.2 3H7.2l3.2-3z"), S("M12 11.9v8.3")) }
    val trash: ImageVector by lazy { icon(S("M4.5 7h15M9.3 7V5.2h5.4V7M6.5 7l.9 12.3a1.8 1.8 0 0 0 1.8 1.7h5.6a1.8 1.8 0 0 0 1.8-1.7L17.5 7M10.2 11v5.6M13.8 11v5.6")) }
    val check: ImageVector by lazy { icon(S("M5 12.6l4.6 4.6L19.2 7.4")) }
    val close: ImageVector by lazy { icon(S("M6.5 6.5l11 11M17.5 6.5l-11 11")) }
    val plus: ImageVector by lazy { icon(S("M12 5v14M5 12h14")) }
    val image: ImageVector by lazy { icon(S("M6 4.5h12A2.5 2.5 0 0 1 20.5 7v10a2.5 2.5 0 0 1-2.5 2.5H6A2.5 2.5 0 0 1 3.5 17V7A2.5 2.5 0 0 1 6 4.5zM4 16.5l4.6-4.4 3.7 3.4 2.9-2.6 4.8 4.3M15.2 8.6a1.4 1.4 0 1 1 0 2.8 1.4 1.4 0 0 1 0-2.8z")) }
    val rotate: ImageVector by lazy { icon(S("M4.8 12a7.2 7.2 0 1 0 2.1-5.1M4.6 4.2v3.9h3.9")) }
    val restore: ImageVector by lazy { icon(S("M9 14.5L4.5 10 9 5.5M4.5 10H14a5.5 5.5 0 0 1 0 11h-3")) }
    val export: ImageVector by lazy { icon(S("M12 3.8v11M7.6 10.4L12 14.8l4.4-4.4M4.5 19.8h15")) }
    val import: ImageVector by lazy { icon(S("M12 14.8v-11M7.6 8.2L12 3.8l4.4 4.4M4.5 19.8h15")) }
    val move: ImageVector by lazy { icon(S("M3.8 7a2.2 2.2 0 0 1 2.2-2.2h3.6l2 2.3H18a2.2 2.2 0 0 1 2.2 2.2v7.9a2.2 2.2 0 0 1-2.2 2.2H6a2.2 2.2 0 0 1-2.2-2.2zM9.2 13.2h5.8M12.8 10.9l2.3 2.3-2.3 2.3")) }
    val selectAll: ImageVector by lazy { icon(S("M12 3.8a8.2 8.2 0 1 1 0 16.4 8.2 8.2 0 0 1 0-16.4zM8.3 12.2l2.6 2.6 4.9-5")) }
    val hand: ImageVector by lazy { icon(S("M8 11V5.4a1.4 1.4 0 012.8 0V11M10.8 10.6V4.6a1.4 1.4 0 012.8 0v6M13.6 11V6.2a1.4 1.4 0 012.8 0V14M8 11v3l-1.7-1.7a1.3 1.3 0 00-1.9 1.8l3.4 4.2a5 5 0 004 1.9h1.6a4.6 4.6 0 004.6-4.6V11")) }
    val grid: ImageVector by lazy { icon(S("M6.5 4.5h3A2 2 0 0 1 11.5 6.5v3a2 2 0 0 1-2 2h-3a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2zM14.5 4.5h3a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2h-3a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2zM6.5 12.5h3a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2h-3a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2zM14.5 12.5h3a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2h-3a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2z")) }
    val lock: ImageVector by lazy { icon(S("M7 10.6h10a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2v-6a2 2 0 0 1 2-2zM8.4 10.6V7.8a3.6 3.6 0 0 1 7.2 0v2.8")) }
    val chevron: ImageVector by lazy { icon(S("M9.5 5.5l6.5 6.5-6.5 6.5")) }
    val plusFill: ImageVector by lazy { icon(S("M12 6.2v11.6M6.2 12h11.6")) }

    val safe: ImageVector by lazy { icon(F("M5 3.5h14a2.5 2.5 0 0 1 2.5 2.5v11a2.5 2.5 0 0 1-2.5 2.5H5A2.5 2.5 0 0 1 2.5 17V6A2.5 2.5 0 0 1 5 3.5zM10.5 7.4a4.1 4.1 0 1 0 0 8.2 4.1 4.1 0 0 0 0-8.2zM10.5 9.8a1.7 1.7 0 1 1 0 3.4 1.7 1.7 0 0 1 0-3.4zM17.3 8.3a.9.9 0 0 0-.9.9v4.6a.9.9 0 0 0 1.8 0V9.2a.9.9 0 0 0-.9-.9z"), F("M5.3 19h3.2v1.2a.8.8 0 0 1-.8.8H6.1a.8.8 0 0 1-.8-.8zM15.5 19h3.2v1.2a.8.8 0 0 1-.8.8h-1.6a.8.8 0 0 1-.8-.8z")) }
    val fingerprint: ImageVector by lazy { icon(S("M6.9 18.6A9.2 9.2 0 0 1 5.2 13a6.8 6.8 0 0 1 13.6 0v1.1M9.4 20.5A11 11 0 0 1 8.4 13a3.6 3.6 0 0 1 7.2 0c0 2.6-.4 4.8-1.3 6.9M12 13c0 3.2-.6 5.5-1.8 7.6M4.4 8.5A8.8 8.8 0 0 1 12 4.2a8.8 8.8 0 0 1 7.7 4.4M17.4 17.6c.4-1.2.7-2.5.8-3.9")) }
    val backspace: ImageVector by lazy { icon(S("M9.2 5.5h9.3a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H9.2L3.6 12zM12.2 9.4l5.2 5.2M17.4 9.4l-5.2 5.2")) }
    val transfer: ImageVector by lazy { icon(S("M7.2 4.5v14M3.8 15.2l3.4 3.4 3.4-3.4M16.8 19.5v-14M13.4 8.8l3.4-3.4 3.4 3.4")) }
    val share: ImageVector by lazy { icon(S("M12 3.8v10.8M8.2 7.4L12 3.6l3.8 3.8M7.5 10.8H6.4a2.2 2.2 0 0 0-2.2 2.2v5.2a2.2 2.2 0 0 0 2.2 2.2h11.2a2.2 2.2 0 0 0 2.2-2.2V13a2.2 2.2 0 0 0-2.2-2.2h-1.1")) }
    val wifi: ImageVector by lazy { icon(S("M3.4 9.3a12.6 12.6 0 0 1 17.2 0M6.4 12.6a8.2 8.2 0 0 1 11.2 0M9.4 15.9a3.8 3.8 0 0 1 5.2 0"), F("M12 18.1a1.4 1.4 0 1 1 0 2.8 1.4 1.4 0 0 1 0-2.8z")) }
    val copy: ImageVector by lazy { icon(S("M10.7 8.5h6.6a2.2 2.2 0 0 1 2.2 2.2v6.6a2.2 2.2 0 0 1-2.2 2.2h-6.6a2.2 2.2 0 0 1-2.2-2.2v-6.6a2.2 2.2 0 0 1 2.2-2.2zM15.5 8.5V6.7a2.2 2.2 0 0 0-2.2-2.2H6.7a2.2 2.2 0 0 0-2.2 2.2v6.6a2.2 2.2 0 0 0 2.2 2.2h1.8")) }
    val scan: ImageVector by lazy { icon(S("M4 8.5V6a2 2 0 0 1 2-2h2.5M15.5 4H18a2 2 0 0 1 2 2v2.5M20 15.5V18a2 2 0 0 1-2 2h-2.5M8.5 20H6a2 2 0 0 1-2-2v-2.5M4 12h16")) }
    val qr: ImageVector by lazy { icon(S("M5 4.5h4a.5.5 0 0 1 .5.5v4a.5.5 0 0 1-.5.5H5a.5.5 0 0 1-.5-.5V5a.5.5 0 0 1 .5-.5zM15 4.5h4a.5.5 0 0 1 .5.5v4a.5.5 0 0 1-.5.5h-4a.5.5 0 0 1-.5-.5V5a.5.5 0 0 1 .5-.5zM5 14.5h4a.5.5 0 0 1 .5.5v4a.5.5 0 0 1-.5.5H5a.5.5 0 0 1-.5-.5v-4a.5.5 0 0 1 .5-.5zM14.5 14.5v2.2M19.5 14.5v5h-2.3M14.5 19.5h.01M17 17h.01")) }
    val hotspot: ImageVector by lazy { icon(S("M8.7 15.3a4.7 4.7 0 1 1 6.6 0M5.9 18.1a8.7 8.7 0 1 1 12.2 0"), F("M12 10.4a1.8 1.8 0 1 1 0 3.6 1.8 1.8 0 0 1 0-3.6z")) }
    val info: ImageVector by lazy { icon(S("M12 3.8a8.2 8.2 0 1 1 0 16.4 8.2 8.2 0 0 1 0-16.4zM12 11v5.3"), F("M12 7.1a1.25 1.25 0 1 1 0 2.5 1.25 1.25 0 0 1 0-2.5z")) }
    val lockFill: ImageVector by lazy { icon(F("M7 10.3h10a2.3 2.3 0 0 1 2.3 2.3v6.1A2.3 2.3 0 0 1 17 21H7a2.3 2.3 0 0 1-2.3-2.3v-6.1A2.3 2.3 0 0 1 7 10.3zM12 13.6a1.5 1.5 0 0 0-.8 2.8v1.4a.8.8 0 0 0 1.6 0v-1.4a1.5 1.5 0 0 0-.8-2.8z"), S("M8.3 10.3V7.8a3.7 3.7 0 0 1 7.4 0v2.5")) }
    val palette: ImageVector by lazy { icon(S("M12 3.8c-4.6 0-8.3 3.5-8.3 7.9 0 4.3 3.4 8.5 8 8.5 1.3 0 1.9-.8 1.9-1.7 0-1.3-1.2-1.6-1.2-2.7 0-.9.7-1.5 1.7-1.5h2.2c2.5 0 4-1.6 4-4 0-3.7-3.6-6.5-8.3-6.5z"), F("M7.6 10.8a1.2 1.2 0 1 1 0 2.4 1.2 1.2 0 0 1 0-2.4zM10.3 7.2a1.2 1.2 0 1 1 0 2.4 1.2 1.2 0 0 1 0-2.4zM14.6 7.4a1.2 1.2 0 1 1 0 2.4 1.2 1.2 0 0 1 0-2.4z")) }

    /** 收起键盘：键盘 + 下箭头 */
    val keyboardHide: ImageVector by lazy { icon(S("M5 4.2h14a2 2 0 0 1 2 2v7.3a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6.2a2 2 0 0 1 2-2zM8.6 12.1h6.8M6.9 8.3h.01M9.6 8.3h.01M12.3 8.3h.01M15 8.3h.01M17.4 8.3h.01M9.7 18.5l2.3 2.2 2.3-2.2")) }

    private class Part(val style: Int, val d: String)

    @Suppress("FunctionName") private fun F(d: String) = Part(0, d)
    @Suppress("FunctionName") private fun FS(d: String) = Part(1, d)
    @Suppress("FunctionName") private fun S(d: String) = Part(2, d)

    private fun icon(vararg parts: Part): ImageVector {
        val black = SolidColor(Color.Black)
        val b = ImageVector.Builder(
            defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f
        )
        for (p in parts) {
            val nodes = PathParser().parsePathString(p.d).toNodes()
            when (p.style) {
                0 -> b.addPath(nodes, pathFillType = PathFillType.EvenOdd, fill = black)
                1 -> b.addPath(
                    nodes, pathFillType = PathFillType.EvenOdd, fill = black, stroke = black,
                    strokeLineWidth = 1.6f, strokeLineJoin = StrokeJoin.Round, strokeLineCap = StrokeCap.Round,
                )
                else -> b.addPath(
                    nodes, stroke = black, strokeLineWidth = 1.9f,
                    strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
                )
            }
        }
        return b.build()
    }
}
