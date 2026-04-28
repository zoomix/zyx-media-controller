package se.kvrgic.mediawristremote.phone

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import java.util.concurrent.Executors
import android.service.notification.StatusBarNotification
import android.util.Log

class MediaSessionListenerService : NotificationListenerService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sessionManager by lazy { getSystemService(MediaSessionManager::class.java) }

    private var activeController: MediaController? = null

    private val artworkDecodeExecutor = Executors.newSingleThreadExecutor()
    private var statePublishGeneration = 0L
    private var cachedArtworkKey: String = ""
    private var cachedArtworkBase64: String = ""
    private var cachedArtworkToken: String = ""

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
        instance = this
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
        refreshNotificationActions()
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
        instance = null
        clearActiveController()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SYNC_FROM_REGISTRY) {
            mainHandler.post { refreshActiveSession() }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        refreshNotificationActions()
        refreshActiveSession()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        refreshNotificationActions()
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
        controllers.forEach { controller ->
            PhoneControlPreferences.rememberPlayedApp(
                context = this,
                packageName = controller.packageName,
                displayName = appNameFor(controller.packageName),
                observedActions = controller.playbackState?.actions ?: 0L,
            )
        }
    }

    private fun refreshNotificationActions() {
        val notifications = runCatching { activeNotifications }.getOrDefault(emptyArray())
        NotificationActionRegistry.updateFrom(notifications)
    }

    private fun selectActiveController(controllers: List<MediaController>) {
        val nextController = MediaControllerRegistry.preferredControllerFrom(controllers)
            ?: chooseBestController(controllers)

        if (activeController?.sessionToken == nextController?.sessionToken) {
            publishCurrentState()
            return
        }

        activeController?.unregisterCallback(controllerCallback)
        clearArtworkCache()
        activeController = nextController
        MediaControllerRegistry.updateActiveController(nextController)

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

    private fun clearArtworkCache() {
        cachedArtworkKey = ""
        cachedArtworkBase64 = ""
        cachedArtworkToken = ""
    }

    private fun publishCurrentState() {
        val controller = activeController
        if (controller == null) {
            clearArtworkCache()
            MediaStatePublisher.publish(
                context = this,
                payload = MediaStatePayload(connectionStatus = MediaConnectionStatus.NO_SESSION),
            )
            stopPositionUpdates()
            return
        }

        val metadata = controller.metadata
        val playbackState = controller.playbackState
        val sessionToken = controller.sessionToken

        val controllers = runCatching {
            sessionManager.getActiveSessions(ComponentName(this, javaClass))
        }.getOrElse { emptyList() }
        val selectedIndex = controllers.indexOfFirst { it.sessionToken == controller.sessionToken }
            .takeIf { it >= 0 } ?: 0

        val artKey = metadata.artworkCacheKey()
        val gen = ++statePublishGeneration

        fun buildPayload(artworkBase64: String, artworkToken: String): MediaStatePayload {
            val c = activeController ?: controller
            val md = c.metadata
            val pb = c.playbackState
            val ctrls = runCatching {
                sessionManager.getActiveSessions(ComponentName(this, javaClass))
            }.getOrElse { emptyList() }
            val idx = ctrls.indexOfFirst { it.sessionToken == c.sessionToken }.takeIf { it >= 0 } ?: 0
            return MediaStatePayload(
                connectionStatus = MediaConnectionStatus.READY,
                title = md?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
                artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),
                appName = appNameFor(c.packageName),
                appPackageName = c.packageName,
                isPlaying = pb?.state == PlaybackState.STATE_PLAYING,
                duration = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
                position = pb?.position ?: 0L,
                availableActions = pb?.actions ?: 0L,
                sessionIndex = idx + 1,
                sessionCount = ctrls.size,
                artworkToken = artworkToken,
                artworkBase64 = artworkBase64,
            )
        }

        if (artKey == cachedArtworkKey && artKey.isNotEmpty()) {
            val payload = buildPayload(cachedArtworkBase64, cachedArtworkToken)
            MediaStatePublisher.publish(this, payload)
            schedulePositionUpdates(payload.isPlaying)
            return
        }

        artworkDecodeExecutor.execute {
            val bmp = metadata?.artworkBitmap(applicationContext)
            val b64 = WearMediaArtworkSync.encode(bmp)
            val token = bmp?.let { bmpNonNull ->
                "${controller.packageName}:${metadata.hashCode()}:${bmpNonNull.width}x${bmpNonNull.height}"
            }.orEmpty()
            mainHandler.post {
                if (gen != statePublishGeneration) return@post
                if (activeController?.sessionToken != sessionToken) return@post
                cachedArtworkKey = artKey
                cachedArtworkBase64 = b64
                cachedArtworkToken = token
                val payload = buildPayload(b64, token)
                MediaStatePublisher.publish(this@MediaSessionListenerService, payload)
                schedulePositionUpdates(payload.isPlaying)
            }
        }
    }

    private fun clearActiveController() {
        activeController?.unregisterCallback(controllerCallback)
        activeController = null
        clearArtworkCache()
        MediaControllerRegistry.updateActiveController(null)
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

        /** Used when registry session selection changed (wear swipe / phone UI) outside this service. */
        private const val ACTION_SYNC_FROM_REGISTRY = "se.kvrgic.mediawristremote.phone.ACTION_SYNC_FROM_REGISTRY"

        @Volatile
        private var instance: MediaSessionListenerService? = null

        /**
         * Re-applies [MediaControllerRegistry] preferred session to this service’s active controller
         * and media callbacks. Without this, the watch can show the correct session once, then
         * periodic publishes from the old (still playing) session overwrite the UI.
         */
        fun syncActiveSessionFromRegistry(context: Context) {
            val svc = instance
            val app = context.applicationContext
            if (svc != null) {
                svc.mainHandler.post { svc.refreshActiveSession() }
            } else {
                runCatching {
                    app.startService(
                        Intent(app, MediaSessionListenerService::class.java).setAction(ACTION_SYNC_FROM_REGISTRY),
                    )
                }.onFailure { error ->
                    Log.w(logTag, "Could not sync session from registry", error)
                }
            }
        }
    }
}
