package com.zyx.mediacontroller.phone

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class MediaSessionListenerService : NotificationListenerService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sessionManager by lazy { getSystemService(MediaSessionManager::class.java) }

    private var activeController: MediaController? = null

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            publishCurrentState()
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            publishCurrentState()
        }

        override fun onSessionDestroyed() {
            refreshActiveSession()
        }
    }

    private val positionUpdater = object : Runnable {
        override fun run() {
            publishCurrentState()
            val isPlaying = activeController?.playbackState?.state == PlaybackState.STATE_PLAYING
            if (isPlaying) {
                mainHandler.postDelayed(this, POSITION_UPDATE_DELAY_MS)
            }
        }
    }

    private val activeSessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            selectActiveController(controllers ?: emptyList())
        }

    override fun onCreate() {
        super.onCreate()
        publishPermissionStateIfNeeded()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        runCatching {
            sessionManager.addOnActiveSessionsChangedListener(
                activeSessionsChangedListener,
                ComponentName(this, javaClass),
            )
        }.onFailure { error ->
            Log.w(logTag, "Unable to register active session listener", error)
        }
        refreshActiveSession()
    }

    override fun onListenerDisconnected() {
        clearActiveController()
        publishPermissionStateIfNeeded()
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        runCatching {
            sessionManager.removeOnActiveSessionsChangedListener(activeSessionsChangedListener)
        }
        clearActiveController()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        refreshActiveSession()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        refreshActiveSession()
    }

    private fun refreshActiveSession() {
        if (!NotificationAccess.isEnabled(this)) {
            clearActiveController()
            publishPermissionStateIfNeeded()
            return
        }

        val controllers = runCatching {
            sessionManager.getActiveSessions(ComponentName(this, javaClass))
        }.getOrElse { error ->
            Log.w(logTag, "Unable to query active sessions", error)
            emptyList()
        }

        selectActiveController(controllers)
    }

    private fun selectActiveController(controllers: List<MediaController>) {
        val nextController = chooseBestController(controllers)

        if (activeController?.sessionToken == nextController?.sessionToken) {
            publishCurrentState()
            return
        }

        activeController?.unregisterCallback(controllerCallback)
        activeController = nextController
        MediaControllerRegistry.activeController = nextController

        if (nextController == null) {
            stopPositionUpdates()
            MediaStatePublisher.publish(
                context = this,
                payload = MediaStatePayload(connectionStatus = MediaConnectionStatus.NO_SESSION),
            )
            return
        }

        nextController.registerCallback(controllerCallback)
        publishCurrentState()
    }

    private fun publishCurrentState() {
        val controller = activeController
        if (controller == null) {
            MediaStatePublisher.publish(
                context = this,
                payload = MediaStatePayload(connectionStatus = MediaConnectionStatus.NO_SESSION),
            )
            stopPositionUpdates()
            return
        }

        val metadata = controller.metadata
        val playbackState = controller.playbackState

        val payload = MediaStatePayload(
            connectionStatus = MediaConnectionStatus.READY,
            title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
            artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),
            appName = appNameFor(controller.packageName),
            isPlaying = playbackState?.state == PlaybackState.STATE_PLAYING,
            duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
            position = playbackState?.position ?: 0L,
            availableActions = playbackState?.actions ?: 0L,
        )

        MediaStatePublisher.publish(this, payload)
        schedulePositionUpdates(payload.isPlaying)
    }

    private fun clearActiveController() {
        activeController?.unregisterCallback(controllerCallback)
        activeController = null
        MediaControllerRegistry.activeController = null
        stopPositionUpdates()
    }

    private fun schedulePositionUpdates(isPlaying: Boolean) {
        stopPositionUpdates()
        if (isPlaying) {
            mainHandler.postDelayed(positionUpdater, POSITION_UPDATE_DELAY_MS)
        }
    }

    private fun stopPositionUpdates() {
        mainHandler.removeCallbacks(positionUpdater)
    }

    private fun publishPermissionStateIfNeeded() {
        MediaStatePublisher.publish(
            context = this,
            payload = MediaStatePayload(connectionStatus = MediaConnectionStatus.PERMISSION_REQUIRED),
        )
    }

    private fun chooseBestController(controllers: List<MediaController>): MediaController? {
        return controllers
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

    private fun appNameFor(packageName: String): String {
        return runCatching {
            val applicationInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(applicationInfo).toString()
        }.getOrDefault(packageName)
    }

    companion object {
        private const val logTag = "MediaSessionBridge"
        private const val POSITION_UPDATE_DELAY_MS = 1_000L
    }
}
