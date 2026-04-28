package se.kvrgic.mediawristremote.wear

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import kotlin.math.abs

private const val SESSION_SWIPE_THRESHOLD_PX = 48f

/** Must match tallest control in [TranslucentMediaButton] (`large`). */
private val MEDIA_CONTROL_ROW_HEIGHT = 46.dp

private val TEXT_ANCHOR_GAP = 6.dp

private fun overlayTitleStyle(base: TextStyle): TextStyle = base.copy(
    color = Color.White,
    shadow = Shadow(
        color = Color.Black.copy(alpha = 0.75f),
        offset = Offset(0f, 1f),
        blurRadius = 10f,
    ),
)

private fun overlayBodyStyle(base: TextStyle): TextStyle = base.copy(
    color = Color.White.copy(alpha = 0.94f),
    shadow = Shadow(
        color = Color.Black.copy(alpha = 0.7f),
        offset = Offset(0f, 1f),
        blurRadius = 8f,
    ),
)

@Composable
fun PlayerScreen(
    uiState: PlayerUiState,
    onRefresh: () -> Unit,
    onTogglePlayback: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    onSelectPreviousSession: () -> Unit,
    onSelectNextSession: () -> Unit,
    canTogglePlayback: Boolean,
    canSkipPrevious: Boolean,
    canSkipNext: Boolean,
    canSwitchSessions: Boolean,
) {
    Scaffold(timeText = { TimeText() }) {
        when {
            !uiState.isPhoneReachable -> {
                StatusScreen(
                    title = "Phone disconnected",
                    body = "Keep the phone nearby and open the companion app once to wake the bridge.",
                    actionLabel = "Retry",
                    onAction = onRefresh,
                )
            }

            !uiState.hasLoadedState -> {
                StatusScreen(
                    title = "Connecting",
                    body = "Waiting for the phone to publish the current media session.",
                    actionLabel = "Refresh",
                    onAction = onRefresh,
                )
            }

            uiState.connectionStatus == MediaConnectionStatus.PERMISSION_REQUIRED -> {
                StatusScreen(
                    title = "Permission needed",
                    body = "Open the phone app and enable Notification Access.",
                    actionLabel = "Refresh",
                    onAction = onRefresh,
                )
            }

            uiState.connectionStatus == MediaConnectionStatus.NO_SESSION -> {
                StatusScreen(
                    title = "Nothing playing",
                    body = "Start media on the phone, then refresh the watch.",
                    actionLabel = "Refresh",
                    onAction = onRefresh,
                )
            }

            else -> {
                ActivePlayerScreen(
                    uiState = uiState,
                    onTogglePlayback = onTogglePlayback,
                    onSkipPrevious = onSkipPrevious,
                    onSkipNext = onSkipNext,
                    onSelectPreviousSession = onSelectPreviousSession,
                    onSelectNextSession = onSelectNextSession,
                    canTogglePlayback = canTogglePlayback,
                    canSkipPrevious = canSkipPrevious,
                    canSkipNext = canSkipNext,
                    canSwitchSessions = canSwitchSessions,
                )
            }
        }
    }
}

@Composable
private fun StatusScreen(
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.title3,
            textAlign = TextAlign.Center,
        )
        Text(
            text = body,
            modifier = Modifier.padding(top = 10.dp, bottom = 14.dp),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.body2,
        )
        androidx.wear.compose.material.Button(onClick = onAction) {
            Text(actionLabel)
        }
    }
}

@Composable
private fun ActivePlayerScreen(
    uiState: PlayerUiState,
    onTogglePlayback: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    onSelectPreviousSession: () -> Unit,
    onSelectNextSession: () -> Unit,
    canTogglePlayback: Boolean,
    canSkipPrevious: Boolean,
    canSkipNext: Boolean,
    canSwitchSessions: Boolean,
) {
    val albumOrShow = when {
        uiState.artist.isNotBlank() -> uiState.artist
        uiState.appName.isNotBlank() -> uiState.appName
        else -> ""
    }

    val artworkBitmap = remember(uiState.artworkToken, uiState.artworkBase64) {
        decodeWearArtworkJpeg(uiState.artworkBase64)
    }

    var dragTotal by remember { mutableStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(canSwitchSessions, onSelectNextSession, onSelectPreviousSession) {
                if (!canSwitchSessions) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { dragTotal = 0f },
                    onHorizontalDrag = { _, dx -> dragTotal += dx },
                    onDragEnd = {
                        if (abs(dragTotal) > SESSION_SWIPE_THRESHOLD_PX) {
                            if (dragTotal < 0f) {
                                onSelectNextSession()
                            } else {
                                onSelectPreviousSession()
                            }
                        }
                        dragTotal = 0f
                    },
                    onDragCancel = { dragTotal = 0f },
                )
            },
    ) {
        // Full-bleed backdrop: scale to fill the screen (round display clips corners / edges)
        when {
            artworkBitmap != null && !artworkBitmap.isRecycled -> {
                Image(
                    bitmap = artworkBitmap.asImageBitmap(),
                    contentDescription = "Album artwork",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            else -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    MaterialTheme.colors.primary.copy(alpha = 0.85f),
                                    MaterialTheme.colors.background,
                                ),
                            ),
                        ),
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp)
                .padding(top = 4.dp, bottom = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Track title: bottom-aligned in upper half → extra lines grow upward
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Text(
                    text = uiState.title.ifBlank { "Unknown track" },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = TEXT_ANCHOR_GAP),
                    style = overlayTitleStyle(
                        MaterialTheme.typography.title3.copy(
                            fontSize = 17.sp,
                            lineHeight = 20.sp,
                        ),
                    ),
                    textAlign = TextAlign.Center,
                    softWrap = true,
                    maxLines = 24,
                    overflow = TextOverflow.Visible,
                )
            }

            // Fixed-height row (46.dp = largest translucent control)
            MediaTransportControlsRow(
                rowHeight = MEDIA_CONTROL_ROW_HEIGHT,
                isPlaying = uiState.isPlaying,
                canTogglePlayback = canTogglePlayback,
                canSkipPrevious = canSkipPrevious,
                canSkipNext = canSkipNext,
                onTogglePlayback = onTogglePlayback,
                onSkipPrevious = onSkipPrevious,
                onSkipNext = onSkipNext,
            )

            // Album / session dots: top-aligned below buttons → extra lines grow downward
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(top = TEXT_ANCHOR_GAP),
                contentAlignment = Alignment.TopCenter,
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (uiState.sessionCount > 1) {
                        SessionPageDots(
                            selectedIndexOneBased = uiState.sessionIndex,
                            pageCount = uiState.sessionCount,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    if (albumOrShow.isNotBlank()) {
                        Text(
                            text = albumOrShow,
                            style = overlayBodyStyle(MaterialTheme.typography.body2),
                            textAlign = TextAlign.Center,
                            softWrap = true,
                            maxLines = 16,
                            overflow = TextOverflow.Visible,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaTransportControlsRow(
    rowHeight: Dp,
    isPlaying: Boolean,
    canTogglePlayback: Boolean,
    canSkipPrevious: Boolean,
    canSkipNext: Boolean,
    onTogglePlayback: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(rowHeight)
            .padding(horizontal = 2.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TranslucentMediaButton(
            vector = Icons.Rounded.FastRewind,
            contentDescription = "Previous",
            enabled = canSkipPrevious,
            onClick = onSkipPrevious,
        )
        TranslucentMediaButton(
            vector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            contentDescription = if (isPlaying) "Pause" else "Play",
            enabled = canTogglePlayback,
            onClick = onTogglePlayback,
            large = true,
        )
        TranslucentMediaButton(
            vector = Icons.Rounded.FastForward,
            contentDescription = "Next",
            enabled = canSkipNext,
            onClick = onSkipNext,
        )
    }
}

@Composable
private fun TranslucentMediaButton(
    vector: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
    large: Boolean = false,
) {
    val alpha = if (enabled) 0.5f else 0.28f
    val size = if (large) MEDIA_CONTROL_ROW_HEIGHT else 40.dp
    val iconSize = if (large) 26.dp else 22.dp
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = alpha))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = vector,
            contentDescription = contentDescription,
            tint = Color.White.copy(alpha = if (enabled) 0.95f else 0.45f),
            modifier = Modifier.size(iconSize),
        )
    }
}

@Composable
private fun SessionPageDots(
    selectedIndexOneBased: Int,
    pageCount: Int,
    modifier: Modifier = Modifier,
) {
    if (pageCount <= 1) return
    val selectedZero = (selectedIndexOneBased - 1).coerceIn(0, pageCount - 1)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(pageCount) { index ->
            val filled = index == selectedZero
            Box(
                modifier = Modifier
                    .size(if (filled) 8.dp else 6.dp)
                    .clip(CircleShape)
                    .then(
                        if (filled) {
                            Modifier.background(MaterialTheme.colors.primary.copy(alpha = 0.92f))
                        } else {
                            Modifier
                                .border(1.5.dp, Color.White.copy(alpha = 0.55f), CircleShape)
                                .background(Color.White.copy(alpha = 0.12f))
                        },
                    ),
            )
        }
    }
}

private fun decodeWearArtworkJpeg(base64: String): Bitmap? {
    if (base64.isBlank()) return null
    return runCatching {
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()?.takeUnless { it.isRecycled }
}
