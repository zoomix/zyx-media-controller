package com.zyx.mediacontroller.phone

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
        val controller = MediaControllerRegistry.activeController ?: return
        val controls = controller.transportControls

        when (command) {
            MediaCommand.PLAY -> controls.play()
            MediaCommand.PAUSE -> controls.pause()
            MediaCommand.TOGGLE -> {
                if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
                    controls.pause()
                } else {
                    controls.play()
                }
            }

            MediaCommand.NEXT -> controls.skipToNext()
            MediaCommand.PREVIOUS -> controls.skipToPrevious()
        }
    }
}
