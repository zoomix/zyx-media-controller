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

    /** Encoded covers keyed by [artworkCacheId]. Main thread only. */
    private val artworkCache = HashMap<String, EncodedArtwork>()
    private val pendingArtworkIds = HashSet<String>()
    private var lastPublishedArtworkTokens: Set<String>? = null

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
        mainHandler.removeCallbacks(notificationDebounceRunnable)
        clearActiveController()
        publishPermissionStateIfNeeded()
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(notificationDebounceRunnable)
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
        scheduleActiveSessionRefresh()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        refreshNotificationActions()
        scheduleActiveSessionRefresh()
    }

    private fun scheduleActiveSessionRefresh() {
        mainHandler.removeCallbacks(notificationDebounceRunnable)
        mainHandler.postDelayed(notificationDebounceRunnable, NOTIFICATION_DEBOUNCE_MS)
    }

    private val notificationDebounceRunnable = Runnable { refreshActiveSession() }

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
        activeController = nextController
        MediaControllerRegistry.updateActiveController(nextController)

        if (nextController == null) {
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
        artworkCache.clear()
    }

    private fun activeSessions(): List<MediaController> = runCatching {
        sessionManager.getActiveSessions(ComponentName(this, javaClass))
    }.getOrElse { emptyList() }

    private fun artworkCacheId(controller: MediaController): String {
        val key = controller.metadata.artworkCacheKey()
        return if (key.isEmpty()) "" else "${controller.packageName}\u0001$key"
    }

    private fun cachedArtwork(controller: MediaController): EncodedArtwork =
        artworkCache[artworkCacheId(controller)] ?: EncodedArtwork.NONE

    /**
     * Publishes text and playback state right away with whatever artwork is already encoded, then encodes
     * missing covers in the background and republishes as each one lands. The watch therefore shows the
     * session immediately, a thumbnail shortly after and the full-resolution cover last.
     */
    private fun publishCurrentState() {
        val controller = activeController
        if (controller == null) {
            clearArtworkCache()
            MediaStatePublisher.publish(
                context = this,
                payload = MediaStatePayload(connectionStatus = MediaConnectionStatus.NO_SESSION),
            )
            return
        }

        val controllers = activeSessions()
        // Active session first so its cover is encoded before the carousel tiles.
        val artworkControllers = (listOf(controller) + if (controllers.size > 1) controllers else emptyList()).distinctBy { it.sessionToken }
        val neededIds = HashSet<String>()
        for (ctrl in artworkControllers) {
            val id = artworkCacheId(ctrl)
            if (id.isNotEmpty()) neededIds.add(id)
        }
        artworkCache.keys.retainAll(neededIds)
        for (ctrl in artworkControllers) {
            requestArtwork(ctrl)
        }

        val metadata = controller.metadata
        val playback = controller.playbackState
        val activeArt = cachedArtwork(controller)
        val sessionIndex = controllers.indexOfFirst { it.sessionToken == controller.sessionToken }.takeIf { it >= 0 } ?: 0
        val carousel = if (controllers.size > 1) controllers.map(::carouselEntry) else emptyList()
        MediaStatePublisher.publish(
            context = this,
            payload = MediaStatePayload(
                connectionStatus = MediaConnectionStatus.READY,
                title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
                artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),
                appName = appNameFor(controller.packageName),
                appPackageName = controller.packageName,
                isPlaying = playback?.state == PlaybackState.STATE_PLAYING,
                duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
                position = playback?.position ?: 0L,
                availableActions = playback?.actions ?: 0L,
                sessionIndex = sessionIndex + 1,
                sessionCount = controllers.size,
                artworkToken = activeArt.token,
                artworkThumbBase64 = activeArt.thumbBase64,
                sessionCarousel = carousel,
            ),
        )
        publishArtworkAssetsIfChanged()
    }

    private fun carouselEntry(controller: MediaController): SessionCarouselEntry {
        val metadata = controller.metadata
        val art = cachedArtwork(controller)
        return SessionCarouselEntry(
            title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
            artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),
            appName = appNameFor(controller.packageName),
            appPackageName = controller.packageName,
            artworkToken = art.token,
            artworkThumbBase64 = art.thumbBase64,
        )
    }

    private fun requestArtwork(controller: MediaController) {
        val id = artworkCacheId(controller)
        if (id.isEmpty() || id in artworkCache || id in pendingArtworkIds) return
        pendingArtworkIds.add(id)
        val metadata = controller.metadata
        val token = "${controller.packageName}:${Integer.toHexString(id.hashCode())}"
        artworkDecodeExecutor.execute {
            val encoded = WearMediaArtworkSync.encode(metadata?.artworkBitmap(applicationContext), token)
            mainHandler.post {
                pendingArtworkIds.remove(id)
                artworkCache[id] = encoded
                if (activeController != null) publishCurrentState()
            }
        }
    }

    /** The asset item only changes when the set of covers does, so playback updates never resend image bytes. */
    private fun publishArtworkAssetsIfChanged() {
        val jpegByToken = HashMap<String, ByteArray>()
        for (art in artworkCache.values) {
            if (art.token.isNotEmpty() && art.fullJpeg.isNotEmpty()) jpegByToken[art.token] = art.fullJpeg
        }
        if (jpegByToken.keys == lastPublishedArtworkTokens) return
        lastPublishedArtworkTokens = jpegByToken.keys.toSet()
        MediaStatePublisher.publishArtwork(this, jpegByToken)
    }

    private fun clearActiveController() {
        activeController?.unregisterCallback(controllerCallback)
        activeController = null
        clearArtworkCache()
        MediaControllerRegistry.updateActiveController(null)
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
        private const val NOTIFICATION_DEBOUNCE_MS = 1_000L

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
