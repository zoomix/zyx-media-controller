package com.zyx.mediacontroller.phone

import org.json.JSONObject

object MediaProtocol {
    const val commandPath = "/media/command"
    const val statePath = "/media/state"
    const val jsonKey = "json"
    const val updatedAtKey = "updatedAt"
}

enum class MediaCommand {
    PLAY,
    PAUSE,
    TOGGLE,
    NEXT,
    PREVIOUS,
    ;

    fun toPayload(): ByteArray = name.toByteArray(Charsets.UTF_8)

    companion object {
        fun fromPayload(payload: ByteArray): MediaCommand? {
            val rawValue = payload.toString(Charsets.UTF_8)
            return entries.firstOrNull { it.name == rawValue }
        }
    }
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
    val isPlaying: Boolean = false,
    val duration: Long = 0L,
    val position: Long = 0L,
    val availableActions: Long = 0L,
) {
    fun toJson(): String {
        return JSONObject()
            .put("connectionStatus", connectionStatus.name)
            .put("title", title)
            .put("artist", artist)
            .put("appName", appName)
            .put("isPlaying", isPlaying)
            .put("duration", duration)
            .put("position", position)
            .put("actions", availableActions)
            .toString()
    }
}
