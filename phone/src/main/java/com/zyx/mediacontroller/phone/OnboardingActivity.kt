package se.kvrgic.mediawristremote.phone

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.launch
import kotlin.math.abs

private enum class PhoneScreen { HOME, APP_CONTROLS, DIAGNOSTICS }

data class CustomActionDiagnostic(val label: String, val action: String, val extras: String)

data class PhonePlayerUiState(
    val notificationAccessEnabled: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val appName: String = "",
    val appPackageName: String = "",
    val isPlaying: Boolean = false,
    val duration: Long = 0L,
    val position: Long = 0L,
    val availableActions: Long = 0L,
    val hasSession: Boolean = false,
    val activeSessions: List<ActiveSessionInfo> = emptyList(),
    val selectedSessionId: String = "",
    val selectedSessionIndex: Int = 0,
    val sessionCount: Int = 0,
    val isManualSelection: Boolean = false,
    val standardActionDiagnostics: List<String> = emptyList(),
    val customActionDiagnostics: List<CustomActionDiagnostic> = emptyList(),
    val notificationActionDiagnostics: List<String> = emptyList(),
    val skipButtonMode: SkipButtonMode = SkipButtonMode.SEEK,
    val playedApps: List<PlayedApp> = emptyList(),
    val lastCommandRoute: String = CommandDiagnostics.lastRoute,
    val artwork: Bitmap? = null,
    val artworkToken: String = "",
    val appIcon: Bitmap? = null,
    val sessionPreviewArt: Map<String, Bitmap?> = emptyMap(),
)

class OnboardingActivity : ComponentActivity() {
    private val sessionManager by lazy { getSystemService(MediaSessionManager::class.java) }
    private var activeController: MediaController? = null
    private var latestControllers: List<MediaController> = emptyList()
    private var uiState by mutableStateOf(PhonePlayerUiState())
    private var currentScreen by mutableStateOf(PhoneScreen.HOME)

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = refreshState()
        override fun onPlaybackStateChanged(state: PlaybackState?) = refreshState()
        override fun onSessionDestroyed() = refreshState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshState()
        setContent {
            MediaWristRemoteTheme {
                PhoneApp(
                    uiState = uiState,
                    currentScreen = currentScreen,
                    onNavigateHome = { currentScreen = PhoneScreen.HOME },
                    onNavigateAppControls = { currentScreen = PhoneScreen.APP_CONTROLS },
                    onNavigateDiagnostics = { currentScreen = PhoneScreen.DIAGNOSTICS },
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
                playedApps = PhoneControlPreferences.playedApps(this),
                lastCommandRoute = CommandDiagnostics.lastRoute,
            )
            MediaStatePublisher.publish(this, MediaStatePayload(connectionStatus = MediaConnectionStatus.PERMISSION_REQUIRED))
            return
        }

        val controllers = activeSessions()
        latestControllers = controllers
        controllers.forEach { controller ->
            PhoneControlPreferences.rememberPlayedApp(
                context = this,
                packageName = controller.packageName,
                displayName = appNameFor(controller.packageName),
                observedActions = controller.playbackState?.actions ?: 0L,
            )
        }

        val controller = MediaControllerRegistry.preferredControllerFrom(controllers) ?: chooseBestController(controllers)
        selectController(controller)
        uiState = controller.toUiState(notificationAccessEnabled = true, controllers = controllers)
        MediaStatePublisher.publish(this, controller.toMediaStatePayload(controllers))
    }

    private fun activeSessions(): List<MediaController> = runCatching {
        sessionManager.getActiveSessions(ComponentName(this, MediaSessionListenerService::class.java))
    }.getOrDefault(emptyList())

    private fun openNotificationAccessSettings() = startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))

    private fun togglePlayback() {
        val controller = activeController ?: return
        MediaCommandDispatcher.dispatch(controller = controller, command = MediaCommand.TOGGLE, onStateMayHaveChanged = ::refreshState)
    }

    private fun skipPrevious() {
        val controller = activeController ?: return
        MediaCommandDispatcher.dispatch(
            context = this,
            controller = controller,
            command = MediaCommand.PREVIOUS,
            skipButtonMode = PhoneControlPreferences.skipButtonMode(this, controller.packageName),
            onStateMayHaveChanged = ::refreshState,
        )
    }

    private fun skipNext() {
        val controller = activeController ?: return
        MediaCommandDispatcher.dispatch(
            context = this,
            controller = controller,
            command = MediaCommand.NEXT,
            skipButtonMode = PhoneControlPreferences.skipButtonMode(this, controller.packageName),
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

    private fun setSkipButtonMode(packageName: String?, mode: SkipButtonMode) {
        PhoneControlPreferences.setSkipButtonMode(this, packageName ?: activeController?.packageName, mode)
        refreshState()
    }

    private fun selectController(controller: MediaController?) {
        if (activeController?.sessionToken == controller?.sessionToken) return
        activeController?.unregisterCallback(controllerCallback)
        activeController = controller
        MediaControllerRegistry.updateActiveController(controller)
        controller?.registerCallback(controllerCallback)
    }

    private fun buildSessionPreviewArt(controllers: List<MediaController>): Map<String, Bitmap?> {
        if (controllers.isEmpty()) return emptyMap()
        return controllers.associate { controller ->
            controller.sessionId() to controller.metadata.artworkBitmap()
        }
    }

    private fun MediaController?.toUiState(
        notificationAccessEnabled: Boolean,
        controllers: List<MediaController>,
    ): PhonePlayerUiState {
        val selectedId = this?.sessionId().orEmpty()
        val activeSessions = controllers.map { controller -> controller.toSessionInfo(selectedId) }
        val playedApps = PhoneControlPreferences.playedApps(this@OnboardingActivity)
        if (this == null) {
            return PhonePlayerUiState(
                notificationAccessEnabled = notificationAccessEnabled,
                activeSessions = activeSessions,
                isManualSelection = MediaControllerRegistry.hasPreferredController(),
                playedApps = playedApps,
                lastCommandRoute = CommandDiagnostics.lastRoute,
                sessionPreviewArt = buildSessionPreviewArt(controllers),
            )
        }

        val metadata = metadata
        val playbackState = playbackState
        val artwork = metadata.artworkBitmap()
        val selectedIndex = controllers.indexOfFirst { it.sessionToken == sessionToken }.takeIf { it >= 0 } ?: 0
        return PhonePlayerUiState(
            notificationAccessEnabled = notificationAccessEnabled,
            title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
            artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),
            appName = appNameFor(packageName),
            appPackageName = packageName,
            isPlaying = playbackState?.state == PlaybackState.STATE_PLAYING,
            duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
            position = playbackState?.position ?: 0L,
            availableActions = playbackState?.actions ?: 0L,
            hasSession = true,
            activeSessions = activeSessions,
            selectedSessionId = selectedId,
            selectedSessionIndex = selectedIndex + 1,
            sessionCount = controllers.size,
            isManualSelection = MediaControllerRegistry.hasPreferredController(),
            standardActionDiagnostics = playbackState?.actions.toStandardActionDiagnostics(),
            customActionDiagnostics = playbackState?.customActions.orEmpty().map { action ->
                CustomActionDiagnostic(
                    label = action.name?.toString().orEmpty().ifBlank { "(no label)" },
                    action = action.action,
                    extras = action.extras.toExtrasSummary(),
                )
            },
            notificationActionDiagnostics = NotificationActionRegistry.actionTitles(packageName),
            skipButtonMode = PhoneControlPreferences.skipButtonMode(this@OnboardingActivity, packageName),
            playedApps = playedApps,
            lastCommandRoute = CommandDiagnostics.lastRoute,
            artwork = artwork,
            artworkToken = artwork?.let { "${packageName}:${metadata?.hashCode()}:${it.width}x${it.height}" }.orEmpty(),
            appIcon = appIconFor(packageName),
            sessionPreviewArt = buildSessionPreviewArt(controllers),
        )
    }

    private fun MediaController.toSessionInfo(selectedId: String): ActiveSessionInfo {
        val metadata = metadata
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() } ?: appNameFor(packageName)
        return ActiveSessionInfo(
            id = sessionId(),
            packageName = packageName,
            appName = appNameFor(packageName),
            title = title,
            artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),
            stateLabel = playbackState?.state.toPlaybackStateLabel(),
            isSelected = sessionId() == selectedId,
        )
    }

    private fun MediaController?.toMediaStatePayload(controllers: List<MediaController>): MediaStatePayload {
        if (this == null) return MediaStatePayload(connectionStatus = MediaConnectionStatus.NO_SESSION)
        val metadata = metadata
        val playbackState = playbackState
        val selectedIndex = controllers.indexOfFirst { it.sessionToken == sessionToken }.takeIf { it >= 0 } ?: 0
        return MediaStatePayload(
            connectionStatus = MediaConnectionStatus.READY,
            title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
            artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),
            appName = appNameFor(packageName),
            appPackageName = packageName,
            isPlaying = playbackState?.state == PlaybackState.STATE_PLAYING,
            duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
            position = playbackState?.position ?: 0L,
            availableActions = playbackState?.actions ?: 0L,
            sessionIndex = selectedIndex + 1,
            sessionCount = controllers.size,
            artworkToken = metadata.artworkBitmap()?.let { "${packageName}:${metadata?.hashCode()}:${it.width}x${it.height}" }.orEmpty(),
            artworkBase64 = WearMediaArtworkSync.encode(metadata.artworkBitmap()),
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

    private fun appIconFor(packageName: String): Bitmap? {
        return runCatching { packageManager.getApplicationIcon(packageName).toBitmap(width = 96, height = 96) }.getOrNull()
    }

    private fun MediaController.sessionId(): String = "$packageName:${sessionToken.hashCode()}"
}

@Composable
private fun MediaWristRemoteTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) {
        darkColorScheme(
            primary = Color(0xFF9AD7FF),
            secondary = Color(0xFFB9C8DA),
            tertiary = Color(0xFFFFC4DD),
            surface = Color(0xFF101418),
            background = Color(0xFF0B0F12),
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF006493),
            secondary = Color(0xFF50606F),
            tertiary = Color(0xFF8A4565),
            surface = Color(0xFFF7FAFD),
            background = Color(0xFFF3F7FB),
        )
    }
    MaterialTheme(colorScheme = colors) {
        Surface(color = MaterialTheme.colorScheme.background, content = content)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhoneApp(
    uiState: PhonePlayerUiState,
    currentScreen: PhoneScreen,
    onNavigateHome: () -> Unit,
    onNavigateAppControls: () -> Unit,
    onNavigateDiagnostics: () -> Unit,
    onOpenSettings: () -> Unit,
    onRefresh: () -> Unit,
    onTogglePlayback: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    onSelectSession: (String) -> Unit,
    onClearPreferredSession: () -> Unit,
    onSetSkipButtonMode: (String?, SkipButtonMode) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Media Wrist Remote", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            text = subtitleForMainTopBar(uiState),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = "Open menu")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Now playing") },
                            onClick = {
                                menuExpanded = false
                                onNavigateHome()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Per-app watch controls") },
                            onClick = {
                                menuExpanded = false
                                onNavigateAppControls()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Diagnostics") },
                            onClick = {
                                menuExpanded = false
                                onNavigateDiagnostics()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Refresh") },
                            onClick = {
                                menuExpanded = false
                                onRefresh()
                            },
                        )
                    }
                },
            )
        },
    ) { padding ->
        when (currentScreen) {
            PhoneScreen.HOME -> HomeScreen(
                uiState = uiState,
                onOpenSettings = onOpenSettings,
                onTogglePlayback = onTogglePlayback,
                onSkipPrevious = onSkipPrevious,
                onSkipNext = onSkipNext,
                onSelectSession = onSelectSession,
                onClearPreferredSession = onClearPreferredSession,
                onSetSkipButtonMode = onSetSkipButtonMode,
                modifier = Modifier.padding(padding),
            )
            PhoneScreen.APP_CONTROLS -> AppControlsScreen(
                uiState = uiState,
                onSetSkipButtonMode = onSetSkipButtonMode,
                modifier = Modifier.padding(padding),
            )
            PhoneScreen.DIAGNOSTICS -> DiagnosticsScreen(uiState = uiState, modifier = Modifier.padding(padding))
        }
    }
}

@Composable
private fun HomeScreen(
    uiState: PhonePlayerUiState,
    onOpenSettings: () -> Unit,
    onTogglePlayback: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    onSelectSession: (String) -> Unit,
    @Suppress("UNUSED_PARAMETER") onClearPreferredSession: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onSetSkipButtonMode: (String?, SkipButtonMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.Top),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PermissionCard(uiState.notificationAccessEnabled, onOpenSettings)
        NowPlayingCard(
            uiState = uiState,
            onTogglePlayback = onTogglePlayback,
            onSkipPrevious = onSkipPrevious,
            onSkipNext = onSkipNext,
            onSelectSession = onSelectSession,
        )
    }
}

@Composable
private fun PermissionCard(notificationAccessEnabled: Boolean, onOpenSettings: () -> Unit) {
    if (notificationAccessEnabled) return
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Notification access needed", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Enable Notification Access so the phone can see media sessions and route watch controls.")
            Button(onClick = onOpenSettings) { Text("Open Notification Access") }
        }
    }
}

@Composable
private fun AppControlsScreen(
    uiState: PhonePlayerUiState,
    onSetSkipButtonMode: (String?, SkipButtonMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PerAppControlsCard(
            playedApps = uiState.playedApps,
            currentPackageName = uiState.appPackageName,
            onSetSkipButtonMode = onSetSkipButtonMode,
        )
    }
}

@Composable
private fun NowPlayingCard(
    uiState: PhonePlayerUiState,
    onTogglePlayback: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    onSelectSession: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                ArtworkHeader(
                    uiState = uiState,
                    onTogglePlayback = onTogglePlayback,
                    onSkipPrevious = onSkipPrevious,
                    onSkipNext = onSkipNext,
                    onSelectSession = onSelectSession,
                )
                if (uiState.hasSession && uiState.sessionCount > 1) {
                    SessionSwipePageDots(
                        selectedIndexOneBased = uiState.selectedSessionIndex,
                        pageCount = uiState.sessionCount,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            Text(
                text = if (uiState.hasSession) uiState.title.ifBlank { "Unknown track" } else "Nothing playing",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (uiState.hasSession) {
                    listOf(uiState.sessionArtistSubtitle(), uiState.sessionAppSubtitleLine())
                        .filter { it.isNotBlank() }
                        .joinToString(" - ")
                        .ifBlank { "Media session active" }
                } else {
                    "Start playback in an app on your phone."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (uiState.hasSession) {
                Text(
                    text = "${formatTime(uiState.position)} / ${formatTime(uiState.duration)}",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
private fun SessionSwipePageDots(
    selectedIndexOneBased: Int,
    pageCount: Int,
    modifier: Modifier = Modifier,
) {
    if (pageCount <= 1) return
    val selectedZero = (selectedIndexOneBased - 1).coerceIn(0, pageCount - 1)
    val colors = MaterialTheme.colorScheme
    Row(
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .semantics {
                contentDescription = "Sessions: ${selectedIndexOneBased.coerceAtLeast(1)} of $pageCount, swipe artwork to switch"
            },
    ) {
        repeat(pageCount) { index ->
            val filled = index == selectedZero
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .then(
                        if (filled) {
                            Modifier.background(colors.primary)
                        } else {
                            Modifier
                                .background(Color.Transparent)
                                .border(
                                    width = 1.5.dp,
                                    color = colors.outlineVariant,
                                    shape = CircleShape,
                                )
                        },
                    ),
            )
        }
    }
}

@Composable
private fun ArtworkHeader(
    uiState: PhonePlayerUiState,
    onTogglePlayback: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    onSelectSession: (String) -> Unit,
) {
    var dragTotal by remember { mutableStateOf(0f) }
    val artworkOffset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        val maxDrag = constraints.maxWidth * 0.42f
        val swipeStrength = abs(artworkOffset.value) / maxDrag.coerceAtLeast(1f)
        val swipeDirection = when {
            artworkOffset.value < -0.5f -> 1
            artworkOffset.value > 0.5f -> -1
            else -> null
        }
        val peekSessionId = neighborSessionIdForSwipe(uiState.activeSessions, uiState.selectedSessionId, swipeDirection)
        val peekArt = peekSessionId?.let { id -> uiState.sessionPreviewArt[id] }
        val peekPackage = peekSessionId?.let { id -> uiState.activeSessions.firstOrNull { it.id == id }?.packageName }
        val peekIcon = peekPackage?.let { packageName ->
            remember(packageName) {
                runCatching { context.packageManager.getApplicationIcon(packageName).toBitmap(width = 96, height = 96) }.getOrNull()
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(30.dp)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.primaryContainer),
            )
            when {
                peekArt != null -> {
                    Image(
                        bitmap = peekArt.asImageBitmap(),
                        contentDescription = "Next session artwork preview",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                peekIcon != null -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Image(
                            bitmap = peekIcon.asImageBitmap(),
                            contentDescription = "Next session app icon",
                            modifier = Modifier.size(72.dp),
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val progress = swipeStrength.coerceIn(0f, 1f)
                        translationX = artworkOffset.value
                        alpha = 1f - (progress * 0.18f)
                        scaleX = 1f - (progress * 0.04f)
                        scaleY = 1f - (progress * 0.04f)
                    }
                    .pointerInput(
                        uiState.activeSessions,
                        uiState.selectedSessionId,
                        uiState.sessionPreviewArt,
                    ) {
                        detectHorizontalDragGestures(
                            onDragStart = {
                                dragTotal = 0f
                                scope.launch { artworkOffset.stop() }
                            },
                            onHorizontalDrag = { _, dragAmount ->
                                dragTotal += dragAmount
                                val nextOffset = (artworkOffset.value + dragAmount).coerceIn(-maxDrag, maxDrag)
                                scope.launch { artworkOffset.snapTo(nextOffset) }
                            },
                            onDragEnd = {
                                if (abs(dragTotal) > SWIPE_THRESHOLD_PX) {
                                    val direction = if (dragTotal < 0f) 1 else -1
                                    scope.launch {
                                        selectAdjacentSession(
                                            activeSessions = uiState.activeSessions,
                                            selectedSessionId = uiState.selectedSessionId,
                                            direction = direction,
                                            onSelectSession = onSelectSession,
                                        )
                                        artworkOffset.snapTo(0f)
                                    }
                                } else {
                                    scope.launch {
                                        artworkOffset.animateTo(
                                            targetValue = 0f,
                                            animationSpec = spring(dampingRatio = 0.78f, stiffness = 520f),
                                        )
                                    }
                                }
                                dragTotal = 0f
                            },
                            onDragCancel = {
                                dragTotal = 0f
                                scope.launch {
                                    artworkOffset.animateTo(
                                        targetValue = 0f,
                                        animationSpec = spring(dampingRatio = 0.78f, stiffness = 520f),
                                    )
                                }
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    if (uiState.artwork != null) {
                        Image(
                            bitmap = uiState.artwork.asImageBitmap(),
                            contentDescription = "Media artwork",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            if (uiState.appIcon != null) {
                                Image(
                                    bitmap = uiState.appIcon.asImageBitmap(),
                                    contentDescription = humanReadableAppLabel(uiState.appName, uiState.appPackageName).ifBlank { "App icon" },
                                    modifier = Modifier.size(72.dp),
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                            }
                            Text(
                                text = humanReadableAppLabel(uiState.appName, uiState.appPackageName).ifBlank { "Media" },
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 20.dp),
                            )
                        }
                    }

                    MediaControlOverlay(
                        uiState = uiState,
                        onTogglePlayback = onTogglePlayback,
                        onSkipPrevious = onSkipPrevious,
                        onSkipNext = onSkipNext,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
        }
    }
}

@Composable
private fun MediaControlOverlay(
    uiState: PhonePlayerUiState,
    onTogglePlayback: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ControlIconButton(
            imageVector = if (uiState.skipButtonMode == SkipButtonMode.SEEK) {
                Icons.Rounded.FastRewind
            } else {
                Icons.Rounded.SkipPrevious
            },
            contentDescription = uiState.skipButtonMode.previousButtonLabel,
            enabled = uiState.hasSession,
            onClick = onSkipPrevious,
        )
        ControlIconButton(
            imageVector = if (uiState.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            contentDescription = if (uiState.isPlaying) "Pause" else "Play",
            enabled = uiState.hasSession,
            size = 72,
            onClick = onTogglePlayback,
        )
        ControlIconButton(
            imageVector = if (uiState.skipButtonMode == SkipButtonMode.SEEK) {
                Icons.Rounded.FastForward
            } else {
                Icons.Rounded.SkipNext
            },
            contentDescription = uiState.skipButtonMode.nextButtonLabel,
            enabled = uiState.hasSession,
            onClick = onSkipNext,
        )
    }
}

@Composable
private fun ControlIconButton(
    imageVector: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    size: Int = 60,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(size.dp)
            .shadow(14.dp, RoundedCornerShape(50))
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = if (enabled) 0.42f else 0.18f)),
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = Color.White.copy(alpha = if (enabled) 0.96f else 0.48f),
            modifier = Modifier.size((size * 0.52f).dp),
        )
    }
}

private fun selectAdjacentSession(
    activeSessions: List<ActiveSessionInfo>,
    selectedSessionId: String,
    direction: Int,
    onSelectSession: (String) -> Unit,
) {
    if (activeSessions.size < 2) return
    val currentIndex = activeSessions.indexOfFirst { it.id == selectedSessionId }.takeIf { it >= 0 } ?: 0
    val nextIndex = (currentIndex + direction).floorMod(activeSessions.size)
    onSelectSession(activeSessions[nextIndex].id)
}

private fun neighborSessionIdForSwipe(
    activeSessions: List<ActiveSessionInfo>,
    selectedSessionId: String,
    swipeDirection: Int?,
): String? {
    if (activeSessions.size < 2 || swipeDirection == null) return null
    val currentIndex = activeSessions.indexOfFirst { it.id == selectedSessionId }.takeIf { it >= 0 } ?: 0
    val nextIndex = (currentIndex + swipeDirection).floorMod(activeSessions.size)
    return activeSessions[nextIndex].id
}

private fun Int.floorMod(modulus: Int): Int {
    return ((this % modulus) + modulus) % modulus
}

private const val SWIPE_THRESHOLD_PX = 90f

@Composable
private fun ActiveSessionsCard(
    activeSessions: List<ActiveSessionInfo>,
    selectedSessionId: String,
    isManualSelection: Boolean,
    onSelectSession: (String) -> Unit,
    onClearPreferredSession: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Active sessions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                text = if (isManualSelection) "Pinned to the selected session." else "Auto-selecting the most active session.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (activeSessions.isEmpty()) {
                Text("No active media sessions.")
            } else {
                activeSessions.forEach { session ->
                    SessionRow(session, session.id == selectedSessionId, onSelectSession)
                }
            }
            OutlinedButton(onClick = onClearPreferredSession, enabled = isManualSelection, modifier = Modifier.fillMaxWidth()) {
                Text("Auto-select session")
            }
        }
    }
}

@Composable
private fun SessionRow(session: ActiveSessionInfo, isSelected: Boolean, onSelectSession: (String) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(session.appName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(session.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    text = listOf(session.artist, session.stateLabel).filter { it.isNotBlank() }.joinToString(" - "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = { onSelectSession(session.id) }, enabled = !isSelected) {
                Text(if (isSelected) "Selected" else "Use")
            }
        }
    }
}

@Composable
private fun PerAppControlsCard(
    playedApps: List<PlayedApp>,
    currentPackageName: String,
    onSetSkipButtonMode: (String?, SkipButtonMode) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Per-app watch controls", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "Choose what the watch Prev and Next buttons do for each media app. Routing stays on the phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (playedApps.isEmpty()) {
                Text("Apps will appear here after they play media.")
            } else {
                playedApps.forEach { app ->
                    AppControlRow(app, app.packageName == currentPackageName, onSetSkipButtonMode)
                }
            }
        }
    }
}

@Composable
private fun AppControlRow(
    app: PlayedApp,
    isCurrent: Boolean,
    onSetSkipButtonMode: (String?, SkipButtonMode) -> Unit,
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        resolvedAppDisplayName(context, app),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
                if (isCurrent) Text("Now", style = MaterialTheme.typography.labelLarge)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SkipButtonMode.entries.forEach { mode ->
                    Button(
                        onClick = { onSetSkipButtonMode(app.packageName, mode) },
                        enabled = app.skipButtonMode != mode,
                        modifier = Modifier.weight(1f),
                        colors = if (app.skipButtonMode == mode) {
                            ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        } else {
                            ButtonDefaults.buttonColors()
                        },
                    ) {
                        Text(if (app.skipButtonMode == mode) "${mode.shortTitle} ✓" else mode.shortTitle)
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsScreen(uiState: PhonePlayerUiState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        DiagnosticsCard(uiState)
    }
}

@Composable
private fun DiagnosticsCard(uiState: PhonePlayerUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Diagnostics", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Selected app: ${humanReadableAppLabel(uiState.appName, uiState.appPackageName).ifBlank { "None" }}")
            Text("Package: ${uiState.appPackageName.ifBlank { "None" }}", style = MaterialTheme.typography.bodySmall)
            Text("Session: ${uiState.selectedSessionId.ifBlank { "None" }}", style = MaterialTheme.typography.bodySmall)
            Text("Artwork token: ${uiState.artworkToken.ifBlank { "none" }}", style = MaterialTheme.typography.bodySmall)
            Text("Raw actions: ${uiState.availableActions}", style = MaterialTheme.typography.bodySmall)
            Text(
                "Standard actions: ${uiState.standardActionDiagnostics.ifEmpty { listOf("none") }.joinToString()}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("Notification actions", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            uiState.notificationActionDiagnostics.ifEmpty { listOf("None exposed") }.forEach { action ->
                Text(action, style = MaterialTheme.typography.bodySmall)
            }
            Text("Custom actions", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            if (uiState.customActionDiagnostics.isEmpty()) {
                Text("None exposed by this session.", style = MaterialTheme.typography.bodySmall)
            } else {
                uiState.customActionDiagnostics.forEach { action ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(action.label, style = MaterialTheme.typography.titleSmall)
                        Text(action.action, style = MaterialTheme.typography.bodySmall)
                        if (action.extras.isNotBlank()) Text("Extras: ${action.extras}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Text("Last command", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(uiState.lastCommandRoute, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun subtitleForMainTopBar(uiState: PhonePlayerUiState): String {
    val name = uiState.sessionAppSubtitleLine()
    return name.ifBlank { "Phone companion" }
}

private fun PhonePlayerUiState.sessionArtistSubtitle(): String =
    sanitizeMediaSubtitleField(artist, appPackageName)

private fun PhonePlayerUiState.sessionAppSubtitleLine(): String =
    humanReadableAppLabel(appName, appPackageName)

private fun humanReadableAppLabel(appName: String, packageName: String): String {
    val trimmed = appName.trim()
    if (trimmed.isEmpty()) return ""
    return if (packageName.isNotEmpty() && trimmed == packageName) "" else trimmed
}

private fun sanitizeMediaSubtitleField(raw: String?, currentPackageName: String): String {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return ""
    if (currentPackageName.isNotEmpty() && text.equals(currentPackageName, ignoreCase = false)) return ""
    if (text.looksLikeJavaPackageIdentifier()) return ""
    return text
}

private fun String.looksLikeJavaPackageIdentifier(): Boolean {
    if (none { it == '.' }) return false
    return matches(Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$"))
}

private fun resolvedAppDisplayName(context: Context, app: PlayedApp): String {
    val fromPm = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(app.packageName, 0)).toString().trim()
    }.getOrNull()
    if (!fromPm.isNullOrBlank() && fromPm != app.packageName) return fromPm
    val persisted = app.displayName.trim()
    if (persisted.isNotEmpty() && persisted != app.packageName) return persisted
    if (!fromPm.isNullOrBlank()) return fromPm
    return persisted.ifBlank { app.packageName }
}

private fun formatTime(milliseconds: Long): String {
    if (milliseconds <= 0L) return "0:00"
    val totalSeconds = milliseconds / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "%d:%02d".format(minutes, seconds)
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

private val SkipButtonMode.shortTitle: String
    get() = when (this) {
        SkipButtonMode.SEEK -> "Seek"
        SkipButtonMode.TRACK -> "Tracks"
    }

private fun Long?.toStandardActionDiagnostics(): List<String> {
    val actions = this ?: 0L
    return buildList {
        if (actions hasAction PlaybackState.ACTION_STOP) add("STOP (${PlaybackState.ACTION_STOP})")
        if (actions hasAction PlaybackState.ACTION_PAUSE) add("PAUSE (${PlaybackState.ACTION_PAUSE})")
        if (actions hasAction PlaybackState.ACTION_PLAY) add("PLAY (${PlaybackState.ACTION_PLAY})")
        if (actions hasAction PlaybackState.ACTION_REWIND) add("REWIND (${PlaybackState.ACTION_REWIND})")
        if (actions hasAction PlaybackState.ACTION_SKIP_TO_PREVIOUS) add("SKIP_TO_PREVIOUS (${PlaybackState.ACTION_SKIP_TO_PREVIOUS})")
        if (actions hasAction PlaybackState.ACTION_SKIP_TO_NEXT) add("SKIP_TO_NEXT (${PlaybackState.ACTION_SKIP_TO_NEXT})")
        if (actions hasAction PlaybackState.ACTION_FAST_FORWARD) add("FAST_FORWARD (${PlaybackState.ACTION_FAST_FORWARD})")
        if (actions hasAction PlaybackState.ACTION_SEEK_TO) add("SEEK_TO (${PlaybackState.ACTION_SEEK_TO})")
        if (actions hasAction PlaybackState.ACTION_PLAY_PAUSE) add("PLAY_PAUSE (${PlaybackState.ACTION_PLAY_PAUSE})")
        if (actions hasAction PlaybackState.ACTION_SET_RATING) add("SET_RATING (${PlaybackState.ACTION_SET_RATING})")
        if (actions hasAction PlaybackState.ACTION_PLAY_FROM_MEDIA_ID) add("PLAY_FROM_MEDIA_ID (${PlaybackState.ACTION_PLAY_FROM_MEDIA_ID})")
        if (actions hasAction PlaybackState.ACTION_PLAY_FROM_SEARCH) add("PLAY_FROM_SEARCH (${PlaybackState.ACTION_PLAY_FROM_SEARCH})")
        if (actions hasAction PlaybackState.ACTION_SKIP_TO_QUEUE_ITEM) add("SKIP_TO_QUEUE_ITEM (${PlaybackState.ACTION_SKIP_TO_QUEUE_ITEM})")
        if (actions hasAction PlaybackState.ACTION_PLAY_FROM_URI) add("PLAY_FROM_URI (${PlaybackState.ACTION_PLAY_FROM_URI})")
        if (actions hasAction PlaybackState.ACTION_PREPARE) add("PREPARE (${PlaybackState.ACTION_PREPARE})")
        if (actions hasAction PlaybackState.ACTION_PREPARE_FROM_MEDIA_ID) add("PREPARE_FROM_MEDIA_ID (${PlaybackState.ACTION_PREPARE_FROM_MEDIA_ID})")
        if (actions hasAction PlaybackState.ACTION_PREPARE_FROM_SEARCH) add("PREPARE_FROM_SEARCH (${PlaybackState.ACTION_PREPARE_FROM_SEARCH})")
        if (actions hasAction PlaybackState.ACTION_PREPARE_FROM_URI) add("PREPARE_FROM_URI (${PlaybackState.ACTION_PREPARE_FROM_URI})")
        if (actions hasAction PlaybackState.ACTION_SET_PLAYBACK_SPEED) add("SET_PLAYBACK_SPEED (${PlaybackState.ACTION_SET_PLAYBACK_SPEED})")
    }
}

@Suppress("DEPRECATION")
private fun Bundle?.toExtrasSummary(): String {
    if (this == null || isEmpty) return ""
    return keySet().sorted().joinToString { key -> "$key=${get(key)}" }
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
