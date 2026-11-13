package com.example.qr

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

data class ParsedRoomQr(
    val hostIp: String,
    val wsPort: Int,
    val httpPort: Int,
    val roomCode: String
)

object QrCodeUtils {

    fun buildRoomPayload(hostIp: String, wsPort: Int, httpPort: Int, roomCode: String): String {
        return "syncbeat://v1/$hostIp/$wsPort/$httpPort/$roomCode"
    }

    fun parseRoomPayload(raw: String): ParsedRoomQr? {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("syncbeat://v1/")) return null
        val parts = trimmed.removePrefix("syncbeat://v1/").split("/")
        if (parts.size < 4) return null
        val hostIp = parts[0].trim()
        val wsPort = parts[1].toIntOrNull() ?: return null
        val httpPort = parts[2].toIntOrNull() ?: return null
        val roomCode = parts[3].trim()
        if (hostIp.isBlank() || roomCode.length != 6) return null
        return ParsedRoomQr(hostIp, wsPort, httpPort, roomCode)
    }

    fun generateQrBitmap(content: String, sizePx: Int = 512): Bitmap? {
        if (content.isBlank()) return null
        return runCatching {
            val hints = mapOf(
                EncodeHintType.MARGIN to 1,
                EncodeHintType.CHARACTER_SET to "UTF-8"
            )
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            val pixels = IntArray(sizePx * sizePx)
            val fgColor = Color.parseColor("#0D0D16")
            val bgColor = Color.parseColor("#F8FAFC")
            for (y in 0 until sizePx) {
                val offset = y * sizePx
                for (x in 0 until sizePx) {
                    pixels[offset + x] = if (matrix[x, y]) fgColor else bgColor
                }
            }
            Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx)
            }
        }.getOrNull()
    }
}
