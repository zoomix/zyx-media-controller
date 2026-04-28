package se.kvrgic.mediawristremote.phone

import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * Encodes artwork as JPEG + Base64 for [MediaStatePayload] so the watch can show cover art.
 * Keeps payload within Wear [DataItem] practical limits.
 */
object WearMediaArtworkSync {
    private const val MAX_EDGE_PX = 140
    private const val JPEG_QUALITY = 72
    private const val MAX_BASE64_LENGTH = 45_000

    fun encode(bitmap: Bitmap?): String {
        if (bitmap == null || bitmap.isRecycled) return ""
        val scaled = scaleDown(bitmap, MAX_EDGE_PX)
        val bytes = ByteArrayOutputStream().use { out ->
            if (!scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
                return ""
            }
            out.toByteArray()
        }
        if (scaled !== bitmap) {
            scaled.recycle()
        }
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return if (b64.length <= MAX_BASE64_LENGTH) b64 else ""
    }

    private fun scaleDown(source: Bitmap, maxEdge: Int): Bitmap {
        val w = source.width
        val h = source.height
        if (w <= maxEdge && h <= maxEdge) return source
        val scale = maxEdge / maxOf(w, h).toFloat()
        val nw = (w * scale).toInt().coerceAtLeast(1)
        val nh = (h * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, nw, nh, true)
    }
}
