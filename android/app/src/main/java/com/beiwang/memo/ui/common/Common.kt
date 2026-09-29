package com.beiwang.memo.ui.common

import android.graphics.Bitmap
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import com.beiwang.memo.data.Crypt
import com.beiwang.memo.data.Images
import com.beiwang.memo.ui.theme.LocalPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

/** 版本号（versionName），显示在设置底部。查包信息是系统调用，进程内只查一次 */
fun appVersion(context: android.content.Context): String =
    cachedVersion ?: (runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "")
        .also { cachedVersion = it }

@Volatile private var cachedVersion: String? = null

/** 从 Compose 拿到的 Context 往上找 Activity（设置窗口亮度、屏幕常亮、相机绑定生命周期要用） */
fun android.content.Context.findActivity(): android.app.Activity? {
    var c: android.content.Context? = this
    while (c is android.content.ContextWrapper) {
        if (c is android.app.Activity) return c
        c = c.baseContext
    }
    return null
}

/** 系统震动反馈（跟随系统的触感设置，不需要权限） */
class Haptics(private val view: View) {
    fun tick() = view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    fun longPress() = view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    fun confirm() = view.performHapticFeedback(
        if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
    )
    fun reject() = view.performHapticFeedback(
        if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
    )
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { Haptics(view) }
}

/** 统一的文字：默认主文字色 */
@Composable
fun Txt(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = LocalPalette.current.ink,
    maxLines: Int = Int.MAX_VALUE,
) {
    BasicText(text, modifier, style.copy(color = color), overflow = TextOverflow.Ellipsis, maxLines = maxLines)
}

@Composable
fun Txt(
    text: AnnotatedString,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = LocalPalette.current.ink,
    maxLines: Int = Int.MAX_VALUE,
) {
    BasicText(text, modifier, style.copy(color = color), overflow = TextOverflow.Ellipsis, maxLines = maxLines)
}

/** 异步加载图片（缩略图或原图），先查内存缓存，没有再到 IO 线程解码 */
/** [open] 非空：保险箱里的加密图片，用它解密 */
@Composable
fun rememberImage(images: Images, id: String, thumb: Boolean, open: Crypt? = null): ImageBitmap? {
    val sealed = open != null
    val bmp by produceState<Bitmap?>(images.cached(id, thumb, sealed), id, thumb, sealed) {
        if (value == null) value = withContext(Dispatchers.IO) { runCatching { images.load(id, thumb, open) }.getOrNull() }
    }
    return remember(bmp) { bmp?.asImageBitmap() }
}

/** 列表里的时间：今天 → 时:分；昨天；一周内 → 周几；今年 → 月/日；更早 → 年/月/日 */
fun whenText(ts: Long, now: Long = System.currentTimeMillis()): String {
    val c = Calendar.getInstance().apply { timeInMillis = now }
    c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
    val today = c.timeInMillis
    val day = 86_400_000L
    val t = Calendar.getInstance().apply { timeInMillis = ts }
    return when {
        ts >= today -> "%02d:%02d".format(t.get(Calendar.HOUR_OF_DAY), t.get(Calendar.MINUTE))
        ts >= today - day -> "昨天"
        ts >= today - 6 * day -> arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")[t.get(Calendar.DAY_OF_WEEK) - 1]
        t.get(Calendar.YEAR) == Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.YEAR) ->
            "${t.get(Calendar.MONTH) + 1}/${t.get(Calendar.DAY_OF_MONTH)}"
        else -> "${t.get(Calendar.YEAR) % 100}/${t.get(Calendar.MONTH) + 1}/${t.get(Calendar.DAY_OF_MONTH)}"
    }
}

/**
 * 摘要：没有关键词时取开头；有关键词时截取命中位置前后一段，并把关键词标成强调色。
 */
fun snippet(text: String, keyword: String, max: Int, highlight: Color): AnnotatedString {
    if (keyword.isEmpty()) return AnnotatedString(text.take(max))
    val i = text.indexOf(keyword, ignoreCase = true)
    if (i < 0) return AnnotatedString(text.take(max))
    val a = (i - 18).coerceAtLeast(0)
    val b = (i + keyword.length + 70).coerceAtMost(text.length)
    return buildAnnotatedString {
        if (a > 0) append('…')
        append(text, a, i)
        withStyle(SpanStyle(color = highlight, fontWeight = FontWeight.SemiBold)) { append(text, i, i + keyword.length) }
        append(text, i + keyword.length, b)
        if (b < text.length) append('…')
    }
}
