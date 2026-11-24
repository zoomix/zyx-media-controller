package se.kvrgic.mediawristremote.phone

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
        val controllers = activeMediaControllers()

        if (command == MediaCommand.SELECT_SESSION_INDEX) {
            val index = MediaCommand.payloadSessionIndexOneBased(messageEvent.data) ?: return
            MediaControllerRegistry.selectSessionAtOneBased(controllers, index) ?: return
            MediaSessionListenerService.syncActiveSessionFromRegistry(this)
            return
        }

        val controller = when (command) {
            MediaCommand.SELECT_NEXT_SESSION -> {
                MediaControllerRegistry.selectNextController(controllers)
            }

            MediaCommand.SELECT_PREVIOUS_SESSION -> {
                MediaControllerRegistry.selectPreviousController(controllers)
            }

            else -> MediaControllerRegistry.activeController?.takeIf { active -> controllers.any { it.sessionToken == active.sessionToken } }
                ?: activeMediaController(controllers)
        } ?: return

        if (command == MediaCommand.SELECT_NEXT_SESSION || command == MediaCommand.SELECT_PREVIOUS_SESSION) {
            // Keep this service’s active MediaController + callbacks aligned with the registry;
            // otherwise the previously playing session keeps republishing and overrides the watch UI.
            MediaSessionListenerService.syncActiveSessionFromRegistry(this)
            return
        }

        MediaCommandDispatcher.dispatch(
            context = this,
            controller = controller,
            command = command,
            skipButtonMode = PhoneControlPreferences.skipButtonMode(this, controller.packageName),
        )
    }

    private fun activeMediaControllers(): List<MediaController> {
        if (!NotificationAccess.isEnabled(this)) return emptyList()

        val sessionManager = getSystemService(MediaSessionManager::class.java)
        return runCatching {
            sessionManager.getActiveSessions(ComponentName(this, MediaSessionListenerService::class.java))
        }.getOrDefault(emptyList()).visibleOnWatch(this)
    }

    private fun activeMediaController(controllers: List<MediaController> = activeMediaControllers()): MediaController? {
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
