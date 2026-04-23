package com.zyx.mediacontroller.phone

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
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

class OnboardingActivity : ComponentActivity() {
    private var notificationAccessEnabled by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshState()
        setContent {
            MaterialTheme {
                Surface {
                    OnboardingScreen(
                        notificationAccessEnabled = notificationAccessEnabled,
                        onOpenSettings = ::openNotificationAccessSettings,
                        onRefresh = ::refreshState,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshState()
    }

    private fun refreshState() {
        notificationAccessEnabled = NotificationAccess.isEnabled(this)
    }

    private fun openNotificationAccessSettings() {
        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }
}

@Composable
private fun OnboardingScreen(
    notificationAccessEnabled: Boolean,
    onOpenSettings: () -> Unit,
    onRefresh: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Wear Media Controller",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Card(modifier = Modifier.padding(top = 16.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = if (notificationAccessEnabled) {
                        "Notification access is enabled. Start media on the phone, then open the watch app."
                    } else {
                        "Enable Notification Access so the phone can discover active media sessions and relay controls to the watch."
                    },
                    textAlign = TextAlign.Center,
                )
            }
        }
        Spacer(modifier = Modifier.size(16.dp))
        Button(onClick = onOpenSettings) {
            Text(
                text = if (notificationAccessEnabled) {
                    "Open Notification Access"
                } else {
                    "Enable Notification Access"
                },
            )
        }
        Spacer(modifier = Modifier.size(12.dp))
        Button(
            onClick = onRefresh,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
        ) {
            Text("Refresh status")
        }
        Spacer(modifier = Modifier.size(12.dp))
        Text(
            text = if (notificationAccessEnabled) {
                "Bridge status: ready to mirror the active session."
            } else {
                "Bridge status: waiting for permission."
            },
            textAlign = TextAlign.Center,
        )
    }
}
