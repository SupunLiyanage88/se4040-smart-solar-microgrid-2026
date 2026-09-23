package com.example.smartsolarmicrogridmobile

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/** Encodes a transaction QR token into a scannable bitmap. Encode-only: no camera dependency. */
fun encodeQrBitmap(content: String, sizePx: Int = 600): Bitmap {
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx)
    return Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565).apply {
        for (x in 0 until sizePx) for (y in 0 until sizePx)
            setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
    }
}
