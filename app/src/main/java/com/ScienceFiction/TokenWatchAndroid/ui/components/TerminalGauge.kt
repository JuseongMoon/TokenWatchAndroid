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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random

enum class GaugeCritterVariant(
    val sprite: PixelSprite,
    val baseTickMillis: Long,
) {
    WEEKLY(PixelSprite.Slime, 285L),
    SESSION(PixelSprite.SlimeSky, 219L),
    ;

    companion object {
        fun from(kind: WindowKind): GaugeCritterVariant =
            if (kind == WindowKind.SESSION) SESSION else WEEKLY
    }
}

/**
 * Continuous usage gauge: dotted track, solid fill, optional elapsed-time marker, and a hopping
 * slime when the consumed value rounds to 100%. Credit gauges keep [usedFraction] as consumption
 * but set [fillsRemaining] so the visible fill runs in the opposite direction.
 */
@Composable
fun TerminalGauge(
    usedFraction: Double,
    fillColor: Color,
    elapsedFraction: Double?,
    modifier: Modifier = Modifier,
    fillsRemaining: Boolean = false,
    height: Dp = 14.dp,
    bracketSize: TextUnit = 13.sp,
    critterVariant: GaugeCritterVariant = GaugeCritterVariant.WEEKLY,
    gaugeCritterEnabled: Boolean = true,
    reduceMotion: Boolean = !ValueAnimator.areAnimatorsEnabled(),
    usedContentDescription: (Int) -> String = { "$it% used" },
    remainingContentDescription: (Int) -> String = { "$it% left" },
) {
    val used = usedFraction.coerceIn(0.0, 1.0)
    val fill = TerminalGaugeMath.fillFraction(used = used, fillsRemaining = fillsRemaining)
    val elapsed = elapsedFraction?.coerceIn(0.0, 1.0)
    val showCritter = gaugeCritterEnabled && used >= GaugeCritterMath.Threshold
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
                        width = (size.width * fill).toFloat().coerceAtLeast(0f),
                        height = size.height,
                    ),
                )
                if (elapsed != null) {
                    drawElapsedMarker(elapsed)
                }
            }

            AnimatedGaugeCritter(
                visible = showCritter,
                modifier = Modifier.fillMaxSize(),
                reduceMotion = reduceMotion,
                variant = critterVariant,
            )
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

/** Keeps the stepped fade local to the slime overlay so usage-fill changes remain immediate. */
@Composable
private fun AnimatedGaugeCritter(
    visible: Boolean,
    modifier: Modifier = Modifier,
    reduceMotion: Boolean,
    variant: GaugeCritterVariant,
) {
    var rendered by remember { mutableStateOf(false) }
    var opacityStep by remember { mutableIntStateOf(0) }

    LaunchedEffect(visible, reduceMotion) {
        if (reduceMotion) {
            opacityStep = if (visible) GaugeCritterMath.OpacitySteps else 0
            rendered = visible
            return@LaunchedEffect
        }

        val stepDelay = GaugeCritterMath.FadeDurationMillis / GaugeCritterMath.OpacitySteps
        if (visible) {
            rendered = true
            for (step in opacityStep..GaugeCritterMath.OpacitySteps) {
                opacityStep = step
                if (step < GaugeCritterMath.OpacitySteps) delay(stepDelay)
            }
        } else {
            for (step in opacityStep downTo 0) {
                opacityStep = step
                if (step > 0) delay(stepDelay)
            }
            rendered = false
        }
    }

    if (rendered) {
        GaugeCritter(
            modifier = modifier.alpha(
                GaugeCritterMath.steppedOpacity(
                    opacityStep.toDouble() / GaugeCritterMath.OpacitySteps,
                ).toFloat(),
            ),
            reduceMotion = reduceMotion,
            variant = variant,
        )
    }
}

/** Animated slime overlay. Animator scale 0 pins the landing frame at 60%. */
@Composable
fun GaugeCritter(
    modifier: Modifier = Modifier,
    variant: GaugeCritterVariant = GaugeCritterVariant.WEEKLY,
    reduceMotion: Boolean = !ValueAnimator.areAnimatorsEnabled(),
) {
    val sprite = variant.sprite
    val speedFactor = remember(variant) { Random.nextDouble(0.95, 1.05) }
    val tickMillis = remember(variant, speedFactor) {
        (variant.baseTickMillis / speedFactor).toLong().coerceAtLeast(1L)
    }
    var tick by remember(variant, reduceMotion) { mutableIntStateOf(0) }
    LaunchedEffect(variant, reduceMotion, tickMillis) {
        tick = 0
        if (!reduceMotion) {
            while (isActive) {
                delay(tickMillis)
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
    const val HopCells = 4
    const val OpacitySteps = 8
    const val FadeDurationMillis = 600L

    fun frameIndex(tick: Int): Int = (abs(tick.toLong()) % 2L).toInt()

    /** Quantizes a clamped 0...1 animation progress to eighth-step opacity levels. */
    fun steppedOpacity(progress: Double): Double {
        val clamped = progress.coerceIn(0.0, 1.0)
        return kotlin.math.floor(clamped * OpacitySteps) / OpacitySteps
    }

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
