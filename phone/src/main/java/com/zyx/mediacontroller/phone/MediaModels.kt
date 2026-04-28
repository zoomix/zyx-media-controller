package se.kvrgic.mediawristremote.phone

import android.graphics.Bitmap

data class MediaAppInfo(
    val packageName: String,
    val displayName: String,
)

data class ActiveSessionInfo(
    val id: String,
    val packageName: String,
    val appName: String,
    val title: String,
    val artist: String,
    val stateLabel: String,
    val isSelected: Boolean,
)

data class ArtworkInfo(
    val bitmap: Bitmap?,
    val token: String,
)
