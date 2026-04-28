package se.kvrgic.mediawristremote.phone

import android.graphics.Bitmap
import android.media.MediaMetadata

fun MediaMetadata?.artworkBitmap(): Bitmap? {
    if (this == null) return null
    return getBitmap(MediaMetadata.METADATA_KEY_ART)
        ?: getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
        ?: getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
}
