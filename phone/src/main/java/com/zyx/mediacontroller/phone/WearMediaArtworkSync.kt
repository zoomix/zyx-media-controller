package se.kvrgic.mediawristremote.phone

import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * Two renditions of one cover. The thumbnail rides inline in [MediaStatePayload] so the watch can paint
 * something immediately; the full JPEG goes out as a Wear Asset (deduplicated by content, so it is only
 * transferred once per cover rather than on every playback state change).
 */
class EncodedArtwork(val token: String, val thumbBase64: String, val fullJpeg: ByteArray) {
    companion object {
        val NONE = EncodedArtwork("", "", ByteArray(0))
    }
}

object WearMediaArtworkSync {
    /** Carousel tiles and the main backdrop share this rendition; kept at 320 so small covers are scaled up (see 33bd01b). */
    private const val FULL_EDGE_PX = 320
    private const val FULL_JPEG_QUALITY = 72

    private const val THUMB_EDGE_PX = 64
    private const val THUMB_JPEG_QUALITY = 60

    fun encode(bitmap: Bitmap?, token: String): EncodedArtwork {
        if (bitmap == null || bitmap.isRecycled) return EncodedArtwork.NONE
        val full = compressScaled(bitmap, FULL_EDGE_PX, FULL_JPEG_QUALITY) ?: return EncodedArtwork.NONE
        val thumb = compressScaled(bitmap, THUMB_EDGE_PX, THUMB_JPEG_QUALITY) ?: ByteArray(0)
        return EncodedArtwork(token, Base64.encodeToString(thumb, Base64.NO_WRAP), full)
    }

    private fun compressScaled(source: Bitmap, edge: Int, quality: Int): ByteArray? {
        val scaled = scaleLongestEdgeTo(source, edge)
        val bytes = ByteArrayOutputStream().use { out ->
            if (!scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)) null else out.toByteArray()
        }
        if (scaled !== source) {
            scaled.recycle()
        }
        return bytes
    }

    private fun scaleLongestEdgeTo(source: Bitmap, edge: Int): Bitmap {
        val w = source.width
        val h = source.height
        val longest = maxOf(w, h)
        if (longest == edge) return source
        val scale = edge / longest.toFloat()
        val nw = (w * scale).toInt().coerceAtLeast(1)
        val nh = (h * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, nw, nh, true)
    }
}
