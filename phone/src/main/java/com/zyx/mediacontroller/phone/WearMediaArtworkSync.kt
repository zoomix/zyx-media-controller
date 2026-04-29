package se.kvrgic.mediawristremote.phone

import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * Encodes artwork as JPEG + Base64 for [MediaStatePayload] so the watch can show cover art.
 * Keeps payload within Wear [DataItem] practical limits.
 */
object WearMediaArtworkSync {
    private const val MAX_EDGE_PX = 320
    private const val JPEG_QUALITY = 72
    private const val MAX_BASE64_LENGTH = 100_000

    /** Compact tiles for session carousel previews (many sessions in one Wear DataItem payload). */
    private const val CAROUSEL_MIN_EDGE_PX = 320
    private const val CAROUSEL_JPEG_QUALITY = 72
    private const val CAROUSEL_MAX_BASE64_LENGTH = 14_000

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

    fun encodeCarouselTile(bitmap: Bitmap?): String {
        if (bitmap == null || bitmap.isRecycled) return ""
        val scaled = scaleForCarousel(bitmap)
        val bytes = ByteArrayOutputStream().use { out ->
            if (!scaled.compress(Bitmap.CompressFormat.JPEG, CAROUSEL_JPEG_QUALITY, out)) {
                return ""
            }
            out.toByteArray()
        }
        if (scaled !== bitmap) {
            scaled.recycle()
        }
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return if (b64.length <= CAROUSEL_MAX_BASE64_LENGTH) b64 else ""
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

    /**
     * Ensures the bitmap is at least [CAROUSEL_MIN_EDGE_PX] on its longest side.
     * Scale down if needed, scale up if the source is smaller.
     */
    private fun scaleForCarousel(source: Bitmap): Bitmap {
        val w = source.width
        val h = source.height
        val longest = maxOf(w, h)
        val shortest = minOf(w, h)
        if (longest >= CAROUSEL_MIN_EDGE_PX && shortest >= CAROUSEL_MIN_EDGE_PX) {
            // Already meets minimum; scale down if too large
            return scaleDown(source, CAROUSEL_MIN_EDGE_PX)
        }
        // Scale up to at least CAROUSEL_MIN_EDGE_PX on the longest side
        val scale = CAROUSEL_MIN_EDGE_PX.toFloat() / longest
        val nw = (w * scale).toInt()
        val nh = (h * scale).toInt()
        return Bitmap.createScaledBitmap(source, nw, nh, true)
    }
}
