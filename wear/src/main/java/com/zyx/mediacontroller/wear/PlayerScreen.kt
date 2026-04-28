package se.kvrgic.mediawristremote.wear

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
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
import kotlinx.coroutines.flow.distinctUntilChanged
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
    onSelectSessionAt: (Int) -> Unit,
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
                    onSelectSessionAt = onSelectSessionAt,
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
        androidx.wear.compose.material.Button(onClick = onAction) {
            Text(actionLabel)
        }
    }
}

private fun carouselAlbumLine(tile: SessionCarouselEntry): String =
    tile.artist.takeIf { it.isNotBlank() } ?: tile.appName

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun CarouselSessionActiveContent(
    uiState: PlayerUiState,
    tiles: List<SessionCarouselEntry>,
    pageCount: Int,
    onTogglePlayback: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    onSelectSessionAt: (Int) -> Unit,
    canTogglePlayback: Boolean,
    canSkipPrevious: Boolean,
    canSkipNext: Boolean,
) {
    val safePageCount = pageCount.coerceAtLeast(1)
    val initialPage = (uiState.sessionIndex.coerceIn(1, safePageCount) - 1).coerceIn(0, safePageCount - 1)
    val pagerState = rememberPagerState(
        initialPage = initialPage,
        pageCount = { safePageCount },
    )
    val sessionIndexStable by rememberUpdatedState(uiState.sessionIndex.coerceIn(1, safePageCount))
    var lastRequestedPage by remember(safePageCount) { mutableStateOf(initialPage) }

    // Phone -> watch sync: if phone switches session externally, align pager.
    LaunchedEffect(uiState.sessionIndex, safePageCount) {
        val phoneIdx = uiState.sessionIndex.coerceIn(1, safePageCount) - 1
        if (!pagerState.isScrollInProgress && pagerState.currentPage != phoneIdx) {
            pagerState.animateScrollToPage(phoneIdx)
        }
    }

    // Watch -> phone sync: commit whenever the selected page changes.
    LaunchedEffect(pagerState, safePageCount) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { page ->
                if (page !in 0 until safePageCount) return@collect
                if (page != lastRequestedPage) {
                    lastRequestedPage = page
                    onSelectSessionAt(page + 1)
                }
            }
    }

    val previewIndex = pagerState.currentPage.coerceIn(0, safePageCount - 1)
    val previewTile = tiles.getOrNull(previewIndex)
    val titleText = previewTile?.title?.takeIf { it.isNotBlank() } ?: uiState.title.ifBlank { "Unknown track" }
    val albumLine = previewTile?.let(::carouselAlbumLine).orEmpty()
        .ifBlank {
            when {
                uiState.artist.isNotBlank() -> uiState.artist
                uiState.appName.isNotBlank() -> uiState.appName
                else -> ""
            }
        }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colors.background),
    ) {
        VerticalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds(),
        ) { page ->
            val tile = tiles.getOrNull(page)
            val fallbackCurrentSessionPage = sessionIndexStable - 1
            val artBase64 = tile?.artworkBase64.takeIf { !it.isNullOrBlank() }
                ?: if (page == fallbackCurrentSessionPage) uiState.artworkBase64 else ""
            val artToken = tile?.artworkToken.takeIf { !it.isNullOrBlank() }
                ?: if (page == fallbackCurrentSessionPage) uiState.artworkToken else ""
            val tileBitmap = remember(artToken, artBase64) {
                decodeWearArtworkJpeg(artBase64)
            }
            if (tileBitmap != null && !tileBitmap.isRecycled) {
                Image(
                    bitmap = tileBitmap.asImageBitmap(),
                    contentDescription = "Session artwork",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
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

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 10.dp, top = 4.dp, bottom = 10.dp)
                .padding(end = if (uiState.sessionCount > 1) 4.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Text(
                        text = titleText,
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

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(top = TEXT_ANCHOR_GAP),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    if (albumLine.isNotBlank()) {
                        Text(
                            text = albumLine,
                            style = overlayBodyStyle(MaterialTheme.typography.body2),
                            textAlign = TextAlign.Center,
                            softWrap = true,
                            maxLines = 16,
                            overflow = TextOverflow.Visible,
                        )
                    }
                }
            }

            if (safePageCount > 1) {
                SessionPageDots(
                    selectedIndexOneBased = pagerState.currentPage + 1,
                    pageCount = safePageCount,
                    vertical = true,
                    modifier = Modifier.padding(start = 2.dp, end = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun ActivePlayerScreen(
    uiState: PlayerUiState,
    onTogglePlayback: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    onSelectSessionAt: (Int) -> Unit,
    canTogglePlayback: Boolean,
    canSkipPrevious: Boolean,
    canSkipNext: Boolean,
) {
    val carouselTiles = uiState.sessionCarousel
    if (uiState.sessionCount >= 2) {
        CarouselSessionActiveContent(
            uiState = uiState,
            tiles = carouselTiles,
            pageCount = uiState.sessionCount,
            onTogglePlayback = onTogglePlayback,
            onSkipPrevious = onSkipPrevious,
            onSkipNext = onSkipNext,
            onSelectSessionAt = onSelectSessionAt,
            canTogglePlayback = canTogglePlayback,
            canSkipPrevious = canSkipPrevious,
            canSkipNext = canSkipNext,
        )
        return
    }

    val albumOrShow = when {
        uiState.artist.isNotBlank() -> uiState.artist
        uiState.appName.isNotBlank() -> uiState.appName
        else -> ""
    }

    val artworkBitmap = remember(uiState.artworkToken, uiState.artworkBase64) {
        decodeWearArtworkJpeg(uiState.artworkBase64)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
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

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 10.dp, top = 4.dp, bottom = 10.dp)
                    .padding(end = if (uiState.sessionCount > 1) 4.dp else 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
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

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(top = TEXT_ANCHOR_GAP),
                        contentAlignment = Alignment.TopCenter,
                    ) {
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

                if (uiState.sessionCount > 1) {
                    SessionPageDots(
                        selectedIndexOneBased = uiState.sessionIndex,
                        pageCount = uiState.sessionCount,
                        vertical = true,
                        modifier = Modifier.padding(start = 2.dp, end = 6.dp),
                    )
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
    vertical: Boolean = false,
) {
    if (pageCount <= 1) return
    val selectedZero = (selectedIndexOneBased - 1).coerceIn(0, pageCount - 1)
    @Composable
    fun Dot(index: Int) {
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
    if (vertical) {
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            repeat(pageCount) { index ->
                Dot(index)
            }
        }
    } else {
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(pageCount) { index ->
                Dot(index)
            }
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
