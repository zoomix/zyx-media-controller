package com.zyx.mediacontroller.phone

import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent

object MediaCommandDispatcher {
    private const val FALLBACK_DELAY_MS = 300L

    private val mainHandler = Handler(Looper.getMainLooper())

    fun dispatch(
        controller: MediaController,
        command: MediaCommand,
        onStateMayHaveChanged: () -> Unit = {},
    ) {
        when (command) {
            MediaCommand.PLAY -> play(controller, onStateMayHaveChanged)
            MediaCommand.PAUSE -> pause(controller, onStateMayHaveChanged)
            MediaCommand.TOGGLE -> toggle(controller, onStateMayHaveChanged)
            MediaCommand.NEXT -> {
                controller.transportControls.skipToNext()
                onStateMayHaveChanged()
            }

            MediaCommand.PREVIOUS -> {
                controller.transportControls.skipToPrevious()
                onStateMayHaveChanged()
            }
        }
    }

    private fun toggle(
        controller: MediaController,
        onStateMayHaveChanged: () -> Unit,
    ) {
        if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
            pause(controller, onStateMayHaveChanged)
        } else {
            play(controller, onStateMayHaveChanged)
        }
    }

    private fun play(
        controller: MediaController,
        onStateMayHaveChanged: () -> Unit,
    ) {
        controller.transportControls.play()
        onStateMayHaveChanged()

        mainHandler.postDelayed({
            if (controller.playbackState?.state != PlaybackState.STATE_PLAYING) {
                dispatchMediaKey(controller, KeyEvent.KEYCODE_MEDIA_PLAY)
                onStateMayHaveChanged()
            }
        }, FALLBACK_DELAY_MS)
    }

    private fun pause(
        controller: MediaController,
        onStateMayHaveChanged: () -> Unit,
    ) {
        controller.transportControls.pause()
        onStateMayHaveChanged()

        mainHandler.postDelayed({
            if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
                dispatchMediaKey(controller, KeyEvent.KEYCODE_MEDIA_PAUSE)
                onStateMayHaveChanged()
            }
        }, FALLBACK_DELAY_MS)
    }

    private fun dispatchMediaKey(controller: MediaController, keyCode: Int) {
        val eventTime = SystemClock.uptimeMillis()
        controller.dispatchMediaButtonEvent(
            KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0),
        )
        controller.dispatchMediaButtonEvent(
            KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0),
        )
    }
}
