package se.kvrgic.mediawristremote.wear

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.Wearable
import kotlin.text.Charsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PlayerUiState(
    val title: String = "",
    val artist: String = "",
    val appName: String = "",
    val appPackageName: String = "",
    val isPlaying: Boolean = false,
    val duration: Long = 0L,
    val position: Long = 0L,
    val availableActions: Long = 0L,
    val sessionIndex: Int = 0,
    val sessionCount: Int = 0,
    val isPhoneReachable: Boolean = false,
    val hasLoadedState: Boolean = false,
    val connectionStatus: MediaConnectionStatus? = null,
    val artworkToken: String = "",
    val sessionCarousel: List<SessionCarouselEntry> = emptyList(),
    /** Best cover decoded so far per artwork token: the inline thumbnail first, replaced by the full asset once loaded. */
    val artwork: Map<String, Bitmap> = emptyMap(),
)

class MediaControlViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val nodeClient = Wearable.getNodeClient(appContext)
    private val messageClient = Wearable.getMessageClient(appContext)
    private val dataClient = Wearable.getDataClient(appContext)
    private val wearDataListener = WearDataListener(appContext, ::handleIncomingState, ::handleIncomingArtwork)

    // MARK: Artwork (main thread only)
    private var thumbBase64ByToken: Map<String, String> = emptyMap()
    private var assetsByToken: Map<String, Asset> = emptyMap()
    private val fullResTokens = HashSet<String>()
    private val loadingThumbs = HashSet<String>()
    private val loadingFull = HashSet<String>()

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    fun startListening() {
        wearDataListener.start()
        refreshConnection()
    }

    fun stopListening() {
        wearDataListener.stop()
    }

    fun refreshConnection() {
        nodeClient.connectedNodes
            .addOnSuccessListener { nodes ->
                _uiState.value = _uiState.value.copy(isPhoneReachable = nodes.isNotEmpty())
            }
            .addOnFailureListener {
                _uiState.value = _uiState.value.copy(isPhoneReachable = false)
            }
    }

    fun togglePlayback() {
        sendCommand(MediaCommand.TOGGLE)
    }

    fun skipNext() {
        sendCommand(MediaCommand.NEXT)
    }

    fun skipPrevious() {
        sendCommand(MediaCommand.PREVIOUS)
    }

    fun selectNextSession() {
        sendCommand(MediaCommand.SELECT_NEXT_SESSION)
    }

    fun selectPreviousSession() {
        sendCommand(MediaCommand.SELECT_PREVIOUS_SESSION)
    }

    fun selectSessionAt(indexOneBased: Int) {
        if (indexOneBased <= 0) return
        val payload = "${SELECT_SESSION_INDEX_PREFIX}$indexOneBased".toByteArray(Charsets.UTF_8)
        nodeClient.connectedNodes
            .addOnSuccessListener { nodes ->
                if (nodes.isEmpty()) {
                    _uiState.value = _uiState.value.copy(isPhoneReachable = false)
                    return@addOnSuccessListener
                }

                _uiState.value = _uiState.value.copy(isPhoneReachable = true)
                for (node in nodes) {
                    messageClient.sendMessage(node.id, MediaProtocol.commandPath, payload)
                }
            }
            .addOnFailureListener {
                _uiState.value = _uiState.value.copy(isPhoneReachable = false)
            }
    }

    private fun sendCommand(command: MediaCommand) {
        nodeClient.connectedNodes
            .addOnSuccessListener { nodes ->
                if (nodes.isEmpty()) {
                    _uiState.value = _uiState.value.copy(isPhoneReachable = false)
                    return@addOnSuccessListener
                }

                _uiState.value = _uiState.value.copy(isPhoneReachable = true)
                for (node in nodes) {
                    messageClient.sendMessage(node.id, MediaProtocol.commandPath, command.toPayload())
                }
            }
            .addOnFailureListener {
                _uiState.value = _uiState.value.copy(isPhoneReachable = false)
            }
    }

    private fun handleIncomingState(payload: MediaStatePayload) {
        _uiState.value = _uiState.value.copy(
            title = payload.title,
            artist = payload.artist,
            appName = payload.appName,
            appPackageName = payload.appPackageName,
            isPlaying = payload.isPlaying,
            duration = payload.duration,
            position = payload.position,
            availableActions = payload.availableActions,
            sessionIndex = payload.sessionIndex,
            sessionCount = payload.sessionCount,
            isPhoneReachable = true,
            hasLoadedState = true,
            connectionStatus = payload.connectionStatus,
            artworkToken = payload.artworkToken,
            sessionCarousel = payload.sessionCarousel,
        )

        // Active session first so its cover is decoded before carousel tiles.
        val thumbs = LinkedHashMap<String, String>()
        if (payload.artworkToken.isNotEmpty()) thumbs[payload.artworkToken] = payload.artworkThumbBase64
        for (tile in payload.sessionCarousel) {
            if (tile.artworkToken.isNotEmpty() && tile.artworkToken !in thumbs) thumbs[tile.artworkToken] = tile.artworkThumbBase64
        }
        thumbBase64ByToken = thumbs
        val stale = _uiState.value.artwork.keys - thumbs.keys
        if (stale.isNotEmpty()) {
            fullResTokens.removeAll(stale)
            _uiState.value = _uiState.value.copy(artwork = _uiState.value.artwork - stale)
        }
        loadArtwork()
    }

    private fun handleIncomingArtwork(assets: Map<String, Asset>) {
        assetsByToken = assets
        loadArtwork()
    }

    private fun loadArtwork() {
        for ((token, thumb) in thumbBase64ByToken) {
            if (token !in _uiState.value.artwork && thumb.isNotEmpty() && loadingThumbs.add(token)) {
                viewModelScope.launch {
                    val bitmap = withContext(Dispatchers.Default) { decodeBase64Jpeg(thumb) }
                    loadingThumbs.remove(token)
                    // A full-res cover may have beaten the thumbnail; never downgrade.
                    if (bitmap != null && token in thumbBase64ByToken && token !in fullResTokens) publishArtwork(token, bitmap)
                }
            }
        }
        for (token in thumbBase64ByToken.keys) {
            val asset = assetsByToken[token] ?: continue
            if (token in fullResTokens || !loadingFull.add(token)) continue
            viewModelScope.launch {
                val bitmap = withContext(Dispatchers.IO) { loadAsset(asset) }
                loadingFull.remove(token)
                if (bitmap != null && token in thumbBase64ByToken) {
                    fullResTokens.add(token)
                    publishArtwork(token, bitmap)
                }
            }
        }
    }

    private fun publishArtwork(token: String, bitmap: Bitmap) {
        _uiState.value = _uiState.value.copy(artwork = _uiState.value.artwork + (token to bitmap))
    }

    private fun decodeBase64Jpeg(base64: String): Bitmap? = runCatching {
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()

    private fun loadAsset(asset: Asset): Bitmap? = runCatching {
        Tasks.await(dataClient.getFdForAsset(asset)).inputStream.use { BitmapFactory.decodeStream(it) }
    }.onFailure { error ->
        Log.w(logTag, "Failed to load artwork asset", error)
    }.getOrNull()

    companion object {
        private const val logTag = "MediaControlViewModel"
    }

    override fun onCleared() {
        wearDataListener.stop()
        super.onCleared()
    }

    fun canSkipPrevious(): Boolean = hasReadySession()

    fun canSkipNext(): Boolean = hasReadySession()

    fun canTogglePlayback(): Boolean = hasReadySession()

    fun canSwitchSessions(): Boolean = hasReadySession() && _uiState.value.sessionCount > 1

    private fun hasReadySession(): Boolean {
        val state = _uiState.value
        return state.isPhoneReachable &&
            state.hasLoadedState &&
            state.connectionStatus == MediaConnectionStatus.READY
    }
}
