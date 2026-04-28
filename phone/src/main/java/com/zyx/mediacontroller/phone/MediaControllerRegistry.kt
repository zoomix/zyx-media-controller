package se.kvrgic.mediawristremote.phone

import android.media.session.MediaController
import android.media.session.MediaSession

object MediaControllerRegistry {
    private val lock = Any()

    @Volatile
    var activeController: MediaController? = null

    @Volatile
    private var preferredSessionToken: MediaSession.Token? = null

    private var sessionTokens: List<MediaSession.Token> = emptyList()

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
            sessionTokens = controllers.map { it.sessionToken }
            val preferredToken = preferredSessionToken ?: return@synchronized null
            val controller = controllers.firstOrNull { it.sessionToken == preferredToken }
            if (controller == null) {
                preferredSessionToken = null
            }
            controller
        }
    }

    fun hasPreferredController(): Boolean = preferredSessionToken != null

    fun selectNextController(controllers: List<MediaController>): MediaController? {
        return selectRelativeController(controllers, step = 1)
    }

    fun selectPreviousController(controllers: List<MediaController>): MediaController? {
        return selectRelativeController(controllers, step = -1)
    }

    fun selectSessionAtOneBased(controllers: List<MediaController>, indexOneBased: Int): MediaController? {
        if (controllers.isEmpty()) return null
        val idx = indexOneBased - 1
        if (idx !in controllers.indices) return null
        return synchronized(lock) {
            sessionTokens = controllers.map { it.sessionToken }
            controllers[idx].also { controller ->
                preferredSessionToken = controller.sessionToken
                activeController = controller
            }
        }
    }

    private fun selectRelativeController(
        controllers: List<MediaController>,
        step: Int,
    ): MediaController? {
        if (controllers.isEmpty()) return null
        return synchronized(lock) {
            sessionTokens = controllers.map { it.sessionToken }
            val currentToken = preferredSessionToken ?: activeController?.sessionToken
            val currentIndex = controllers.indexOfFirst { it.sessionToken == currentToken }
                .takeIf { it >= 0 }
                ?: 0
            val nextIndex = (currentIndex + step).floorMod(controllers.size)
            controllers[nextIndex].also { controller ->
                preferredSessionToken = controller.sessionToken
                activeController = controller
            }
        }
    }

    private fun Int.floorMod(modulus: Int): Int {
        return ((this % modulus) + modulus) % modulus
    }
}
