package com.ScienceFiction.TokenWatchAndroid.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

object Term {
    val Background = Color(0xFF000000)
    val Foreground = Color(0xFFD6DBD1)
    val Dim = Color(0xFF6B7A6B)
    val Cyan = Color(0xFF56D6D6)
    val Green = Color(0xFF4CD07A)
    val Yellow = Color(0xFFE6C34D)
    val Red = Color(0xFFF0574C)
    val Orange = Color(0xFFF2944D)
    val Magenta = Color(0xFFCC85E6)
    val Blue = Color(0xFF669EF2)
    val Pink = Color(0xFFF273A6)
    val Teal = Color(0xFF4CC7B3)
    val Track = Color(0xFF242B21)

    fun statusColor(remainingPercent: Double): Color = when {
        remainingPercent < 10.0 -> Red
        remainingPercent < 25.0 -> Yellow
        else -> Green
    }
}

private val terminalTypography = Typography(
    bodyLarge = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 19.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
)

@Composable
fun TokenWatchTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Term.Green,
            secondary = Term.Cyan,
            background = Term.Background,
            surface = Term.Background,
            onPrimary = Term.Background,
            onSecondary = Term.Background,
            onBackground = Term.Foreground,
            onSurface = Term.Foreground,
            error = Term.Red,
        ),
        typography = terminalTypography,
        content = content,
    )
}
