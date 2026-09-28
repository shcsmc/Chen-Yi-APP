package com.beiwang.memo.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.core.graphics.ColorUtils
import java.io.File

/**
 * 自定义背景：相册图缩到屏幕尺寸存成 bg.jpg。
 * 设背景时顺便分析一次图片：整体偏亮 → 浅色界面；再从图里挑最有代表性的鲜艳颜色当强调色。
 */
class Background(private val context: Context) {

    private val file = File(context.filesDir, "bg.jpg")

    /** 读不了图返回 null（不改动当前背景） */
    fun setFromUri(uri: Uri, current: BgPrefs): BgPrefs? = runCatching {
        val bmp = Images.decode(context, uri, MAX_SIDE) ?: return null
        save(bmp, current)
    }.getOrNull()

    fun setFromBytes(bytes: ByteArray, current: BgPrefs): BgPrefs? = runCatching {
        val bmp = Images.decode(bytes, MAX_SIDE) ?: return null
        save(bmp, current)
    }.getOrNull()

    fun clear(current: BgPrefs): BgPrefs {
        file.delete()
        return BgPrefs(custom = false, version = current.version + 1)
    }

    fun load(): Bitmap? = if (file.exists()) BitmapFactory.decodeFile(file.path) else null

    private fun save(bmp: Bitmap, current: BgPrefs): BgPrefs {
        Images.writeJpeg(bmp, file, 90)
        val (accent, light) = analyze(bmp)
        return BgPrefs(custom = true, accent = accent, light = light, version = current.version + 1)
    }

    companion object {
        private const val MAX_SIDE = 2560

        /** 返回（强调色，是否用浅色界面） */
        fun analyze(src: Bitmap): Pair<Int, Boolean> {
            val small = Bitmap.createScaledBitmap(src, 48, 48, true)
            val px = IntArray(48 * 48)
            small.getPixels(px, 0, 48, 0, 0, 48, 48)

            val bins = 24
            val weight = FloatArray(bins)
            val sumR = FloatArray(bins)
            val sumG = FloatArray(bins)
            val sumB = FloatArray(bins)
            val hsv = FloatArray(3)
            var luma = 0f
            for (c in px) {
                val r = Color.red(c) / 255f
                val g = Color.green(c) / 255f
                val b = Color.blue(c) / 255f
                luma += 0.299f * r + 0.587f * g + 0.114f * b
                Color.colorToHSV(c, hsv)
                val s = hsv[1]
                val v = hsv[2]
                if (s < 0.22f || v < 0.22f) continue
                val w = s * s * v
                val bin = ((hsv[0] / 360f) * bins).toInt().coerceIn(0, bins - 1)
                weight[bin] += w
                sumR[bin] += r * w
                sumG[bin] += g * w
                sumB[bin] += b * w
            }
            val light = luma / px.size > 0.6f

            // 相邻两格合并着看，避免同一种颜色被分到两格里而输给别的颜色
            var best = -1
            var bestW = 0f
            for (i in 0 until bins) {
                val w = weight[i] + 0.5f * (weight[(i + 1) % bins] + weight[(i + bins - 1) % bins])
                if (w > bestW) { bestW = w; best = i }
            }
            val total = weight.sum()
            if (best < 0 || total < 12f || weight[best] <= 0f) return (if (light) LIGHT_DEFAULT else DARK_DEFAULT) to light

            val w = weight[best]
            val base = Color.rgb(
                (sumR[best] / w * 255).toInt().coerceIn(0, 255),
                (sumG[best] / w * 255).toInt().coerceIn(0, 255),
                (sumB[best] / w * 255).toInt().coerceIn(0, 255),
            )
            return tune(base, light) to light
        }

        /** 调整到在对应深浅界面上看得清：深色界面用偏亮的，浅色界面用偏深的 */
        fun tune(color: Int, light: Boolean): Int {
            val hsl = FloatArray(3)
            ColorUtils.colorToHSL(color, hsl)
            hsl[1] = hsl[1].coerceIn(0.55f, 0.9f)
            hsl[2] = if (light) hsl[2].coerceIn(0.36f, 0.46f) else hsl[2].coerceIn(0.62f, 0.74f)
            return ColorUtils.HSLToColor(hsl)
        }

        /** 内置背景 / 灰度图时的强调色 */
        const val DARK_DEFAULT = 0xFF8FA2FF.toInt()
        const val LIGHT_DEFAULT = 0xFF3E6BE0.toInt()
    }
}
