package com.ScienceFiction.TokenWatchAndroid

import android.os.Bundle
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.ScienceFiction.TokenWatchAndroid.ui.TokenWatchApp
import com.ScienceFiction.TokenWatchAndroid.ui.theme.TokenWatchTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        requestedOrientation = if (resources.configuration.smallestScreenWidthDp >= 600) {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TokenWatchTheme {
                TokenWatchApp()
            }
        }
    }
}
