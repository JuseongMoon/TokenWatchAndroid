package com.ScienceFiction.TokenWatchAndroid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.ScienceFiction.TokenWatchAndroid.ui.TokenWatchApp
import com.ScienceFiction.TokenWatchAndroid.ui.theme.TokenWatchTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TokenWatchTheme {
                TokenWatchApp()
            }
        }
    }
}
