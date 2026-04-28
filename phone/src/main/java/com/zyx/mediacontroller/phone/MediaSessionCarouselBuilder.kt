package se.kvrgic.mediawristremote.phone

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController

internal object MediaSessionCarouselBuilder {

    fun buildEntries(
        controllers: List<MediaController>,
        context: Context,
        appLabel: (String) -> String,
    ): List<SessionCarouselEntry> =
        controllers.map { controller ->
            val metadata = controller.metadata
            val bitmap = metadata?.artworkBitmap(context)
            val encoded = WearMediaArtworkSync.encodeCarouselTile(bitmap)
            SessionCarouselEntry(
                title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
                artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),
                appName = appLabel(controller.packageName),
                appPackageName = controller.packageName,
                artworkToken = bitmap?.let { bmp ->
                    "${controller.packageName}:${metadata.hashCode()}:${bmp.width}x${bmp.height}"
                }.orEmpty(),
                artworkBase64 = encoded,
            )
        }
}
