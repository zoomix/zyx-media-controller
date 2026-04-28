package com.zyx.mediacontroller.phone

import android.content.ComponentName
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

class WatchMessageReceiver : WearableListenerService() {
    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path != MediaProtocol.commandPath) {
            super.onMessageReceived(messageEvent)
            return
        }

        val command = MediaCommand.fromPayload(messageEvent.data) ?: return
        val controller = MediaControllerRegistry.activeController ?: activeMediaController() ?: return

        MediaCommandDispatcher.dispatch(
            context = this,
            controller = controller,
            command = command,
            skipButtonMode = PhoneControlPreferences.skipButtonMode(this),
        )
    }

    private fun activeMediaController(): MediaController? {
        if (!NotificationAccess.isEnabled(this)) return null

        val sessionManager = getSystemService(MediaSessionManager::class.java)
        val controllers = runCatching {
            sessionManager.getActiveSessions(ComponentName(this, MediaSessionListenerService::class.java))
        }.getOrDefault(emptyList())

        return MediaControllerRegistry.preferredControllerFrom(controllers)
            ?: controllers
            .filter { controller ->
                val state = controller.playbackState?.state
                state != null && state != PlaybackState.STATE_NONE
            }
            .maxByOrNull(::scoreController)
            ?: controllers.firstOrNull()
    }

    private fun scoreController(controller: MediaController): Int {
        val stateScore = when (controller.playbackState?.state) {
            PlaybackState.STATE_PLAYING -> 100
            PlaybackState.STATE_BUFFERING -> 80
            PlaybackState.STATE_PAUSED -> 60
            else -> 20
        }
        val metadataBonus = if (controller.metadata != null) 10 else 0
        return stateScore + metadataBonus
    }
}
