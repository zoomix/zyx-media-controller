package se.kvrgic.mediawristremote.wear

import org.json.JSONObject

object MediaProtocol {
    const val commandPath = "/media/command"
    const val statePath = "/media/state"
    const val jsonKey = "json"
}

enum class MediaCommand {
    PLAY,
    PAUSE,
    TOGGLE,
    NEXT,
    PREVIOUS,
    SELECT_NEXT_SESSION,
    SELECT_PREVIOUS_SESSION,
    ;

    fun toPayload(): ByteArray = name.toByteArray(Charsets.UTF_8)
}

enum class MediaConnectionStatus {
    PERMISSION_REQUIRED,
    NO_SESSION,
    READY,
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
    val artworkToken: String = "",
    val artworkBase64: String = "",
) {
    companion object {
        fun fromJson(json: String): MediaStatePayload {
            val payload = JSONObject(json)
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
                artworkBase64 = payload.optString("artworkBase64"),
            )
        }
    }
}
