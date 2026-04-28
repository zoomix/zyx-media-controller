package se.kvrgic.mediawristremote.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.activity.OnBackPressedCallback
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.wear.compose.material.MaterialTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MediaControlViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Horizontal session swipes are easy to misread as the system back/back-stack gesture.
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    // Consume — stay in-app; exit via launcher / recents.
                }
            },
        )
        setContent {
            MaterialTheme {
                val uiState by viewModel.uiState.collectAsState()
                PlayerScreen(
                    uiState = uiState,
                    onRefresh = viewModel::refreshConnection,
                    onTogglePlayback = viewModel::togglePlayback,
                    onSkipPrevious = viewModel::skipPrevious,
                    onSkipNext = viewModel::skipNext,
                    onSelectPreviousSession = viewModel::selectPreviousSession,
                    onSelectNextSession = viewModel::selectNextSession,
                    canTogglePlayback = viewModel.canTogglePlayback(),
                    canSkipPrevious = viewModel.canSkipPrevious(),
                    canSkipNext = viewModel.canSkipNext(),
                    canSwitchSessions = viewModel.canSwitchSessions(),
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.startListening()
    }

    override fun onStop() {
        viewModel.stopListening()
        super.onStop()
    }
}
