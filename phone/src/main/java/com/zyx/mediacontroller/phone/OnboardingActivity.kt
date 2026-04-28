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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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

data class PlayerSessionOption(
    val id: String,
    val title: String,
    val subtitle: String,
    val stateLabel: String,
)

data class CustomActionDiagnostic(
    val label: String,
    val action: String,
    val extras: String,
)

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
    val sessionOptions: List<PlayerSessionOption> = emptyList(),
    val selectedSessionId: String = "",
    val isManualSelection: Boolean = false,
    val standardActionDiagnostics: List<String> = emptyList(),
    val customActionDiagnostics: List<CustomActionDiagnostic> = emptyList(),
    val skipButtonMode: SkipButtonMode = SkipButtonMode.SEEK,
    val lastCommandRoute: String = CommandDiagnostics.lastRoute,
)

class OnboardingActivity : ComponentActivity() {
    private val sessionManager by lazy { getSystemService(MediaSessionManager::class.java) }
    private var activeController: MediaController? = null
    private var latestControllers: List<MediaController> = emptyList()
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
                        onSelectSession = ::selectSession,
                        onClearPreferredSession = ::clearPreferredSession,
                        onSetSkipButtonMode = ::setSkipButtonMode,
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
            uiState = PhonePlayerUiState(
                notificationAccessEnabled = false,
                skipButtonMode = PhoneControlPreferences.skipButtonMode(this),
            )
            MediaStatePublisher.publish(
                context = this,
                payload = MediaStatePayload(connectionStatus = MediaConnectionStatus.PERMISSION_REQUIRED),
            )
            return
        }

        val controllers = activeSessions()
        latestControllers = controllers

        val controller = MediaControllerRegistry.preferredControllerFrom(controllers)
            ?: chooseBestController(controllers)
        selectController(controller)
        uiState = controller.toUiState(
            notificationAccessEnabled = true,
            controllers = controllers,
        )
        MediaStatePublisher.publish(this, controller.toMediaStatePayload())
    }

    private fun activeSessions(): List<MediaController> {
        return runCatching {
            sessionManager.getActiveSessions(ComponentName(this, MediaSessionListenerService::class.java))
        }.getOrDefault(emptyList())
    }

    private fun openNotificationAccessSettings() {
        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    private fun togglePlayback() {
        val controller = activeController ?: return
        MediaCommandDispatcher.dispatch(
            controller = controller,
            command = MediaCommand.TOGGLE,
            onStateMayHaveChanged = ::refreshState,
        )
    }

    private fun skipPrevious() {
        val controller = activeController ?: return
        MediaCommandDispatcher.dispatch(
            context = this,
            controller = controller,
            command = MediaCommand.PREVIOUS,
            skipButtonMode = PhoneControlPreferences.skipButtonMode(this),
            onStateMayHaveChanged = ::refreshState,
        )
    }

    private fun skipNext() {
        val controller = activeController ?: return
        MediaCommandDispatcher.dispatch(
            context = this,
            controller = controller,
            command = MediaCommand.NEXT,
            skipButtonMode = PhoneControlPreferences.skipButtonMode(this),
            onStateMayHaveChanged = ::refreshState,
        )
    }

    private fun selectSession(sessionId: String) {
        val controller = latestControllers.firstOrNull { it.sessionId() == sessionId }
            ?: activeSessions().firstOrNull { it.sessionId() == sessionId }
            ?: return

        MediaControllerRegistry.selectPreferredController(controller)
        selectController(controller)
        refreshState()
    }

    private fun clearPreferredSession() {
        MediaControllerRegistry.clearPreferredController()
        refreshState()
    }

    private fun setSkipButtonMode(mode: SkipButtonMode) {
        PhoneControlPreferences.setSkipButtonMode(this, mode)
        refreshState()
    }

    private fun selectController(controller: MediaController?) {
        if (activeController?.sessionToken == controller?.sessionToken) return

        activeController?.unregisterCallback(controllerCallback)
        activeController = controller
        MediaControllerRegistry.updateActiveController(controller)
        controller?.registerCallback(controllerCallback)
    }

    private fun MediaController?.toUiState(
        notificationAccessEnabled: Boolean,
        controllers: List<MediaController>,
    ): PhonePlayerUiState {
        val sessionOptions = controllers.map { it.toSessionOption() }
        if (this == null) {
            return PhonePlayerUiState(
                notificationAccessEnabled = notificationAccessEnabled,
                sessionOptions = sessionOptions,
                isManualSelection = MediaControllerRegistry.hasPreferredController(),
                skipButtonMode = PhoneControlPreferences.skipButtonMode(this@OnboardingActivity),
            )
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
            sessionOptions = sessionOptions,
            selectedSessionId = sessionId(),
            isManualSelection = MediaControllerRegistry.hasPreferredController(),
            standardActionDiagnostics = playbackState?.actions.toStandardActionDiagnostics(),
            customActionDiagnostics = playbackState?.customActions
                .orEmpty()
                .map { action ->
                    CustomActionDiagnostic(
                        label = action.name?.toString().orEmpty().ifBlank { "(no label)" },
                        action = action.action,
                        extras = action.extras.toExtrasSummary(),
                    )
                },
            skipButtonMode = PhoneControlPreferences.skipButtonMode(this@OnboardingActivity),
            lastCommandRoute = CommandDiagnostics.lastRoute,
        )
    }

    private fun MediaController.toSessionOption(): PlayerSessionOption {
        val metadata = metadata
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?.takeIf { it.isNotBlank() }
            ?: appNameFor(packageName)
        val subtitle = listOf(
            metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),
            appNameFor(packageName),
        )
            .filter { it.isNotBlank() && it != title }
            .joinToString(" - ")

        return PlayerSessionOption(
            id = sessionId(),
            title = title,
            subtitle = subtitle.ifBlank { packageName },
            stateLabel = playbackState?.state.toPlaybackStateLabel(),
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

    private fun MediaController.sessionId(): String = "$packageName:${sessionToken.hashCode()}"
}

@Composable
private fun PhoneControllerScreen(
    uiState: PhonePlayerUiState,
    onOpenSettings: () -> Unit,
    onRefresh: () -> Unit,
    onTogglePlayback: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    onSelectSession: (String) -> Unit,
    onClearPreferredSession: () -> Unit,
    onSetSkipButtonMode: (SkipButtonMode) -> Unit,
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

        if (uiState.notificationAccessEnabled) {
            SessionPickerCard(
                sessionOptions = uiState.sessionOptions,
                selectedSessionId = uiState.selectedSessionId,
                isManualSelection = uiState.isManualSelection,
                onSelectSession = onSelectSession,
                onClearPreferredSession = onClearPreferredSession,
            )
        }

        if (uiState.hasSession) {
            WatchControlsCard(
                selectedMode = uiState.skipButtonMode,
                onSetSkipButtonMode = onSetSkipButtonMode,
            )

            MediaDiagnosticsCard(
                rawActions = uiState.availableActions,
                standardActions = uiState.standardActionDiagnostics,
                customActions = uiState.customActionDiagnostics,
                lastCommandRoute = uiState.lastCommandRoute,
            )
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
                Text(uiState.skipButtonMode.previousButtonLabel)
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
                Text(uiState.skipButtonMode.nextButtonLabel)
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

@Composable
private fun SessionPickerCard(
    sessionOptions: List<PlayerSessionOption>,
    selectedSessionId: String,
    isManualSelection: Boolean,
    onSelectSession: (String) -> Unit,
    onClearPreferredSession: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Instruction target",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = if (isManualSelection) {
                    "Watch and phone controls are sent to the selected session."
                } else {
                    "Auto-selecting the most active session."
                },
                style = MaterialTheme.typography.bodySmall,
            )

            if (sessionOptions.isEmpty()) {
                Text("No media sessions available.")
            } else {
                sessionOptions.forEach { option ->
                    SessionOptionRow(
                        option = option,
                        isSelected = option.id == selectedSessionId,
                        onSelectSession = onSelectSession,
                    )
                }
            }

            OutlinedButton(
                onClick = onClearPreferredSession,
                enabled = isManualSelection,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Auto-select target")
            }
        }
    }
}

@Composable
private fun SessionOptionRow(
    option: PlayerSessionOption,
    isSelected: Boolean,
    onSelectSession: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = option.title,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(text = option.subtitle)
                Text(
                    text = option.stateLabel,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Button(
                onClick = { onSelectSession(option.id) },
                enabled = !isSelected,
            ) {
                Text(if (isSelected) "Selected" else "Use")
            }
        }
    }
}

@Composable
private fun WatchControlsCard(
    selectedMode: SkipButtonMode,
    onSetSkipButtonMode: (SkipButtonMode) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Watch controls",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Choose what the watch Prev and Next buttons send to the selected session.",
                style = MaterialTheme.typography.bodySmall,
            )

            SkipButtonMode.entries.forEach { mode ->
                Button(
                    onClick = { onSetSkipButtonMode(mode) },
                    enabled = mode != selectedMode,
                    modifier = Modifier.fillMaxWidth(),
                    colors = if (mode == selectedMode) {
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    } else {
                        ButtonDefaults.buttonColors()
                    },
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = if (mode == selectedMode) "${mode.title} selected" else mode.title)
                        Text(
                            text = mode.description,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaDiagnosticsCard(
    rawActions: Long,
    standardActions: List<String>,
    customActions: List<CustomActionDiagnostic>,
    lastCommandRoute: String,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Session diagnostics",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Raw actions: $rawActions",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "Standard actions: ${standardActions.ifEmpty { listOf("none") }.joinToString()}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "Last command: $lastCommandRoute",
                style = MaterialTheme.typography.bodySmall,
            )

            Text(
                text = "Custom actions",
                style = MaterialTheme.typography.titleSmall,
            )
            if (customActions.isEmpty()) {
                Text(
                    text = "None exposed by this session.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                customActions.forEach { action ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = action.label,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = action.action,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (action.extras.isNotBlank()) {
                            Text(
                                text = "Extras: ${action.extras}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
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
        if (actions hasAction PlaybackState.ACTION_REWIND) add("rewind")
        if (actions hasAction PlaybackState.ACTION_FAST_FORWARD) add("fast forward")
        if (actions hasAction PlaybackState.ACTION_SEEK_TO) add("seek")
    }

    return labels.ifEmpty { listOf(actions.toString()) }.joinToString()
}

private infix fun Long.hasAction(action: Long): Boolean = this and action != 0L

private val SkipButtonMode.previousButtonLabel: String
    get() = when (this) {
        SkipButtonMode.SEEK -> "Rewind"
        SkipButtonMode.TRACK -> "Prev"
    }

private val SkipButtonMode.nextButtonLabel: String
    get() = when (this) {
        SkipButtonMode.SEEK -> "Forward"
        SkipButtonMode.TRACK -> "Next"
    }

private fun Long?.toStandardActionDiagnostics(): List<String> {
    val actions = this ?: 0L
    return buildList {
        if (actions hasAction PlaybackState.ACTION_STOP) add("STOP (${PlaybackState.ACTION_STOP})")
        if (actions hasAction PlaybackState.ACTION_PAUSE) add("PAUSE (${PlaybackState.ACTION_PAUSE})")
        if (actions hasAction PlaybackState.ACTION_PLAY) add("PLAY (${PlaybackState.ACTION_PLAY})")
        if (actions hasAction PlaybackState.ACTION_REWIND) add("REWIND (${PlaybackState.ACTION_REWIND})")
        if (actions hasAction PlaybackState.ACTION_SKIP_TO_PREVIOUS) {
            add("SKIP_TO_PREVIOUS (${PlaybackState.ACTION_SKIP_TO_PREVIOUS})")
        }
        if (actions hasAction PlaybackState.ACTION_SKIP_TO_NEXT) {
            add("SKIP_TO_NEXT (${PlaybackState.ACTION_SKIP_TO_NEXT})")
        }
        if (actions hasAction PlaybackState.ACTION_FAST_FORWARD) {
            add("FAST_FORWARD (${PlaybackState.ACTION_FAST_FORWARD})")
        }
        if (actions hasAction PlaybackState.ACTION_SEEK_TO) add("SEEK_TO (${PlaybackState.ACTION_SEEK_TO})")
        if (actions hasAction PlaybackState.ACTION_PLAY_PAUSE) {
            add("PLAY_PAUSE (${PlaybackState.ACTION_PLAY_PAUSE})")
        }
        if (actions hasAction PlaybackState.ACTION_SET_RATING) {
            add("SET_RATING (${PlaybackState.ACTION_SET_RATING})")
        }
        if (actions hasAction PlaybackState.ACTION_PLAY_FROM_MEDIA_ID) {
            add("PLAY_FROM_MEDIA_ID (${PlaybackState.ACTION_PLAY_FROM_MEDIA_ID})")
        }
        if (actions hasAction PlaybackState.ACTION_PLAY_FROM_SEARCH) {
            add("PLAY_FROM_SEARCH (${PlaybackState.ACTION_PLAY_FROM_SEARCH})")
        }
        if (actions hasAction PlaybackState.ACTION_SKIP_TO_QUEUE_ITEM) {
            add("SKIP_TO_QUEUE_ITEM (${PlaybackState.ACTION_SKIP_TO_QUEUE_ITEM})")
        }
        if (actions hasAction PlaybackState.ACTION_PLAY_FROM_URI) {
            add("PLAY_FROM_URI (${PlaybackState.ACTION_PLAY_FROM_URI})")
        }
        if (actions hasAction PlaybackState.ACTION_PREPARE) add("PREPARE (${PlaybackState.ACTION_PREPARE})")
        if (actions hasAction PlaybackState.ACTION_PREPARE_FROM_MEDIA_ID) {
            add("PREPARE_FROM_MEDIA_ID (${PlaybackState.ACTION_PREPARE_FROM_MEDIA_ID})")
        }
        if (actions hasAction PlaybackState.ACTION_PREPARE_FROM_SEARCH) {
            add("PREPARE_FROM_SEARCH (${PlaybackState.ACTION_PREPARE_FROM_SEARCH})")
        }
        if (actions hasAction PlaybackState.ACTION_PREPARE_FROM_URI) {
            add("PREPARE_FROM_URI (${PlaybackState.ACTION_PREPARE_FROM_URI})")
        }
        if (actions hasAction PlaybackState.ACTION_SET_PLAYBACK_SPEED) {
            add("SET_PLAYBACK_SPEED (${PlaybackState.ACTION_SET_PLAYBACK_SPEED})")
        }
    }
}

@Suppress("DEPRECATION")
private fun Bundle?.toExtrasSummary(): String {
    if (this == null || isEmpty) return ""
    return keySet()
        .sorted()
        .joinToString { key -> "$key=${get(key)}" }
}

private fun Int?.toPlaybackStateLabel(): String {
    return when (this) {
        PlaybackState.STATE_PLAYING -> "Playing"
        PlaybackState.STATE_BUFFERING -> "Buffering"
        PlaybackState.STATE_PAUSED -> "Paused"
        PlaybackState.STATE_STOPPED -> "Stopped"
        PlaybackState.STATE_FAST_FORWARDING -> "Fast forwarding"
        PlaybackState.STATE_REWINDING -> "Rewinding"
        PlaybackState.STATE_SKIPPING_TO_NEXT -> "Skipping to next"
        PlaybackState.STATE_SKIPPING_TO_PREVIOUS -> "Skipping to previous"
        PlaybackState.STATE_NONE -> "Inactive"
        null -> "Unknown state"
        else -> "Active"
    }
}
