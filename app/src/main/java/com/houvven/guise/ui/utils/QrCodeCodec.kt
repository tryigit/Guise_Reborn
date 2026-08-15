package com.houvven.guise.ui.utils

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

internal object QrCodeCodec {
    fun encode(text: String, size: Int = DEFAULT_QR_SIZE): BitMatrix {
        require(text.isNotEmpty()) { "QR content is empty" }
        require(size in MIN_QR_SIZE..MAX_QR_SIZE) { "Invalid QR size" }
        return QRCodeWriter().encode(
            text,
            BarcodeFormat.QR_CODE,
            size,
            size,
            mapOf(
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
                EncodeHintType.MARGIN to 1,
            ),
        )
    }

    fun decode(width: Int, height: Int, pixels: IntArray): String {
        require(width > 0 && height > 0 && pixels.size == width * height) {
            "Invalid QR image buffer"
        }
        val source = RGBLuminanceSource(width, height, pixels)
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        return MultiFormatReader().decode(
            bitmap,
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.CHARACTER_SET to "UTF-8",
                DecodeHintType.TRY_HARDER to true,
            ),
        ).text
    }

    const val DEFAULT_QR_SIZE = 768
    private const val MIN_QR_SIZE = 128
    private const val MAX_QR_SIZE = 1536
}
