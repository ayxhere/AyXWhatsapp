package ayx.whatsapp

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Runtime QR generation. The payment strings are only decoded in memory (see Obf) and turned into a
 * QR here — nothing is stored as a scannable image resource, so a decompile/re-mod can't lift them.
 */
object QrGen {
    private val cache = HashMap<String, ImageBitmap?>()
    private const val BLACK = 0xFF000000.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()

    fun make(text: String, sizePx: Int = 360): ImageBitmap? {
        cache[text]?.let { return it }
        val img = runCatching {
            val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 1)
            val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            val w = m.width; val h = m.height
            val px = IntArray(w * h)
            for (y in 0 until h) { val o = y * w; for (x in 0 until w) px[o + x] = if (m.get(x, y)) BLACK else WHITE }
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.setPixels(px, 0, w, 0, 0, w, h) }.asImageBitmap()
        }.getOrNull()
        cache[text] = img
        return img
    }
}
