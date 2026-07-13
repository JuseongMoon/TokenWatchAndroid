package com.ScienceFiction.TokenWatchAndroid.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ScienceFiction.TokenWatchAndroid.domain.Agent
import com.ScienceFiction.TokenWatchAndroid.domain.AgentSnapshot
import com.ScienceFiction.TokenWatchAndroid.domain.ServiceHealth
import com.ScienceFiction.TokenWatchAndroid.domain.UsageStyle
import com.ScienceFiction.TokenWatchAndroid.domain.UsageWindow
import com.ScienceFiction.TokenWatchAndroid.localization.L10n
import com.ScienceFiction.TokenWatchAndroid.localization.resetExactText
import com.ScienceFiction.TokenWatchAndroid.localization.resetRemainingText
import com.ScienceFiction.TokenWatchAndroid.ui.components.KvRow
import com.ScienceFiction.TokenWatchAndroid.ui.components.TerminalBox
import com.ScienceFiction.TokenWatchAndroid.ui.components.TerminalButton
import com.ScienceFiction.TokenWatchAndroid.ui.components.TerminalGauge
import com.ScienceFiction.TokenWatchAndroid.ui.components.TerminalSpinner
import com.ScienceFiction.TokenWatchAndroid.ui.components.rememberMinuteInstant
import com.ScienceFiction.TokenWatchAndroid.ui.theme.Term
import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

data class DetailAccountUiState(
    val email: String? = null,
    val plan: String? = null,
    val isLoading: Boolean = true,
    val canRefresh: Boolean = true,
    val expiresAt: Instant? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    agent: Agent,
    snapshot: AgentSnapshot?,
    isLoading: Boolean,
    account: DetailAccountUiState,
    serviceHealth: ServiceHealth,
    hideUnusedWindows: Boolean,
    gaugeCritterEnabled: Boolean,
    loc: L10n,
    showLogoutConfirmation: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpenStatusPage: (String) -> Unit,
    onResetCreditPeak: (String) -> Unit,
    onLogoutRequest: () -> Unit,
    onLogoutConfirm: () -> Unit,
    onLogoutDismiss: () -> Unit,
    now: Instant? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Term.Background),
    ) {
        ScreenTopBar(
            title = agent.provider.displayName.uppercase(),
            modifier = Modifier.statusBarsPadding(),
            leading = {
                TerminalTextButton(
                    text = "[back]",
                    color = Term.Green,
                    accessibilityLabel = loc.a11yBack,
                    onClick = onBack,
                )
            },
            trailing = {
                TerminalTextButton(
                    text = "[refresh]",
                    color = Term.Cyan,
                    enabled = !isLoading,
                    accessibilityLabel = loc.a11yRefresh,
                    onClick = onRefresh,
                )
            },
        )

        PullToRefreshBox(
            isRefreshing = isLoading,
            onRefresh = onRefresh,
            modifier = Modifier.weight(1f),
            indicator = {},
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                AccountCard(agent = agent, snapshot = snapshot, account = account, loc = loc)
                DetailUsageCard(
                    snapshot = snapshot,
                    isLoading = isLoading,
                    hideUnusedWindows = hideUnusedWindows,
                    gaugeCritterEnabled = gaugeCritterEnabled,
                    loc = loc,
                    nowOverride = now,
                    onResetCreditPeak = onResetCreditPeak,
                )
                StatusCard(
                    agent = agent,
                    snapshot = snapshot,
                    account = account,
                    serviceHealth = serviceHealth,
                    loc = loc,
                    onOpenStatusPage = onOpenStatusPage,
                )
                TerminalButton(
                    title = "[ LOGOUT ]",
                    color = Term.Red,
                    onClick = onLogoutRequest,
                )
            }
        }
    }

    if (showLogoutConfirmation) {
        LogoutConfirmationDialog(
            providerName = agent.provider.displayName,
            loc = loc,
            onConfirm = onLogoutConfirm,
            onDismiss = onLogoutDismiss,
        )
    }
}

@Composable
private fun AccountCard(
    agent: Agent,
    snapshot: AgentSnapshot?,
    account: DetailAccountUiState,
    loc: L10n,
) {
    TerminalBox(title = "ACCOUNT", titleColor = agent.provider.terminalColor()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = agent.provider.terminalTag,
                color = agent.provider.terminalColor(),
                style = terminalTextStyle(14.sp, FontWeight.SemiBold),
            )
            Text(
                text = agent.provider.displayName.uppercase(),
                color = Term.Foreground,
                style = terminalTextStyle(14.sp, FontWeight.SemiBold),
            )
            val plan = account.plan?.takeIf(String::isNotBlank)
                ?: snapshot?.planLabel?.takeIf(String::isNotBlank)
            if (plan != null) {
                Text(
                    text = "· $plan",
                    color = Term.Dim,
                    style = terminalTextStyle(14.sp, FontWeight.SemiBold),
                )
            }
            Spacer(modifier = Modifier.weight(1f))
        }
        TerminalDivider()
        KvRow(
            key = "email",
            value = account.email?.takeIf(String::isNotBlank)
                ?: if (account.isLoading) loc.checking else loc.unavailable,
            keyWidth = 64.dp,
        )
    }
}

@Composable
private fun DetailUsageCard(
    snapshot: AgentSnapshot?,
    isLoading: Boolean,
    hideUnusedWindows: Boolean,
    gaugeCritterEnabled: Boolean,
    loc: L10n,
    nowOverride: Instant?,
    onResetCreditPeak: (String) -> Unit,
) {
    val now = rememberMinuteInstant(nowOverride)
    TerminalBox(title = "USAGE") {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                snapshot != null && snapshot.windows.isNotEmpty() -> {
                    val windows = visibleUsageWindows(snapshot.windows, hideUnusedWindows)
                    if (isLoading) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TerminalSpinner(size = 11.sp)
                            Text(
                                text = "refreshing…",
                                color = Term.Dim,
                                style = terminalTextStyle(11.sp),
                            )
                        }
                    }
                    if (windows.isEmpty()) {
                        DetailPlaceholder(loc.usageAllUnusedHidden)
                    } else {
                        UsageLegend(loc)
                        windows.forEachIndexed { index, window ->
                            if (index > 0) TerminalDivider()
                            DetailUsageRow(
                                window = window,
                                loc = loc,
                                gaugeCritterEnabled = gaugeCritterEnabled,
                                now = now,
                                onResetCreditPeak = onResetCreditPeak,
                            )
                        }
                    }
                    snapshot.error?.let { ErrorLine(it) }
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
                    Text(text = "loading usage…", color = Term.Dim, style = terminalTextStyle(12.sp))
                }

                else -> DetailPlaceholder("no usage data")
            }
        }
    }
}

@Composable
private fun UsageLegend(loc: L10n) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(2.dp)
                .height(12.dp)
                .background(Color.White),
        )
        Text(
            text = loc.usageLegend,
            color = Term.Dim,
            style = terminalTextStyle(10.sp),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun DetailUsageRow(
    window: UsageWindow,
    loc: L10n,
    gaugeCritterEnabled: Boolean,
    now: Instant,
    onResetCreditPeak: (String) -> Unit,
) {
    when (window.style) {
        UsageStyle.BALANCE -> BalanceDetailRow(window = window, loc = loc, now = now)
        UsageStyle.CREDIT_GAUGE -> CreditGaugeDetailRow(
            window = window,
            loc = loc,
            gaugeCritterEnabled = gaugeCritterEnabled,
            onResetPeak = if (window.estimatedTotal) {
                { onResetCreditPeak(window.label) }
            } else {
                null
            },
        )
        UsageStyle.GAUGE -> GaugeDetailRow(
            window = window,
            loc = loc,
            now = now,
            gaugeCritterEnabled = gaugeCritterEnabled,
        )
    }
}

@Composable
private fun CreditGaugeDetailRow(
    window: UsageWindow,
    loc: L10n,
    gaugeCritterEnabled: Boolean,
    onResetPeak: (() -> Unit)?,
) {
    val statusColor = Term.statusColor(window.remainingPercent)
    var showResetConfirmation by rememberSaveable(window.label) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = window.label.uppercase(loc.dateLocale),
                color = Term.Cyan,
                style = terminalTextStyle(12.sp, FontWeight.SemiBold),
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${if (window.estimatedTotal) "~" else ""}${window.remainingPercent.roundToInt()}% left",
                color = statusColor,
                style = terminalTextStyle(12.sp),
            )
        }
        TerminalGauge(
            usedFraction = window.usedPercent / 100.0,
            fillColor = statusColor,
            elapsedFraction = null,
            fillsRemaining = true,
            height = 20.dp,
            bracketSize = 15.sp,
            gaugeCritterEnabled = gaugeCritterEnabled,
            usedContentDescription = loc::a11yUsed,
            remainingContentDescription = loc::a11yRemaining,
        )
        KvRow(
            key = "remaining",
            value = window.valueText ?: "—",
            valueColor = statusColor,
            keyWidth = 84.dp,
        )
        if (window.estimatedTotal) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = loc.creditApproxNote,
                    color = Term.Dim,
                    style = terminalTextStyle(11.sp),
                    modifier = Modifier.weight(1f),
                )
                if (onResetPeak != null) {
                    TerminalTextButton(
                        text = loc.creditResetButton,
                        color = Term.Yellow,
                        size = 11.sp,
                        weight = FontWeight.Normal,
                        onClick = { showResetConfirmation = true },
                    )
                }
            }
        }
    }

    if (showResetConfirmation) {
        CreditResetConfirmationDialog(
            loc = loc,
            onConfirm = {
                showResetConfirmation = false
                onResetPeak?.invoke()
            },
            onDismiss = { showResetConfirmation = false },
        )
    }
}

@Composable
private fun BalanceDetailRow(window: UsageWindow, loc: L10n, now: Instant) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = window.label.uppercase(loc.dateLocale),
            color = Term.Cyan,
            style = terminalTextStyle(12.sp, FontWeight.SemiBold),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text = "▸", color = Term.Dim, style = terminalTextStyle(13.sp))
            Text(
                text = window.valueText ?: "—",
                color = Term.Green,
                style = terminalTextStyle(20.sp, FontWeight.Bold),
            )
        }
        detailResetText(window, loc, now)?.let { reset ->
            KvRow(key = "reset", value = reset, keyWidth = 84.dp)
        }
    }
}

@Composable
private fun GaugeDetailRow(
    window: UsageWindow,
    loc: L10n,
    now: Instant,
    gaugeCritterEnabled: Boolean,
) {
    val statusColor = Term.statusColor(window.remainingPercent)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = window.label.uppercase(loc.dateLocale),
                color = Term.Cyan,
                style = terminalTextStyle(12.sp, FontWeight.SemiBold),
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${window.usedPercent.roundToInt()}% used",
                color = statusColor,
                style = terminalTextStyle(12.sp),
            )
        }
        TerminalGauge(
            usedFraction = window.usedPercent / 100.0,
            fillColor = statusColor,
            elapsedFraction = window.elapsedFraction(now),
            height = 20.dp,
            bracketSize = 15.sp,
            gaugeCritterEnabled = gaugeCritterEnabled,
            usedContentDescription = loc::a11yUsed,
        )
        KvRow(
            key = "remaining",
            value = "${window.remainingPercent.roundToInt()}%",
            valueColor = statusColor,
            keyWidth = 84.dp,
        )
        detailResetText(window, loc, now)?.let { reset ->
            KvRow(key = "reset", value = reset, keyWidth = 84.dp)
        }
        usagePace(window, now)?.let { pace ->
            val text = when (pace.kind) {
                UsagePaceKind.AHEAD -> loc.paceAhead(pace.magnitude)
                UsagePaceKind.UNDER -> loc.paceUnder(pace.magnitude)
                UsagePaceKind.EVEN -> loc.paceEven
            }
            val color = when (pace.kind) {
                UsagePaceKind.AHEAD -> Term.Yellow
                UsagePaceKind.UNDER -> Term.Green
                UsagePaceKind.EVEN -> Term.Dim
            }
            Text(text = text, color = color, style = terminalTextStyle(11.sp))
        }
        depletionEta(window, now)?.let { eta ->
            Text(
                text = "!! ${loc.depletionWarning(loc.depletionEta(eta.days, eta.hours, eta.minutes))}",
                color = Term.Red,
                style = terminalTextStyle(11.sp),
            )
        }
    }
}

private fun detailResetText(window: UsageWindow, loc: L10n, now: Instant): String? {
    val exact = window.resetExactText(loc) ?: return null
    val remaining = window.resetRemainingText(loc, now)
    return remaining?.let { "$exact · $it" } ?: exact
}

internal enum class UsagePaceKind { AHEAD, UNDER, EVEN }

internal data class UsagePace(val kind: UsagePaceKind, val magnitude: Int)

internal fun usagePace(window: UsageWindow, now: Instant): UsagePace? {
    val delta = window.paceDelta(now) ?: return null
    val kind = when {
        delta >= 3.0 -> UsagePaceKind.AHEAD
        delta <= -3.0 -> UsagePaceKind.UNDER
        else -> UsagePaceKind.EVEN
    }
    return UsagePace(kind = kind, magnitude = abs(delta).roundToInt())
}

internal data class DepletionEta(val days: Int, val hours: Int, val minutes: Int)

/** Returns an ETA only when the current linear pace exhausts the quota before reset. */
internal fun depletionEta(window: UsageWindow, now: Instant): DepletionEta? {
    val elapsed = window.elapsedFraction(now) ?: return null
    val resetsAt = window.resetsAt ?: return null
    val total = window.windowSeconds ?: return null
    if (window.usedPercent <= 1.0 || window.usedPercent >= 99.5 || elapsed <= 0.02) return null
    val elapsedSeconds = total * elapsed
    if (elapsedSeconds <= 0.0) return null
    val ratePerSecond = window.usedPercent / elapsedSeconds
    if (ratePerSecond <= 0.0) return null
    val secondsToFull = (100.0 - window.usedPercent) / ratePerSecond
    val projectedFull = now.plusMillis((secondsToFull * 1_000.0).toLong())
    if (!projectedFull.isBefore(resetsAt)) return null

    val remaining = Duration.between(now, projectedFull)
    val totalMinutes = max(0L, remaining.toMinutes())
    return DepletionEta(
        days = (totalMinutes / (24 * 60)).toInt(),
        hours = ((totalMinutes / 60) % 24).toInt(),
        minutes = (totalMinutes % 60).toInt(),
    )
}

@Composable
private fun StatusCard(
    agent: Agent,
    snapshot: AgentSnapshot?,
    account: DetailAccountUiState,
    serviceHealth: ServiceHealth,
    loc: L10n,
    onOpenStatusPage: (String) -> Unit,
) {
    TerminalBox(title = "STATUS") {
        snapshot?.let {
            KvRow(key = "updated", value = loc.relativeTime(it.fetchedAt), keyWidth = 96.dp)
        }
        KvRow(key = "provider", value = agent.provider.displayName, keyWidth = 96.dp)
        ServiceStatusRow(
            agent = agent,
            serviceHealth = serviceHealth,
            loc = loc,
            onOpenStatusPage = onOpenStatusPage,
        )
        KvRow(
            key = "type",
            value = loc.usageCategoryLabel(agent.provider.usageCategory),
            keyWidth = 96.dp,
        )
        if (!account.canRefresh && account.expiresAt != null) {
            KvRow(
                key = "re-login",
                value = loc.absoluteDateTime(account.expiresAt),
                valueColor = Term.Yellow,
                keyWidth = 96.dp,
            )
        }
    }
}

@Composable
private fun ServiceStatusRow(
    agent: Agent,
    serviceHealth: ServiceHealth,
    loc: L10n,
    onOpenStatusPage: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "service",
            color = Term.Cyan,
            style = terminalTextStyle(13.sp),
            modifier = Modifier.width(96.dp),
        )
        Text(text = ":", color = Term.Dim, style = terminalTextStyle(13.sp))
        Text(text = "●", color = serviceHealthDotColor(serviceHealth), style = terminalTextStyle(11.sp))
        Text(
            text = loc.serviceHealthLabel(serviceHealth),
            color = serviceHealthColor(serviceHealth),
            style = terminalTextStyle(13.sp),
        )
        Spacer(modifier = Modifier.weight(1f))
        agent.provider.statusPageUrl?.let { url ->
            TerminalTextButton(
                text = "[status ↗]",
                color = Term.Green,
                accessibilityLabel = loc.a11yStatusPage,
                size = 13.sp,
                weight = FontWeight.Normal,
                onClick = { onOpenStatusPage(url) },
            )
        }
    }
}

@Composable
private fun DetailPlaceholder(text: String) {
    Text(
        text = text,
        color = Term.Dim,
        style = terminalTextStyle(12.sp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp),
    )
}

@Composable
private fun LogoutConfirmationDialog(
    providerName: String,
    loc: L10n,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.72f))
                .padding(28.dp),
            contentAlignment = Alignment.Center,
        ) {
            TerminalBox(
                title = loc.logoutConfirmTitle.uppercase(loc.dateLocale),
                borderColor = Term.Red,
                titleColor = Term.Red,
            ) {
                Text(
                    text = loc.logoutMessage(providerName),
                    color = Term.Foreground,
                    style = terminalTextStyle(12.sp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End),
                ) {
                    TerminalTextButton(
                        text = "[${loc.cancel}]",
                        color = Term.Dim,
                        onClick = onDismiss,
                    )
                    TerminalTextButton(
                        text = "[${loc.logout}]",
                        color = Term.Red,
                        onClick = onConfirm,
                    )
                }
            }
        }
    }
}

@Composable
private fun CreditResetConfirmationDialog(
    loc: L10n,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.72f))
                .padding(28.dp),
            contentAlignment = Alignment.Center,
        ) {
            TerminalBox(
                title = loc.creditResetTitle,
                borderColor = Term.Yellow,
                titleColor = Term.Yellow,
            ) {
                Text(
                    text = loc.creditResetMessage,
                    color = Term.Foreground,
                    style = terminalTextStyle(12.sp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End),
                ) {
                    TerminalTextButton(
                        text = "[${loc.cancel}]",
                        color = Term.Dim,
                        onClick = onDismiss,
                    )
                    TerminalTextButton(
                        text = "[${loc.creditResetConfirm}]",
                        color = Term.Red,
                        onClick = onConfirm,
                    )
                }
            }
        }
    }
}
