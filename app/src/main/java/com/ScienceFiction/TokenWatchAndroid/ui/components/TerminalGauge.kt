package com.ScienceFiction.TokenWatchAndroid.ui.components

import android.animation.ValueAnimator
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ScienceFiction.TokenWatchAndroid.ui.theme.Term
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Continuous usage gauge: dotted track, solid used fill, optional elapsed-time
 * marker, and a hopping slime when the displayed value rounds to 100%.
 */
@Composable
fun TerminalGauge(
    usedFraction: Double,
    fillColor: Color,
    elapsedFraction: Double?,
    modifier: Modifier = Modifier,
    height: Dp = 14.dp,
    bracketSize: TextUnit = 13.sp,
    gaugeCritterEnabled: Boolean = true,
    reduceMotion: Boolean = !ValueAnimator.areAnimatorsEnabled(),
    usedContentDescription: (Int) -> String = { "$it% used" },
) {
    val used = usedFraction.coerceIn(0.0, 1.0)
    val elapsed = elapsedFraction?.coerceIn(0.0, 1.0)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription = usedContentDescription((used * 100.0).roundToInt())
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GaugeBracket("[", bracketSize)
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 3.dp)
                .height(height)
                .clipToBounds(),
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawDottedTrack(Term.Dim)
                drawRect(
                    color = fillColor,
                    size = Size(
                        width = (size.width * used).toFloat().coerceAtLeast(0f),
                        height = size.height,
                    ),
                )
                if (elapsed != null) {
                    drawElapsedMarker(elapsed)
                }
            }

            if (gaugeCritterEnabled && used >= GaugeCritterMath.Threshold) {
                GaugeCritter(
                    modifier = Modifier.fillMaxSize(),
                    reduceMotion = reduceMotion,
                )
            }
        }
        GaugeBracket("]", bracketSize)
    }
}

/** Animated slime overlay. Animator scale 0 pins the landing frame at 60%. */
@Composable
fun GaugeCritter(
    modifier: Modifier = Modifier,
    sprite: PixelSprite = PixelSprite.Slime,
    reduceMotion: Boolean = !ValueAnimator.areAnimatorsEnabled(),
) {
    var tick by remember(sprite, reduceMotion) { mutableIntStateOf(0) }
    LaunchedEffect(sprite, reduceMotion) {
        tick = 0
        if (!reduceMotion) {
            while (isActive) {
                delay(GaugeCritterMath.TickMillis)
                tick += 1
            }
        }
    }

    Canvas(
        modifier = modifier
            .clipToBounds()
            .clearAndSetSemantics { },
    ) {
        val verticalInset = 2.dp.toPx()
        val bottomInset = 1.dp.toPx()
        val cell = max(0f, size.height - verticalInset) / sprite.rows
        val spriteWidth = cell * sprite.columns
        val x = if (reduceMotion) {
            size.width * 0.6f
        } else {
            GaugeCritterMath.offsetX(
                tick = tick,
                hop = (cell * GaugeCritterMath.HopCells).toDouble(),
                spriteWidth = spriteWidth.toDouble(),
                barWidth = size.width.toDouble(),
            ).toFloat()
        }
        val y = size.height - bottomInset - cell * sprite.rows
        drawPixelSpriteFrame(
            frame = sprite.frame(if (reduceMotion) 0 else GaugeCritterMath.frameIndex(tick)),
            palette = sprite.palette,
            cell = cell,
            origin = Offset(x, y),
        )
    }
}

/** Pure iOS-compatible critter thresholds and hopping arithmetic. */
object GaugeCritterMath {
    const val Threshold = 0.995
    const val TickMillis = 250L
    const val HopCells = 4

    fun frameIndex(tick: Int): Int = (abs(tick.toLong()) % 2L).toInt()

    fun offsetX(
        tick: Int,
        hop: Double,
        spriteWidth: Double,
        barWidth: Double,
    ): Double {
        if (hop <= 0.0 || barWidth <= 0.0) return -spriteWidth
        val hopsPerCross = ceil((barWidth + spriteWidth) / hop).toInt().coerceAtLeast(1)
        val absoluteTick = abs(tick.toLong())
        val hopIndex = ((absoluteTick + 1L) / 2L % hopsPerCross.toLong()).toInt()
        return hopIndex * hop - spriteWidth
    }
}

@Composable
private fun GaugeBracket(text: String, size: TextUnit) {
    Text(
        text = text,
        color = Term.Dim,
        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = size),
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDottedTrack(color: Color) {
    val dot = 1.2.dp.toPx()
    val step = 2.dp.toPx()
    var row = 0
    var y = 1.dp.toPx()
    while (y < size.height) {
        var x = if (row % 2 == 0) 1.dp.toPx() else 1.dp.toPx() + step / 2f
        while (x < size.width) {
            drawOval(
                color = color.copy(alpha = 0.7f),
                topLeft = Offset(x, y),
                size = Size(dot, dot),
            )
            x += step
        }
        y += step
        row += 1
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawElapsedMarker(elapsed: Double) {
    val markerWidth = 2.dp.toPx()
    val minimumCenter = 1.dp.toPx()
    val maximumCenter = (size.width - 1.dp.toPx()).coerceAtLeast(minimumCenter)
    val center = (size.width * elapsed).toFloat().coerceIn(minimumCenter, maximumCenter)
    val topLeft = Offset(center - markerWidth / 2f, 0f)

    // Compact dark halo approximates the iOS 1.5pt shadow on either fill color.
    val halo = 1.5.dp.toPx()
    drawRect(
        color = Color.Black.copy(alpha = 0.5f),
        topLeft = Offset(topLeft.x - halo / 2f, 0f),
        size = Size(markerWidth + halo, size.height),
    )
    drawRect(
        color = Color.White,
        topLeft = topLeft,
        size = Size(markerWidth, size.height),
    )
    drawRect(
        color = Color.Black.copy(alpha = 0.45f),
        topLeft = topLeft,
        size = Size(markerWidth, size.height),
        style = Stroke(width = 0.5.dp.toPx()),
    )
}
