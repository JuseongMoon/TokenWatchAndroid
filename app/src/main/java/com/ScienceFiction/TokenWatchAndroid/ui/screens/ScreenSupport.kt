package com.ScienceFiction.TokenWatchAndroid.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ScienceFiction.TokenWatchAndroid.domain.AgentProvider
import com.ScienceFiction.TokenWatchAndroid.domain.ServiceHealth
import com.ScienceFiction.TokenWatchAndroid.domain.TerminalColorKey
import com.ScienceFiction.TokenWatchAndroid.ui.theme.Term

internal fun terminalTextStyle(
    size: TextUnit,
    weight: FontWeight = FontWeight.Normal,
) = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = size,
    fontWeight = weight,
)

internal fun AgentProvider.terminalColor(): Color = when (terminalColorKey) {
    TerminalColorKey.YELLOW -> Term.Yellow
    TerminalColorKey.CYAN -> Term.Cyan
    TerminalColorKey.ORANGE -> Term.Orange
    TerminalColorKey.MAGENTA -> Term.Magenta
    TerminalColorKey.BLUE -> Term.Blue
    TerminalColorKey.GREEN -> Term.Green
    TerminalColorKey.PINK -> Term.Pink
    TerminalColorKey.TEAL -> Term.Teal
    TerminalColorKey.FOREGROUND -> Term.Foreground
}

internal fun serviceHealthColor(health: ServiceHealth): Color = when (health) {
    ServiceHealth.OPERATIONAL -> Term.Green
    ServiceHealth.DEGRADED -> Term.Yellow
    ServiceHealth.MAJOR -> Term.Red
    ServiceHealth.MAINTENANCE -> Term.Blue
    ServiceHealth.UNKNOWN -> Term.Dim
}

/** Unknown is a valid-but-unrecognized status, so keep its dot deliberately quieter than failures. */
internal fun serviceHealthDotColor(health: ServiceHealth): Color = when (health) {
    ServiceHealth.UNKNOWN -> Term.Dim.copy(alpha = 0.4f)
    else -> serviceHealthColor(health)
}

@Composable
internal fun TerminalTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Term.Green,
    enabled: Boolean = true,
    accessibilityLabel: String? = null,
    size: TextUnit = 13.sp,
    weight: FontWeight = FontWeight.SemiBold,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Text(
        text = text,
        color = color.copy(alpha = if (enabled) 1f else 0.35f),
        maxLines = 1,
        style = terminalTextStyle(size = size, weight = weight),
        modifier = modifier
            .then(
                if (accessibilityLabel != null) {
                    Modifier.semantics { contentDescription = accessibilityLabel }
                } else {
                    Modifier
                },
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 2.dp, vertical = 5.dp),
    )
}

@Composable
internal fun TerminalDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .height(1.dp)
            .background(Term.Dim.copy(alpha = 0.35f)),
    )
}

@Composable
internal fun ScreenTopBar(
    title: String,
    modifier: Modifier = Modifier,
    leading: (@Composable RowScope.() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Term.Background)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                leading?.invoke(this)
            }
        }
        Text(
            text = title,
            color = Term.Foreground,
            maxLines = 1,
            style = terminalTextStyle(14.sp, FontWeight.SemiBold),
        )
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                trailing?.invoke(this)
            }
        }
    }
}
