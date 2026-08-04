package com.ScienceFiction.TokenWatchAndroid.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ScienceFiction.TokenWatchAndroid.domain.UsageStyle
import com.ScienceFiction.TokenWatchAndroid.domain.UsageWindow
import com.ScienceFiction.TokenWatchAndroid.domain.WindowKind
import com.ScienceFiction.TokenWatchAndroid.domain.WorkHours
import com.ScienceFiction.TokenWatchAndroid.domain.WorkHoursSchedule
import com.ScienceFiction.TokenWatchAndroid.localization.L10n
import com.ScienceFiction.TokenWatchAndroid.localization.resetSummary
import com.ScienceFiction.TokenWatchAndroid.ui.theme.Term
import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

/** Compact usage row used inside an agent card, matching the clean iOS layout. */
@Composable
fun UsageBar(
    window: UsageWindow,
    loc: L10n,
    modifier: Modifier = Modifier,
    now: Instant? = null,
    gaugeCritterEnabled: Boolean = true,
    workHoursSchedule: WorkHoursSchedule? = null,
) {
    val currentNow = rememberMinuteInstant(now)
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = window.label.uppercase(loc.dateLocale),
            color = Term.Cyan,
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            ),
        )

        when (window.style) {
            UsageStyle.BALANCE -> BalanceValue(window.valueText)
            UsageStyle.CREDIT_GAUGE -> CreditGaugeValue(
                window = window,
                loc = loc,
                gaugeCritterEnabled = gaugeCritterEnabled,
            )
            UsageStyle.GAUGE -> GaugeValue(
                window = window,
                loc = loc,
                now = currentNow,
                gaugeCritterEnabled = gaugeCritterEnabled,
                workHoursSchedule = workHoursSchedule,
            )
        }

        if (window.resetsAt != null) {
            Text(
                text = window.resetSummary(loc = loc, at = currentNow),
                color = Term.Dim,
                style = terminalTextStyle(10),
            )
        }
    }
}

/**
 * Compose equivalent of the clean iOS `TimelineView(.periodic(..., by: 60))`.
 * [nowOverride] keeps screenshots and UI tests deterministic without disabling production ticks.
 */
@Composable
internal fun rememberMinuteInstant(nowOverride: Instant? = null): Instant {
    var current by remember { mutableStateOf(nowOverride ?: Instant.now()) }
    LaunchedEffect(nowOverride) {
        if (nowOverride != null) {
            current = nowOverride
            return@LaunchedEffect
        }
        current = Instant.now()
        while (currentCoroutineContext().isActive) {
            delay(60_000L)
            current = Instant.now()
        }
    }
    return nowOverride ?: current
}

@Composable
private fun GaugeValue(
    window: UsageWindow,
    loc: L10n,
    now: Instant,
    gaugeCritterEnabled: Boolean,
    workHoursSchedule: WorkHoursSchedule?,
) {
    val statusColor = Term.statusColor(window.remainingPercent)
    val weeklySchedule = workHoursSchedule.takeIf { window.kind == WindowKind.WEEKLY }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TerminalGauge(
            usedFraction = window.usedPercent / 100.0,
            fillColor = statusColor,
            elapsedFraction = window.markerFraction(at = now, schedule = weeklySchedule),
            markerPaused = weeklySchedule?.let { !WorkHours.isWorkingTime(now, it) } ?: false,
            modifier = Modifier.weight(1f),
            height = 14.dp,
            bracketSize = 13.sp,
            critterVariant = GaugeCritterVariant.from(window.kind),
            gaugeCritterEnabled = gaugeCritterEnabled,
            usedContentDescription = loc::a11yUsed,
        )
        Text(
            text = String.format(Locale.US, "%3d%% used", window.usedPercent.roundToInt()),
            color = statusColor,
            maxLines = 1,
            style = terminalTextStyle(12),
        )
    }
}

@Composable
private fun CreditGaugeValue(
    window: UsageWindow,
    loc: L10n,
    gaugeCritterEnabled: Boolean,
) {
    val statusColor = Term.statusColor(window.remainingPercent)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TerminalGauge(
            usedFraction = window.usedPercent / 100.0,
            fillColor = statusColor,
            elapsedFraction = null,
            fillsRemaining = true,
            modifier = Modifier.weight(1f),
            height = 14.dp,
            bracketSize = 13.sp,
            critterVariant = GaugeCritterVariant.from(window.kind),
            gaugeCritterEnabled = gaugeCritterEnabled,
            usedContentDescription = loc::a11yUsed,
            remainingContentDescription = loc::a11yRemaining,
        )
        Text(
            text = "${if (window.estimatedTotal) "~" else ""}${window.valueText ?: "—"}",
            color = statusColor,
            maxLines = 1,
            style = terminalTextStyle(12),
        )
    }
}

@Composable
private fun BalanceValue(valueText: String?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(text = "▸", color = Term.Dim, style = terminalTextStyle(13))
        Text(
            text = valueText ?: "—",
            color = Term.Green,
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            ),
        )
        Spacer(modifier = Modifier.width(0.dp).weight(1f))
    }
}

private fun terminalTextStyle(size: Int) = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = size.sp,
)
