package com.zyx.mediacontroller.phone

import android.media.session.MediaController
import android.media.session.MediaSession

object MediaControllerRegistry {
    private val lock = Any()

    @Volatile
    var activeController: MediaController? = null

    @Volatile
    private var preferredSessionToken: MediaSession.Token? = null

    fun selectPreferredController(controller: MediaController) {
        synchronized(lock) {
            preferredSessionToken = controller.sessionToken
            activeController = controller
        }
    }

    fun clearPreferredController() {
        synchronized(lock) {
            preferredSessionToken = null
        }
    }

    fun updateActiveController(controller: MediaController?) {
        synchronized(lock) {
            activeController = controller
        }
    }

    fun preferredControllerFrom(controllers: List<MediaController>): MediaController? {
        return synchronized(lock) {
            val preferredToken = preferredSessionToken ?: return@synchronized null
            val controller = controllers.firstOrNull { it.sessionToken == preferredToken }
            if (controller == null) {
                preferredSessionToken = null
            }
            controller
        }
    }

    fun hasPreferredController(): Boolean = preferredSessionToken != null
}
