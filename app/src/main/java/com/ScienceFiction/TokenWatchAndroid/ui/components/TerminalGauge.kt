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
import com.ScienceFiction.TokenWatchAndroid.domain.WindowKind
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/** The gauge monsters' pace by window: one tick of their hop march. */
enum class GaugeCritterVariant(
    val baseTickMillis: Long,
) {
    WEEKLY(285L),
    SESSION(219L),
    ;

    companion object {
        fun from(kind: WindowKind): GaugeCritterVariant =
            if (kind == WindowKind.SESSION) SESSION else WEEKLY
    }
}

/**
 * Continuous usage gauge: dotted track, solid fill, optional elapsed-time marker, and the window's
 * monsters when it has any. Nothing else is drawn on the bar: whatever walks on it can be fought
 * (D023). Credit gauges keep [usedFraction] as consumption but set [fillsRemaining] so the visible
 * fill runs in the opposite direction.
 */
@Composable
fun TerminalGauge(
    usedFraction: Double,
    fillColor: Color,
    elapsedFraction: Double?,
    markerPaused: Boolean = false,
    modifier: Modifier = Modifier,
    fillsRemaining: Boolean = false,
    height: Dp = 14.dp,
    bracketSize: TextUnit = 13.sp,
    /** Sets the monsters' pace: session windows walk faster. */
    critterVariant: GaugeCritterVariant = GaugeCritterVariant.WEEKLY,
    reduceMotion: Boolean = !ValueAnimator.areAnimatorsEnabled(),
    usedContentDescription: (Int) -> String = { "$it% used" },
    remainingContentDescription: (Int) -> String = { "$it% left" },
    /**
     * This window's monsters (V2). Null draws none: no monster UI, no monster on this window, or the
     * "gauge monsters" setting off (the caller's [rememberGaugeMonsters] returns null then).
     */
    monsters: GaugeMonsters? = null,
) {
    val used = usedFraction.coerceIn(0.0, 1.0)
    val fill = TerminalGaugeMath.fillFraction(used = used, fillsRemaining = fillsRemaining)
    val elapsed = elapsedFraction?.coerceIn(0.0, 1.0)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription = if (fillsRemaining) {
                    remainingContentDescription(((1.0 - used) * 100.0).roundToInt())
                } else {
                    usedContentDescription((used * 100.0).roundToInt())
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GaugeBracket("[", bracketSize)
        // Not clipped as a whole: a monster's sword and halo rise above the bar. Each layer clips itself.
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 3.dp)
                .height(height),
        ) {
            Canvas(modifier = Modifier.fillMaxSize().clipToBounds()) {
                drawDottedTrack(Term.Dim)
                drawRect(
                    color = fillColor,
                    size = Size(
                        width = (size.width * fill).toFloat().coerceAtLeast(0f),
                        height = size.height,
                    ),
                )
                if (elapsed != null) {
                    drawElapsedMarker(elapsed, markerPaused)
                }
            }

            if (monsters != null) {
                GaugeMonsterLayer(
                    monsters = monsters,
                    sessionWindow = critterVariant == GaugeCritterVariant.SESSION,
                    modifier = Modifier.fillMaxSize(),
                    reduceMotion = reduceMotion,
                )
            }
        }
        GaugeBracket("]", bracketSize)
    }
}

/** Pure fill-direction arithmetic shared with unit tests. */
object TerminalGaugeMath {
    fun fillFraction(used: Double, fillsRemaining: Boolean): Double {
        val clamped = used.coerceIn(0.0, 1.0)
        return if (fillsRemaining) 1.0 - clamped else clamped
    }
}

/**
 * Pure iOS-compatible hopping arithmetic: the march the gauge monsters walk ([GaugeMonsterMotion]
 * builds on [offsetX] and [HopCells]).
 */
object GaugeCritterMath {
    const val HopCells = 4

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

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawElapsedMarker(
    elapsed: Double,
    paused: Boolean,
) {
    val markerWidth = 2.dp.toPx()
    val minimumCenter = 1.dp.toPx()
    val maximumCenter = (size.width - 1.dp.toPx()).coerceAtLeast(minimumCenter)
    val center = (size.width * elapsed).toFloat().coerceIn(minimumCenter, maximumCenter)
    val topLeft = Offset(center - markerWidth / 2f, 0f)

    // Compact dark halo approximates the iOS 1.5pt shadow on either fill color.
    val halo = 1.5.dp.toPx()
    if (!paused) drawRect(
        color = Color.Black.copy(alpha = 0.5f), topLeft = Offset(topLeft.x - halo / 2f, 0f),
        size = Size(markerWidth + halo, size.height),
    )
    drawRect(
        color = if (paused) Term.Dim.copy(alpha = 0.5f) else Color.White,
        topLeft = topLeft,
        size = Size(markerWidth, size.height),
    )
    drawRect(
        color = Color.Black.copy(alpha = if (paused) 0.2f else 0.45f),
        topLeft = topLeft,
        size = Size(markerWidth, size.height),
        style = Stroke(width = 0.5.dp.toPx()),
    )
}
