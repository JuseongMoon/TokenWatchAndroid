package com.ScienceFiction.TokenWatchAndroid

import android.content.Intent
import android.os.Bundle
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ScienceFiction.TokenWatchAndroid.notifications.ResetNotificationManager
import com.ScienceFiction.TokenWatchAndroid.ui.TokenWatchApp
import com.ScienceFiction.TokenWatchAndroid.ui.theme.TokenWatchTheme

class MainActivity : ComponentActivity() {
    /**
     * Agent carried by a reset notification the user tapped, consumed once by the UI. Held as
     * state rather than read from `intent` directly because the launcher activity is
     * SINGLE_TOP: a second notification arrives through [onNewIntent], not a new instance.
     */
    private var notificationAgentId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        requestedOrientation = if (resources.configuration.smallestScreenWidthDp >= 600) {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
        super.onCreate(savedInstanceState)
        notificationAgentId = intent?.getStringExtra(ResetNotificationManager.ExtraAgentId)
        enableEdgeToEdge()
        setContent {
            TokenWatchTheme {
                TokenWatchApp(
                    notificationAgentId = notificationAgentId,
                    onNotificationHandled = { notificationAgentId = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        notificationAgentId = intent.getStringExtra(ResetNotificationManager.ExtraAgentId)
    }
}
