package se.kvrgic.mediawristremote.wear

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.google.android.gms.wearable.Wearable
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
    val artworkBase64: String = "",
)

class MediaControlViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val nodeClient = Wearable.getNodeClient(appContext)
    private val messageClient = Wearable.getMessageClient(appContext)
    private val wearDataListener = WearDataListener(appContext, ::handleIncomingState)

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
            artworkBase64 = payload.artworkBase64,
        )
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
