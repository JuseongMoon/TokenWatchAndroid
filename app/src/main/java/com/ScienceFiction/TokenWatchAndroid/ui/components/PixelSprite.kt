package com.ScienceFiction.TokenWatchAndroid.ui.components

import android.animation.ValueAnimator
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Rectangular integer-grid animation frames with a shared color palette. */
data class PixelSprite(
    val frames: List<List<List<Int>>>,
    val palette: Map<Int, Color>,
) {
    init {
        require(frames.isNotEmpty()) { "A pixel sprite needs at least one frame" }
        require(frames.first().isNotEmpty()) { "A pixel sprite frame needs at least one row" }
        require(frames.first().first().isNotEmpty()) { "A pixel sprite row needs at least one column" }
        val expectedRows = frames.first().size
        val expectedColumns = frames.first().first().size
        require(frames.all { frame ->
            frame.size == expectedRows && frame.all { it.size == expectedColumns }
        }) { "All pixel sprite frames must share one grid size" }
    }

    val rows: Int = frames.first().size
    val columns: Int = frames.first().first().size

    fun frame(index: Int): List<List<Int>> = frames[Math.floorMod(index, frames.size)]

    companion object {
        /** Two-frame green slime: squash/landing followed by stretch/jump. */
        val Slime = PixelSprite(
            frames = listOf(
                listOf(
                    listOf(0, 0, 0, 0, 0, 0, 0, 0, 0),
                    listOf(0, 0, 0, 0, 0, 0, 0, 0, 0),
                    listOf(0, 0, 1, 2, 1, 1, 1, 0, 0),
                    listOf(0, 1, 2, 1, 1, 1, 1, 1, 0),
                    listOf(1, 1, 4, 1, 1, 1, 4, 1, 1),
                    listOf(1, 1, 1, 1, 1, 1, 1, 3, 3),
                    listOf(0, 1, 1, 1, 1, 1, 3, 3, 0),
                ),
                listOf(
                    listOf(0, 0, 0, 1, 1, 1, 0, 0, 0),
                    listOf(0, 0, 1, 2, 1, 1, 1, 0, 0),
                    listOf(0, 1, 2, 1, 1, 1, 1, 1, 0),
                    listOf(0, 1, 1, 4, 1, 4, 1, 1, 0),
                    listOf(0, 1, 1, 1, 1, 1, 1, 3, 0),
                    listOf(0, 1, 1, 1, 1, 1, 3, 3, 0),
                    listOf(0, 0, 1, 1, 1, 3, 3, 0, 0),
                ),
            ),
            palette = mapOf(
                1 to Color(0xFF47D966),
                2 to Color(0xFFBFF8C7),
                3 to Color(0xFF29A64A),
                4 to Color(0xFF052614),
            ),
        )
    }
}

/** Renders one [PixelSprite] frame with square density-independent cells. */
@Composable
fun PixelSpriteView(
    sprite: PixelSprite,
    frameIndex: Int,
    cell: Dp,
    modifier: Modifier = Modifier,
    flatColor: Color? = null,
) {
    Canvas(
        modifier = modifier
            .then(
                Modifier.pixelSpriteSize(
                    width = cell * sprite.columns,
                    height = cell * sprite.rows,
                ),
            )
            .clearAndSetSemantics { },
    ) {
        val cellPixels = this.size.height / sprite.rows
        drawPixelSpriteFrame(
            frame = sprite.frame(frameIndex),
            palette = sprite.palette,
            cell = cellPixels,
            flatColor = flatColor,
        )
    }
}

/**
 * Toggles a sprite's frames in place for compact previews. Disabling system animations pins the
 * sprite to its landing frame, matching the stationary gauge critter behavior.
 */
@Composable
fun AnimatedPixelSpriteView(
    sprite: PixelSprite,
    cell: Dp,
    modifier: Modifier = Modifier,
    flatColor: Color? = null,
    tickMillis: Long = 250L,
    reduceMotion: Boolean = !ValueAnimator.areAnimatorsEnabled(),
) {
    require(tickMillis > 0L) { "Sprite animation tick must be positive" }

    var tick by remember(sprite, tickMillis, reduceMotion) { mutableIntStateOf(0) }
    LaunchedEffect(sprite, tickMillis, reduceMotion) {
        tick = 0
        if (!reduceMotion) {
            while (isActive) {
                delay(tickMillis)
                tick += 1
            }
        }
    }

    PixelSpriteView(
        sprite = sprite,
        frameIndex = if (reduceMotion) 0 else tick,
        cell = cell,
        modifier = modifier,
        flatColor = flatColor,
    )
}

private fun Modifier.pixelSpriteSize(width: Dp, height: Dp): Modifier =
    requiredSize(width = width, height = height)

internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPixelSpriteFrame(
    frame: List<List<Int>>,
    palette: Map<Int, Color>,
    cell: Float,
    flatColor: Color? = null,
    origin: Offset = Offset.Zero,
) {
    for ((rowIndex, row) in frame.withIndex()) {
        for ((columnIndex, value) in row.withIndex()) {
            if (value == 0) continue
            val color = flatColor ?: palette[value] ?: continue
            drawRect(
                color = color,
                topLeft = Offset(
                    x = origin.x + columnIndex * cell,
                    y = origin.y + rowIndex * cell,
                ),
                size = Size(cell, cell),
            )
        }
    }
}
