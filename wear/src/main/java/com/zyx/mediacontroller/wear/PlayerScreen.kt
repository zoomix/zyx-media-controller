package com.zyx.mediacontroller.wear

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText

@Composable
fun PlayerScreen(
    uiState: PlayerUiState,
    onRefresh: () -> Unit,
    onTogglePlayback: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    canTogglePlayback: Boolean,
    canSkipPrevious: Boolean,
    canSkipNext: Boolean,
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
                    canTogglePlayback = canTogglePlayback,
                    canSkipPrevious = canSkipPrevious,
                    canSkipNext = canSkipNext,
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
        Button(onClick = onAction) {
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
    canTogglePlayback: Boolean,
    canSkipPrevious: Boolean,
    canSkipNext: Boolean,
) {
    val subtitle = listOf(uiState.artist, uiState.appName)
        .filter { it.isNotBlank() }
        .joinToString(" - ")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = uiState.title.ifBlank { "Unknown track" },
            style = MaterialTheme.typography.title3,
            textAlign = TextAlign.Center,
        )
        Text(
            text = subtitle.ifBlank { "Media session active" },
            modifier = Modifier.padding(top = 6.dp),
            style = MaterialTheme.typography.body2,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "${formatTime(uiState.position)} / ${formatTime(uiState.duration)}",
            modifier = Modifier.padding(top = 12.dp),
            style = MaterialTheme.typography.caption3,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = onSkipPrevious,
                enabled = canSkipPrevious,
            ) {
                Text("Prev")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = onTogglePlayback, enabled = canTogglePlayback) {
                Text(if (uiState.isPlaying) "Pause" else "Play")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = onSkipNext,
                enabled = canSkipNext,
            ) {
                Text("Next")
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
