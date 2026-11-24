package se.kvrgic.mediawristremote.phone

import org.json.JSONArray
import org.json.JSONObject

object MediaProtocol {
    const val commandPath = "/media/command"
    const val statePath = "/media/state"
    const val artworkPath = "/media/artwork"
    const val jsonKey = "json"
    const val updatedAtKey = "updatedAt"
}

const val SELECT_SESSION_INDEX_PREFIX = "SELECT_SESSION_INDEX:"

enum class MediaCommand {
    PLAY,
    PAUSE,
    TOGGLE,
    NEXT,
    PREVIOUS,
    SELECT_NEXT_SESSION,
    SELECT_PREVIOUS_SESSION,
    SELECT_SESSION_INDEX,
    ;

    fun toPayload(): ByteArray = name.toByteArray(Charsets.UTF_8)

    companion object {
        fun fromPayload(payload: ByteArray): MediaCommand? {
            val rawValue = payload.toString(Charsets.UTF_8)
            if (rawValue.startsWith(SELECT_SESSION_INDEX_PREFIX)) {
                return SELECT_SESSION_INDEX
            }
            return entries.firstOrNull { it.name == rawValue }
        }

        /** 1-based session index matching [MediaStatePayload.sessionIndex]. */
        fun payloadSessionIndexOneBased(payload: ByteArray): Int? {
            val raw = payload.toString(Charsets.UTF_8)
            if (!raw.startsWith(SELECT_SESSION_INDEX_PREFIX)) return null
            return raw.removePrefix(SELECT_SESSION_INDEX_PREFIX).toIntOrNull()
        }
    }
}

enum class MediaConnectionStatus {
    PERMISSION_REQUIRED,
    NO_SESSION,
    READY,
}

data class SessionCarouselEntry(
    val title: String = "",
    val artist: String = "",
    val appName: String = "",
    val appPackageName: String = "",
    val artworkToken: String = "",
    val artworkThumbBase64: String = "",
) {
    fun toJsonObject(): JSONObject =
        JSONObject()
            .put("title", title)
            .put("artist", artist)
            .put("appName", appName)
            .put("appPackageName", appPackageName)
            .put("artworkToken", artworkToken)
            .put("artworkThumb", artworkThumbBase64)
}

data class MediaStatePayload(
    val connectionStatus: MediaConnectionStatus,
    val title: String = "",
    val artist: String = "",
    val appName: String = "",
    val appPackageName: String = "",
    val isPlaying: Boolean = false,
    val duration: Long = 0L,
    val position: Long = 0L,
    val availableActions: Long = 0L,
    val sessionIndex: Int = 0,
    val sessionCount: Int = 0,
    /** Key of the full-resolution cover in the [MediaProtocol.artworkPath] item; empty if no artwork. */
    val artworkToken: String = "",
    /** Tiny JPEG (Base64) shown until the full-resolution asset has loaded. */
    val artworkThumbBase64: String = "",
    /** One entry per active session for wear carousel previews (aligned with active session ordering). */
    val sessionCarousel: List<SessionCarouselEntry> = emptyList(),
) {
    fun toJson(): String {
        val carouselArr = JSONArray()
        sessionCarousel.forEach { entry -> carouselArr.put(entry.toJsonObject()) }

        return JSONObject()
            .put("connectionStatus", connectionStatus.name)
            .put("title", title)
            .put("artist", artist)
            .put("appName", appName)
            .put("appPackageName", appPackageName)
            .put("isPlaying", isPlaying)
            .put("duration", duration)
            .put("position", position)
            .put("actions", availableActions)
            .put("sessionIndex", sessionIndex)
            .put("sessionCount", sessionCount)
            .put("artworkToken", artworkToken)
            .put("artworkThumb", artworkThumbBase64)
            .put("sessionCarousel", carouselArr)
            .toString()
    }
}
