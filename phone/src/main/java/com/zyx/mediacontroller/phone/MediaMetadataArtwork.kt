package se.kvrgic.mediawristremote.phone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.net.Uri
import android.os.Looper
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private val httpArtworkExecutor = Executors.newSingleThreadExecutor()

/** Stable key so we skip re-downloading/decoding artwork when metadata is unchanged (e.g. position ticks). */
fun MediaMetadata?.artworkCacheKey(): String = this?.run {
    buildString {
        append(getString(MediaMetadata.METADATA_KEY_MEDIA_ID))
        append('\u0001')
        append(getString(MediaMetadata.METADATA_KEY_TITLE))
        append('\u0001')
        append(getString(MediaMetadata.METADATA_KEY_ARTIST))
        append('\u0001')
        append(getLong(MediaMetadata.METADATA_KEY_DURATION))
        append('\u0001')
        append(getString(MediaMetadata.METADATA_KEY_ART_URI))
        append('\u0001')
        append(getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI))
        append('\u0001')
        append(getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI))
        append('\u0001')
        val embedded =
            getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        append(embedded?.let { "${it.width}x${it.height}" }.orEmpty())
    }
}.orEmpty()

/**
 * Resolves [MediaMetadata] cover art. Many players only set URI fields
 * ([MediaMetadata.METADATA_KEY_ALBUM_ART_URI], etc.); [MediaMetadata.getBitmap] is null in that case.
 *
 * HTTP(S) artwork is loaded on a background thread (with a short timeout). On the main thread,
 * HTTP loading is skipped to avoid jank; use a non-UI context path for full resolution.
 */
fun MediaMetadata?.artworkBitmap(context: Context): Bitmap? {
    if (this == null) return null
    return getBitmap(MediaMetadata.METADATA_KEY_ART)
        ?: getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
        ?: getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        ?: loadArtworkFromUriString(context, getString(MediaMetadata.METADATA_KEY_ART_URI))
        ?: loadArtworkFromUriString(context, getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI))
        ?: loadArtworkFromUriString(context, getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI))
}

private fun loadArtworkFromUriString(context: Context, uriString: String?): Bitmap? {
    if (uriString.isNullOrBlank()) return null
    val uri = Uri.parse(uriString)
    return when (uri.scheme?.lowercase()) {
        "http", "https" -> loadHttpArtworkIfAllowed(uriString)
        else -> runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input)
            }
        }.getOrNull()
    }
}

private fun loadHttpArtworkIfAllowed(url: String): Bitmap? {
    if (Looper.getMainLooper().isCurrentThread) return null
    val future = httpArtworkExecutor.submit<Bitmap?> {
        runCatching {
            val conn = URL(url).openConnection()
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.getInputStream().use { BitmapFactory.decodeStream(it) }
        }.getOrNull()
    }
    return try {
        future.get(10, TimeUnit.SECONDS)
    } catch (_: Exception) {
        future.cancel(true)
        null
    }
}
