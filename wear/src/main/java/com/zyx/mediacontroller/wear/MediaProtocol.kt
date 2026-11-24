package se.kvrgic.mediawristremote.wear

import org.json.JSONArray
import org.json.JSONObject

object MediaProtocol {
    const val commandPath = "/media/command"
    const val statePath = "/media/state"
    const val artworkPath = "/media/artwork"
    const val jsonKey = "json"
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
)

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
    val artworkToken: String = "",
    val artworkThumbBase64: String = "",
    val sessionCarousel: List<SessionCarouselEntry> = emptyList(),
) {
    companion object {
        fun fromJson(json: String): MediaStatePayload {
            val payload = JSONObject(json)
            val carouselJson = payload.optJSONArray("sessionCarousel") ?: JSONArray()
            val carousel = buildList {
                for (index in 0 until carouselJson.length()) {
                    val tile = carouselJson.optJSONObject(index) ?: continue
                    add(
                        SessionCarouselEntry(
                            title = tile.optString("title"),
                            artist = tile.optString("artist"),
                            appName = tile.optString("appName"),
                            appPackageName = tile.optString("appPackageName"),
                            artworkToken = tile.optString("artworkToken"),
                            artworkThumbBase64 = tile.optString("artworkThumb"),
                        ),
                    )
                }
            }
            return MediaStatePayload(
                connectionStatus = MediaConnectionStatus.valueOf(
                    payload.optString("connectionStatus", MediaConnectionStatus.NO_SESSION.name),
                ),
                title = payload.optString("title"),
                artist = payload.optString("artist"),
                appName = payload.optString("appName"),
                appPackageName = payload.optString("appPackageName"),
                isPlaying = payload.optBoolean("isPlaying"),
                duration = payload.optLong("duration"),
                position = payload.optLong("position"),
                availableActions = payload.optLong("actions"),
                sessionIndex = payload.optInt("sessionIndex"),
                sessionCount = payload.optInt("sessionCount"),
                artworkToken = payload.optString("artworkToken"),
                artworkThumbBase64 = payload.optString("artworkThumb"),
                sessionCarousel = carousel,
            )
        }
    }
}
