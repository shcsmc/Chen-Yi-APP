package com.beiwang.memo.data

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder

/**
 * 二维码的生成和识别（ZXing，纯 Java，不依赖谷歌服务，国产手机也能用）。不含界面，可单元测试。
 */
object Qr {

    /** 生成二维码的模块矩阵：[y][x] = true 为深色块。不含外面的空白边（画的时候自己留） */
    fun encode(text: String): Array<BooleanArray> {
        val code = Encoder.encode(text, ErrorCorrectionLevel.M, mapOf(EncodeHintType.CHARACTER_SET to "UTF-8"))
        val m = code.matrix
        return Array(m.height) { y -> BooleanArray(m.width) { x -> m.get(x, y).toInt() == 1 } }
    }

    /**
     * 识别一帧相机画面里的二维码（只用亮度，也就是 YUV 的 Y 平面）。
     * [rowStride] 是每行在数组里占的字节数（可能比 [width] 大）。没认出来返回 null。
     * 一个 Reader 实例不要在多个线程同时用。
     */
    class Reader {
        private val reader = QRCodeReader()
        private val hints = mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.TRY_HARDER to true,
        )

        fun decode(luminance: ByteArray, rowStride: Int, width: Int, height: Int): String? {
            if (width <= 0 || height <= 0 || rowStride < width || luminance.size < rowStride * (height - 1) + width) return null
            val source = PlanarYUVLuminanceSource(luminance, rowStride, height, 0, 0, width, height, false)
            return try {
                reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text
            } catch (e: ReaderException) {
                null                      // 这一帧没认出来（最常见），等下一帧
            } catch (e: RuntimeException) {
                null                      // 画面里有残缺的码时 ZXing 偶尔会抛别的异常：一样当没认出来
            } finally {
                reader.reset()
            }
        }
    }
}
