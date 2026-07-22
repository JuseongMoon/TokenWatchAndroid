package com.ScienceFiction.TokenWatchAndroid.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ScienceFiction.TokenWatchAndroid.domain.Agent
import com.ScienceFiction.TokenWatchAndroid.domain.AgentSnapshot
import com.ScienceFiction.TokenWatchAndroid.domain.ServiceHealth
import com.ScienceFiction.TokenWatchAndroid.domain.UsageWindow
import com.ScienceFiction.TokenWatchAndroid.localization.L10n
import com.ScienceFiction.TokenWatchAndroid.ui.components.TerminalBox
import com.ScienceFiction.TokenWatchAndroid.ui.components.TerminalBlink
import com.ScienceFiction.TokenWatchAndroid.ui.components.TerminalSpinner
import com.ScienceFiction.TokenWatchAndroid.ui.components.UsageBar
import com.ScienceFiction.TokenWatchAndroid.ui.components.rememberMinuteInstant
import com.ScienceFiction.TokenWatchAndroid.ui.theme.Term

@Composable
fun AgentCard(
    agent: Agent,
    snapshot: AgentSnapshot?,
    isLoading: Boolean,
    serviceHealth: ServiceHealth?,
    hideUnusedWindows: Boolean,
    loc: L10n,
    gaugeCritterEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    TerminalBox(
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AgentCardTitleBar(agent = agent, serviceHealth = serviceHealth, loc = loc)
            AgentUsageContent(
                snapshot = snapshot,
                isLoading = isLoading,
                hideUnusedWindows = hideUnusedWindows,
                loc = loc,
                gaugeCritterEnabled = gaugeCritterEnabled,
            )
        }
    }
}

/** `[C] CLAUDE [●] · pro`; status text stays available to accessibility only. */
@Composable
private fun AgentCardTitleBar(agent: Agent, serviceHealth: ServiceHealth?, loc: L10n) {
    val providerColor = agent.provider.terminalColor()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${agent.provider.terminalTag} ${agent.provider.displayName.uppercase()}",
            color = providerColor,
            maxLines = 1,
            style = terminalTextStyle(12.sp, FontWeight.SemiBold).copy(
                shadow = Shadow(
                    color = providerColor.copy(alpha = 0.5f),
                    offset = Offset.Zero,
                    blurRadius = 2f,
                ),
            ),
        )
        serviceHealth?.takeUnless { it == ServiceHealth.UNKNOWN }?.let { health ->
            ServiceHealthBadge(health = health, loc = loc)
        }
        agent.accountLabel
            ?.trim()
            ?.takeIf { it.isNotEmpty() && '@' !in it }
            ?.let { plan ->
                Text(
                    text = "· $plan",
                    color = providerColor,
                    maxLines = 1,
                    style = terminalTextStyle(12.sp, FontWeight.SemiBold),
                )
            }
        Spacer(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ServiceHealthBadge(health: ServiceHealth, loc: L10n) {
    Row(
        modifier = Modifier.clearAndSetSemantics {
            contentDescription = loc.serviceHealthLabel(health)
        },
        horizontalArrangement = Arrangement.spacedBy(1.5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "[", color = Term.Dim, style = terminalTextStyle(12.sp))
        when (health) {
            ServiceHealth.MAINTENANCE -> Text(
                text = loc.serviceMaintenanceBadge,
                color = Term.Orange,
                style = terminalTextStyle(10.sp, FontWeight.SemiBold),
            )
            ServiceHealth.MAJOR -> TerminalBlink { ServiceHealthDot(health) }
            else -> ServiceHealthDot(health)
        }
        Text(text = "]", color = Term.Dim, style = terminalTextStyle(12.sp))
    }
}

@Composable
private fun ServiceHealthDot(health: ServiceHealth) {
    Box(
        modifier = Modifier
            .size(8.dp)
            .background(serviceHealthDotColor(health), CircleShape),
    )
}

/** `[tag] NAME · plan`; account email is deliberately never exposed on the main screen. */
internal fun agentCardTitle(agent: Agent): String = buildString {
    append(agent.provider.terminalTag)
    append(' ')
    append(agent.provider.displayName.uppercase())
    agent.accountLabel
        ?.trim()
        ?.takeIf { it.isNotEmpty() && '@' !in it }
        ?.let {
            append(" · ")
            append(it)
        }
}

internal fun visibleUsageWindows(
    windows: List<UsageWindow>,
    hideUnusedWindows: Boolean,
): List<UsageWindow> = if (hideUnusedWindows) windows.filterNot(UsageWindow::isUnused) else windows

@Composable
private fun AgentUsageContent(
    snapshot: AgentSnapshot?,
    isLoading: Boolean,
    hideUnusedWindows: Boolean,
    loc: L10n,
    gaugeCritterEnabled: Boolean,
) {
    when {
        snapshot != null && snapshot.windows.isNotEmpty() -> {
            val windows = visibleUsageWindows(snapshot.windows, hideUnusedWindows)
            val now = rememberMinuteInstant()
            // Keep last-good usage visible, but place its stale/error notice before the graphs.
            snapshot.error?.let { ErrorLine(it) }
            if (windows.isEmpty()) {
                PlaceholderLine(loc.usageAllUnusedHidden)
            } else {
                windows.forEach { window ->
                    UsageBar(
                        window = window,
                        loc = loc,
                        now = now,
                        gaugeCritterEnabled = gaugeCritterEnabled,
                    )
                }
            }
        }

        snapshot?.error != null -> ErrorLine(snapshot.error)

        isLoading -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TerminalSpinner(size = 13.sp)
            Text(text = "querying usage…", color = Term.Dim, style = terminalTextStyle(12.sp))
        }

        else -> PlaceholderLine("no usage data")
    }
}

@Composable
internal fun ErrorLine(error: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = "!",
            color = Term.Red,
            style = terminalTextStyle(13.sp, FontWeight.Bold),
        )
        Text(
            text = error,
            color = Term.Red.copy(alpha = 0.85f),
            style = terminalTextStyle(11.sp),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun PlaceholderLine(text: String) {
    Text(
        text = text,
        color = Term.Dim,
        style = terminalTextStyle(12.sp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp),
    )
}
