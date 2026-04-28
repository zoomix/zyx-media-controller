package se.kvrgic.mediawristremote.phone

import android.content.Context
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent

object MediaCommandDispatcher {
    private const val FALLBACK_DELAY_MS = 300L
    private const val SEEK_FALLBACK_DELAY_MS = 800L
    private const val SEEK_DETECTION_THRESHOLD_MS = 5_000L

    private val mainHandler = Handler(Looper.getMainLooper())

    fun dispatch(
        context: Context? = null,
        controller: MediaController,
        command: MediaCommand,
        skipButtonMode: SkipButtonMode = SkipButtonMode.TRACK,
        onStateMayHaveChanged: () -> Unit = {},
    ) {
        CommandDiagnostics.record("${command.name} requested for ${controller.packageName}")
        when (command) {
            MediaCommand.PLAY -> play(controller, onStateMayHaveChanged)
            MediaCommand.PAUSE -> pause(controller, onStateMayHaveChanged)
            MediaCommand.TOGGLE -> toggle(controller, onStateMayHaveChanged)
            MediaCommand.NEXT -> next(context, controller, skipButtonMode, onStateMayHaveChanged)
            MediaCommand.PREVIOUS -> previous(context, controller, skipButtonMode, onStateMayHaveChanged)
            MediaCommand.SELECT_NEXT_SESSION,
            MediaCommand.SELECT_PREVIOUS_SESSION,
            MediaCommand.SELECT_SESSION_INDEX,
            -> onStateMayHaveChanged()
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
        CommandDiagnostics.record("PLAY -> transportControls.play()")
        onStateMayHaveChanged()

        mainHandler.postDelayed({
            if (controller.playbackState?.state != PlaybackState.STATE_PLAYING) {
                dispatchMediaKey(controller, KeyEvent.KEYCODE_MEDIA_PLAY)
                CommandDiagnostics.record("PLAY -> media key fallback")
                onStateMayHaveChanged()
            }
        }, FALLBACK_DELAY_MS)
    }

    private fun pause(
        controller: MediaController,
        onStateMayHaveChanged: () -> Unit,
    ) {
        controller.transportControls.pause()
        CommandDiagnostics.record("PAUSE -> transportControls.pause()")
        onStateMayHaveChanged()

        mainHandler.postDelayed({
            if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
                dispatchMediaKey(controller, KeyEvent.KEYCODE_MEDIA_PAUSE)
                CommandDiagnostics.record("PAUSE -> media key fallback")
                onStateMayHaveChanged()
            }
        }, FALLBACK_DELAY_MS)
    }

    private fun previous(
        context: Context?,
        controller: MediaController,
        skipButtonMode: SkipButtonMode,
        onStateMayHaveChanged: () -> Unit,
    ) {
        if (skipButtonMode == SkipButtonMode.SEEK) {
            controller.seekBack(context, onStateMayHaveChanged)
        } else {
            controller.transportControls.skipToPrevious()
            CommandDiagnostics.record("PREVIOUS -> transportControls.skipToPrevious()")
        }
        onStateMayHaveChanged()
    }

    private fun next(
        context: Context?,
        controller: MediaController,
        skipButtonMode: SkipButtonMode,
        onStateMayHaveChanged: () -> Unit,
    ) {
        if (skipButtonMode == SkipButtonMode.SEEK) {
            controller.seekForward(context, onStateMayHaveChanged)
        } else {
            controller.transportControls.skipToNext()
            CommandDiagnostics.record("NEXT -> transportControls.skipToNext()")
        }
        onStateMayHaveChanged()
    }

    private fun MediaController.seekBack(
        context: Context?,
        onStateMayHaveChanged: () -> Unit,
    ) {
        val positionBefore = playbackState?.position
        val notificationAction = context?.let {
            NotificationActionRegistry.sendSeekBack(it, packageName)
        }
        if (notificationAction != null) {
            CommandDiagnostics.record("PREVIOUS -> notification action: $notificationAction")
            scheduleSeekMediaKeyFallback(
                controller = this,
                keyCode = KeyEvent.KEYCODE_MEDIA_REWIND,
                direction = SeekDirection.BACKWARD,
                positionBefore = positionBefore,
                onStateMayHaveChanged = onStateMayHaveChanged,
            )
            return
        }

        val customAction = playbackState?.customActions
            .orEmpty()
            .firstOrNull { action ->
                action.matchesAny("rewind", "seek_backward", "seek_back", "skip_backward", "skip_back")
            }

        if (customAction != null) {
            transportControls.sendCustomAction(customAction.action, null)
            CommandDiagnostics.record("PREVIOUS -> custom action: ${customAction.name} (${customAction.action})")
        } else if (hasAction(PlaybackState.ACTION_REWIND)) {
            transportControls.rewind()
            CommandDiagnostics.record("PREVIOUS -> transportControls.rewind()")
        } else {
            transportControls.skipToPrevious()
            CommandDiagnostics.record("PREVIOUS -> fallback transportControls.skipToPrevious()")
        }

        scheduleSeekMediaKeyFallback(
            controller = this,
            keyCode = KeyEvent.KEYCODE_MEDIA_REWIND,
            direction = SeekDirection.BACKWARD,
            positionBefore = positionBefore,
            onStateMayHaveChanged = onStateMayHaveChanged,
        )
    }

    private fun MediaController.seekForward(
        context: Context?,
        onStateMayHaveChanged: () -> Unit,
    ) {
        val positionBefore = playbackState?.position
        val notificationAction = context?.let {
            NotificationActionRegistry.sendSeekForward(it, packageName)
        }
        if (notificationAction != null) {
            CommandDiagnostics.record("NEXT -> notification action: $notificationAction")
            scheduleSeekMediaKeyFallback(
                controller = this,
                keyCode = KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                direction = SeekDirection.FORWARD,
                positionBefore = positionBefore,
                onStateMayHaveChanged = onStateMayHaveChanged,
            )
            return
        }

        val customAction = playbackState?.customActions
            .orEmpty()
            .firstOrNull { action ->
                action.matchesAny("fast_forward", "forward", "seek_forward", "seek_ahead", "skip_forward", "skip_ahead")
            }

        if (customAction != null) {
            transportControls.sendCustomAction(customAction.action, null)
            CommandDiagnostics.record("NEXT -> custom action: ${customAction.name} (${customAction.action})")
        } else if (hasAction(PlaybackState.ACTION_FAST_FORWARD)) {
            transportControls.fastForward()
            CommandDiagnostics.record("NEXT -> transportControls.fastForward()")
        } else {
            transportControls.skipToNext()
            CommandDiagnostics.record("NEXT -> fallback transportControls.skipToNext()")
        }

        scheduleSeekMediaKeyFallback(
            controller = this,
            keyCode = KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            direction = SeekDirection.FORWARD,
            positionBefore = positionBefore,
            onStateMayHaveChanged = onStateMayHaveChanged,
        )
    }

    private fun scheduleSeekMediaKeyFallback(
        controller: MediaController,
        keyCode: Int,
        direction: SeekDirection,
        positionBefore: Long?,
        onStateMayHaveChanged: () -> Unit,
    ) {
        mainHandler.postDelayed({
            val positionAfter = controller.playbackState?.position
            if (!looksLikeSeekMoved(direction, positionBefore, positionAfter)) {
                dispatchMediaKey(controller, keyCode)
                CommandDiagnostics.record("${direction.commandLabel} -> media key fallback")
                onStateMayHaveChanged()
            }
        }, SEEK_FALLBACK_DELAY_MS)
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

    private fun MediaController.hasAction(action: Long): Boolean {
        return playbackState?.actions?.let { actions -> actions and action != 0L } == true
    }

    private fun PlaybackState.CustomAction.matchesAny(vararg needles: String): Boolean {
        val haystack = listOf(action, name?.toString().orEmpty())
            .joinToString(separator = " ")
            .lowercase()
        return needles.any { needle -> haystack.contains(needle) }
    }

    private fun looksLikeSeekMoved(
        direction: SeekDirection,
        positionBefore: Long?,
        positionAfter: Long?,
    ): Boolean {
        if (positionBefore == null || positionAfter == null) return false
        val delta = positionAfter - positionBefore
        return when (direction) {
            SeekDirection.BACKWARD -> delta < -SEEK_DETECTION_THRESHOLD_MS
            SeekDirection.FORWARD -> delta > SEEK_DETECTION_THRESHOLD_MS
        }
    }

    private enum class SeekDirection(val commandLabel: String) {
        BACKWARD("PREVIOUS"),
        FORWARD("NEXT"),
    }
}
