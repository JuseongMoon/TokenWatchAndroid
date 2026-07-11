package com.ScienceFiction.TokenWatchAndroid.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ScienceFiction.TokenWatchAndroid.ui.theme.Term

/**
 * Rectangular terminal container used by cards and settings sections.
 *
 * The title deliberately sits inside the border as the first line, matching
 * the clean iOS 6df2689 implementation rather than cutting through the edge.
 */
@Composable
fun TerminalBox(
    title: String? = null,
    modifier: Modifier = Modifier,
    titleColor: Color = Term.Cyan,
    borderColor: Color = Term.Dim,
    contentPadding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Term.Background)
            .terminalRectBorder(width = 1.5.dp, color = borderColor)
            .padding(contentPadding),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        if (title != null) {
            Text(
                text = title,
                color = titleColor,
                maxLines = 1,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    shadow = terminalGlow(titleColor, radius = 2f),
                ),
            )
        }

        CompositionLocalProvider(
            LocalTextStyle provides TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                color = Term.Foreground,
            ),
        ) {
            content()
        }
    }
}

/** Plain prompt-style button without Material shape, elevation, or ripple. */
@Composable
fun TerminalButton(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Term.Green,
    dashedBorder: Boolean = false,
    enabled: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .terminalButtonBorder(color = color, dashed = dashedBorder)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title,
            color = color,
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                shadow = terminalGlow(color, radius = 2f),
            ),
        )
    }
}

/** Monospaced key/value row whose colons stay aligned across a section. */
@Composable
fun KvRow(
    key: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = Term.Foreground,
    keyWidth: Dp = 84.dp,
) {
    Row(modifier = modifier.fillMaxWidth()) {
        Text(
            text = key,
            color = Term.Cyan,
            style = terminalBodyStyle(),
            modifier = Modifier
                .width(keyWidth)
                .alignByBaseline(),
        )
        Text(
            text = ":",
            color = Term.Dim,
            style = terminalBodyStyle(),
            modifier = Modifier
                .padding(horizontal = 6.dp)
                .alignByBaseline(),
        )
        SelectionContainer(
            modifier = Modifier
                .weight(1f)
                .alignByBaseline(),
        ) {
            Text(
                text = value,
                color = valueColor,
                style = terminalBodyStyle(),
            )
        }
    }
}

private fun terminalBodyStyle() = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 13.sp,
)

private fun terminalGlow(color: Color, radius: Float) = Shadow(
    color = color.copy(alpha = 0.5f),
    offset = Offset.Zero,
    blurRadius = radius,
)

private fun Modifier.terminalRectBorder(width: Dp, color: Color): Modifier = drawWithCache {
    val strokeWidth = width.toPx()
    onDrawWithContent {
        drawContent()
        drawRect(
            color = color,
            style = Stroke(width = strokeWidth),
        )
    }
}

private fun Modifier.terminalButtonBorder(color: Color, dashed: Boolean): Modifier = drawWithCache {
    val strokeWidth = 1.dp.toPx()
    val pathEffect = if (dashed) {
        PathEffect.dashPathEffect(
            intervals = floatArrayOf(5.dp.toPx(), 4.dp.toPx()),
        )
    } else {
        null
    }
    val borderColor = color.copy(alpha = if (dashed) 0.55f else 0.6f)
    onDrawWithContent {
        drawContent()
        drawRect(
            color = borderColor,
            style = Stroke(width = strokeWidth, pathEffect = pathEffect),
        )
    }
}
