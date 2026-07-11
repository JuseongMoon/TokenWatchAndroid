package com.ScienceFiction.TokenWatchAndroid.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ScienceFiction.TokenWatchAndroid.ui.theme.Term
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Hard on/off terminal caret. One full blink cycle lasts [periodMillis]. */
@Composable
fun BlinkingCursor(
    modifier: Modifier = Modifier,
    symbol: String = "█",
    color: Color = Term.Green,
    size: TextUnit = 14.sp,
    periodMillis: Long = 1_000L,
) {
    val phase = rememberSteppedFrame(
        frameCount = 2,
        frameMillis = (periodMillis / 2L).coerceAtLeast(1L),
    )
    Text(
        text = symbol,
        color = color,
        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = size),
        modifier = modifier
            .alpha(if (phase == 0) 1f else 0f)
            .clearAndSetSemantics { },
    )
}

/** Makes arbitrary terminal content blink with the same hard-edged rhythm. */
@Composable
fun TerminalBlink(
    modifier: Modifier = Modifier,
    periodMillis: Long = 1_000L,
    content: @Composable () -> Unit,
) {
    val phase = rememberSteppedFrame(
        frameCount = 2,
        frameMillis = (periodMillis / 2L).coerceAtLeast(1L),
    )
    Box(modifier = modifier.alpha(if (phase == 0) 1f else 0f)) {
        content()
    }
}

/** Single blinking pixel heart used as an alternative prompt cursor. */
@Composable
fun BlinkingHeart(
    modifier: Modifier = Modifier,
    size: Dp = 11.dp,
    periodMillis: Long = 1_000L,
) {
    TerminalBlink(modifier = modifier, periodMillis = periodMillis) {
        PixelHeart(size = size)
    }
}

/** Eight-frame braille spinner, advanced every 100 ms like the iOS source. */
@Composable
fun TerminalSpinner(
    modifier: Modifier = Modifier,
    color: Color = Term.Green,
    size: TextUnit = 14.sp,
) {
    val index = rememberSteppedFrame(
        frameCount = TerminalSpinnerFrames.size,
        frameMillis = 100L,
    )
    Text(
        text = TerminalSpinnerFrames[index],
        color = color,
        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = size),
        modifier = modifier.clearAndSetSemantics { },
    )
}

private val TerminalSpinnerFrames = listOf("⣾", "⣽", "⣻", "⢿", "⡿", "⣟", "⣯", "⣷")

@Composable
internal fun rememberSteppedFrame(frameCount: Int, frameMillis: Long): Int {
    require(frameCount > 0)
    var frame by remember(frameCount, frameMillis) { mutableIntStateOf(0) }
    LaunchedEffect(frameCount, frameMillis) {
        while (isActive) {
            delay(frameMillis.coerceAtLeast(1L))
            frame = (frame + 1) % frameCount
        }
    }
    return frame
}
