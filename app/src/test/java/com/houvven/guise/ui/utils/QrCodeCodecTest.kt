package com.houvven.guise.ui.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class QrCodeCodecTest {

    @Test
    fun qrPayloadRoundTripsWithoutAndroidBitmap() {
        val payload = "{\"schemaVersion\":1,\"templates\":[{\"id\":\"demo\"}]}"
        val matrix = QrCodeCodec.encode(payload, 256)
        val pixels = IntArray(matrix.width * matrix.height)
        var offset = 0
        repeat(matrix.height) { y ->
            repeat(matrix.width) { x ->
                pixels[offset++] = if (matrix[x, y]) BLACK else WHITE
            }
        }

        assertEquals(payload, QrCodeCodec.decode(matrix.width, matrix.height, pixels))
    }

    private companion object {
        const val BLACK = -0x1000000
        const val WHITE = -0x1
    }
}
