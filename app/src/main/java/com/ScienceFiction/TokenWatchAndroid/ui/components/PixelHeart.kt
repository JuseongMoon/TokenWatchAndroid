package com.ScienceFiction.TokenWatchAndroid.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Vertical slice of the 11x10 pixel-heart bitmap. */
enum class HeartPart {
    Full,
    Left,
    Right,
}

/**
 * Glossy 11x10 pixel heart. A flat tint is used by disabled previews, while
 * [outline] renders only the white edge for a fully depleted usage window.
 */
@Composable
fun PixelHeart(
    modifier: Modifier = Modifier,
    flatColor: Color? = null,
    part: HeartPart = HeartPart.Full,
    outline: Boolean = false,
    size: Dp = 11.dp,
) {
    val width = size * (PixelHeartData.columns.toFloat() / PixelHeartData.rows.toFloat())
    Canvas(
        modifier = modifier
            .requiredSize(width = width, height = size)
            .clearAndSetSemantics { },
    ) {
        val cell = this.size.height / PixelHeartData.rows
        val centerColumn = PixelHeartData.columns / 2
        for ((rowIndex, row) in PixelHeartData.bitmap.withIndex()) {
            for ((columnIndex, value) in row.withIndex()) {
                if (value == 0) continue
                when (part) {
                    HeartPart.Full -> Unit
                    HeartPart.Left -> if (columnIndex > centerColumn) continue
                    HeartPart.Right -> if (columnIndex <= centerColumn) continue
                }
                if (outline && !PixelHeartData.isEdge(rowIndex, columnIndex)) {
                    continue
                }
                val color = when {
                    outline -> Color.White
                    flatColor != null -> flatColor
                    else -> PixelHeartData.palette.getValue(value)
                }
                drawRect(
                    color = color,
                    topLeft = Offset(columnIndex * cell, rowIndex * cell),
                    size = Size(cell, cell),
                )
            }
        }
    }
}

/**
 * Up to five hearts representing remaining usage in half-heart (10%) steps.
 * Only the final half-heart blinks; at 100% used, a white outline blinks.
 */
@Composable
fun HeartHealthBar(
    usedPercent: Double,
    modifier: Modifier = Modifier,
    size: Dp = 11.dp,
    spacing: Dp = 2.dp,
    usedContentDescription: (Int) -> String = { "$it% used" },
) {
    val halfHearts = remainingHalfHearts(usedPercent)
    Row(
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = usedContentDescription(usedPercent.roundToInt())
        },
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (halfHearts == 0) {
            TerminalBlink {
                PixelHeart(outline = true, size = size)
            }
        } else {
            val slots = (halfHearts + 1) / 2
            repeat(slots) { index ->
                when {
                    index < slots - 1 -> PixelHeart(size = size)
                    halfHearts % 2 == 0 -> SplitBlinkingHeart(size = size)
                    else -> TerminalBlink {
                        PixelHeart(part = HeartPart.Left, size = size)
                    }
                }
            }
        }
    }
}

/** Converts used percent to the iOS-compatible range of 0...10 half-hearts. */
fun remainingHalfHearts(usedPercent: Double): Int {
    val used = usedPercent.coerceIn(0.0, 100.0)
    return (10 - (used / 10.0).toInt()).coerceAtLeast(0)
}

@Composable
private fun SplitBlinkingHeart(size: Dp) {
    val width = size * (PixelHeartData.columns.toFloat() / PixelHeartData.rows.toFloat())
    Box(
        modifier = Modifier.requiredSize(width = width, height = size),
        contentAlignment = Alignment.TopStart,
    ) {
        PixelHeart(part = HeartPart.Left, size = size)
        TerminalBlink {
            PixelHeart(part = HeartPart.Right, size = size)
        }
    }
}

internal object PixelHeartData {
    val bitmap = listOf(
        listOf(0, 0, 1, 1, 0, 0, 0, 1, 1, 0, 0),
        listOf(0, 1, 2, 2, 1, 0, 1, 1, 1, 1, 0),
        listOf(1, 2, 2, 1, 1, 1, 1, 1, 1, 1, 1),
        listOf(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 3),
        listOf(1, 1, 1, 1, 1, 1, 1, 1, 1, 3, 3),
        listOf(0, 1, 1, 1, 1, 1, 1, 1, 3, 3, 0),
        listOf(0, 0, 1, 1, 1, 1, 1, 3, 3, 0, 0),
        listOf(0, 0, 0, 1, 1, 1, 3, 3, 0, 0, 0),
        listOf(0, 0, 0, 0, 1, 1, 3, 0, 0, 0, 0),
        listOf(0, 0, 0, 0, 0, 3, 0, 0, 0, 0, 0),
    )

    val palette = mapOf(
        1 to Color(0xFFFF3345),
        2 to Color(0xFFFFD1DB),
        3 to Color(0xFFC21830),
    )

    val rows: Int = bitmap.size
    val columns: Int = bitmap.first().size

    fun isEdge(row: Int, column: Int): Boolean {
        val neighbors = arrayOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)
        return neighbors.any { (rowOffset, columnOffset) ->
            val neighborRow = row + rowOffset
            val neighborColumn = column + columnOffset
            neighborRow !in bitmap.indices ||
                neighborColumn !in bitmap.first().indices ||
                bitmap[neighborRow][neighborColumn] == 0
        }
    }
}
