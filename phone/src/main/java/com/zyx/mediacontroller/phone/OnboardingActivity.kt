package com.zyx.mediacontroller.phone

import android.content.ComponentName
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

data class PhonePlayerUiState(
    val notificationAccessEnabled: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val appName: String = "",
    val isPlaying: Boolean = false,
    val duration: Long = 0L,
    val position: Long = 0L,
    val availableActions: Long = 0L,
    val hasSession: Boolean = false,
)

class OnboardingActivity : ComponentActivity() {
    private val sessionManager by lazy { getSystemService(MediaSessionManager::class.java) }
    private var activeController: MediaController? = null
    private var uiState by mutableStateOf(PhonePlayerUiState())

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            refreshState()
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            refreshState()
        }

        override fun onSessionDestroyed() {
            refreshState()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshState()
        setContent {
            MaterialTheme {
                Surface {
                    PhoneControllerScreen(
                        uiState = uiState,
                        onOpenSettings = ::openNotificationAccessSettings,
                        onRefresh = ::refreshState,
                        onTogglePlayback = ::togglePlayback,
                        onSkipPrevious = ::skipPrevious,
                        onSkipNext = ::skipNext,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshState()
    }

    override fun onDestroy() {
        selectController(null)
        super.onDestroy()
    }

    private fun refreshState() {
        val notificationAccessEnabled = NotificationAccess.isEnabled(this)
        if (!notificationAccessEnabled) {
            selectController(null)
            uiState = PhonePlayerUiState(notificationAccessEnabled = false)
            MediaStatePublisher.publish(
                context = this,
                payload = MediaStatePayload(connectionStatus = MediaConnectionStatus.PERMISSION_REQUIRED),
            )
            return
        }

        val controllers = runCatching {
            sessionManager.getActiveSessions(ComponentName(this, MediaSessionListenerService::class.java))
        }.getOrDefault(emptyList())

        val controller = chooseBestController(controllers)
        selectController(controller)
        uiState = controller.toUiState(notificationAccessEnabled = true)
        MediaStatePublisher.publish(this, controller.toMediaStatePayload())
    }

    private fun openNotificationAccessSettings() {
        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    private fun togglePlayback() {
        val controller = activeController ?: return
        if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
            controller.transportControls.pause()
        } else {
            controller.transportControls.play()
        }
        refreshState()
    }

    private fun skipPrevious() {
        activeController?.transportControls?.skipToPrevious()
        refreshState()
    }

    private fun skipNext() {
        activeController?.transportControls?.skipToNext()
        refreshState()
    }

    private fun selectController(controller: MediaController?) {
        if (activeController?.sessionToken == controller?.sessionToken) return

        activeController?.unregisterCallback(controllerCallback)
        activeController = controller
        MediaControllerRegistry.activeController = controller
        controller?.registerCallback(controllerCallback)
    }

    private fun MediaController?.toUiState(notificationAccessEnabled: Boolean): PhonePlayerUiState {
        if (this == null) {
            return PhonePlayerUiState(notificationAccessEnabled = notificationAccessEnabled)
        }

        val metadata = metadata
        val playbackState = playbackState
        return PhonePlayerUiState(
            notificationAccessEnabled = notificationAccessEnabled,
            title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
            artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),
            appName = appNameFor(packageName),
            isPlaying = playbackState?.state == PlaybackState.STATE_PLAYING,
            duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
            position = playbackState?.position ?: 0L,
            availableActions = playbackState?.actions ?: 0L,
            hasSession = true,
        )
    }

    private fun MediaController?.toMediaStatePayload(): MediaStatePayload {
        if (this == null) {
            return MediaStatePayload(connectionStatus = MediaConnectionStatus.NO_SESSION)
        }

        val metadata = metadata
        val playbackState = playbackState
        return MediaStatePayload(
            connectionStatus = MediaConnectionStatus.READY,
            title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
            artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),
            appName = appNameFor(packageName),
            isPlaying = playbackState?.state == PlaybackState.STATE_PLAYING,
            duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
            position = playbackState?.position ?: 0L,
            availableActions = playbackState?.actions ?: 0L,
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
}

@Composable
private fun PhoneControllerScreen(
    uiState: PhonePlayerUiState,
    onOpenSettings: () -> Unit,
    onRefresh: () -> Unit,
    onTogglePlayback: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Phone Media Controller",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )

        PermissionCard(
            notificationAccessEnabled = uiState.notificationAccessEnabled,
            onOpenSettings = onOpenSettings,
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Active session",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = if (uiState.hasSession) {
                        uiState.title.ifBlank { "Unknown track" }
                    } else {
                        "Nothing playing"
                    },
                    style = MaterialTheme.typography.headlineSmall,
                )
                if (uiState.hasSession) {
                    val subtitle = listOf(uiState.artist, uiState.appName)
                        .filter { it.isNotBlank() }
                        .joinToString(" - ")
                    Text(text = subtitle.ifBlank { "Media session active" })
                    Text(text = "${formatTime(uiState.position)} / ${formatTime(uiState.duration)}")
                    Text(
                        text = "Actions: ${actionSummary(uiState.availableActions)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Text("Start playback in any media app, then refresh.")
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = onSkipPrevious,
                enabled = uiState.hasSession,
                modifier = Modifier.weight(1f),
            ) {
                Text("Prev")
            }
            Button(
                onClick = onTogglePlayback,
                enabled = uiState.hasSession,
                modifier = Modifier.weight(1f),
            ) {
                Text(if (uiState.isPlaying) "Pause" else "Play")
            }
            Button(
                onClick = onSkipNext,
                enabled = uiState.hasSession,
                modifier = Modifier.weight(1f),
            ) {
                Text("Next")
            }
        }

        OutlinedButton(onClick = onRefresh) {
            Text("Refresh")
        }
    }
}

@Composable
private fun PermissionCard(
    notificationAccessEnabled: Boolean,
    onOpenSettings: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = if (notificationAccessEnabled) {
                    "Notification access is enabled."
                } else {
                    "Enable Notification Access so the app can read and control the active media session."
                },
            )
            Button(
                onClick = onOpenSettings,
                colors = if (notificationAccessEnabled) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                } else {
                    ButtonDefaults.buttonColors()
                },
            ) {
                Text(
                    text = if (notificationAccessEnabled) {
                        "Open Notification Access"
                    } else {
                        "Enable Notification Access"
                    },
                )
            }
        }
    }
}

private fun formatTime(milliseconds: Long): String {
    if (milliseconds <= 0L) return "0:00"
    val totalSeconds = milliseconds / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "%d:%02d".format(minutes, seconds)
}

private fun actionSummary(actions: Long): String {
    if (actions == 0L) return "not advertised"

    val labels = buildList {
        if (actions hasAction PlaybackState.ACTION_PLAY) add("play")
        if (actions hasAction PlaybackState.ACTION_PAUSE) add("pause")
        if (actions hasAction PlaybackState.ACTION_PLAY_PAUSE) add("toggle")
        if (actions hasAction PlaybackState.ACTION_SKIP_TO_PREVIOUS) add("prev")
        if (actions hasAction PlaybackState.ACTION_SKIP_TO_NEXT) add("next")
        if (actions hasAction PlaybackState.ACTION_SEEK_TO) add("seek")
    }

    return labels.ifEmpty { listOf(actions.toString()) }.joinToString()
}

private infix fun Long.hasAction(action: Long): Boolean = this and action != 0L
