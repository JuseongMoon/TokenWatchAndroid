package com.ScienceFiction.TokenWatchAndroid.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ScienceFiction.TokenWatchAndroid.data.AppSettings
import com.ScienceFiction.TokenWatchAndroid.domain.Agent
import com.ScienceFiction.TokenWatchAndroid.domain.AgentSnapshot
import com.ScienceFiction.TokenWatchAndroid.domain.UsageWindow
import com.ScienceFiction.TokenWatchAndroid.localization.AppLanguage
import com.ScienceFiction.TokenWatchAndroid.localization.L10n
import com.ScienceFiction.TokenWatchAndroid.ui.components.AnimatedPixelSpriteView
import com.ScienceFiction.TokenWatchAndroid.ui.components.KvRow
import com.ScienceFiction.TokenWatchAndroid.ui.components.PixelHeart
import com.ScienceFiction.TokenWatchAndroid.ui.components.PixelSprite
import com.ScienceFiction.TokenWatchAndroid.ui.components.TerminalBox
import com.ScienceFiction.TokenWatchAndroid.ui.theme.Term
import java.util.UUID
import kotlin.math.roundToInt

enum class RefreshIntervalOption(val seconds: Int, val terminalLabel: String) {
    OFF(0, "off"),
    THIRTY_SECONDS(30, "30s"),
    SIXTY_SECONDS(60, "60s"),
    FIVE_MINUTES(300, "5m"),
    AUTO(-1, "auto"),
}

data class GraphOption(
    val agent: Agent,
    val window: UsageWindow,
) {
    val id: String
        get() = "${agent.id}|${window.label}"
}

@Composable
fun SettingsScreen(
    settings: AppSettings,
    agents: List<Agent>,
    snapshots: Map<UUID, AgentSnapshot>,
    autoIntervalSeconds: Int,
    appVersion: String,
    loc: L10n,
    onSettingsChange: (AppSettings) -> Unit,
    onLogoutAgent: (Agent) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Term.Background),
    ) {
        ScreenTopBar(
            title = "SETTINGS",
            modifier = Modifier.statusBarsPadding(),
            trailing = {
                TerminalTextButton(text = "[done]", color = Term.Green, onClick = onDone)
            },
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AccountsSection(agents = agents, onLogoutAgent = onLogoutAgent)
            LanguageSection(
                selected = settings.language,
                loc = loc,
                onSelected = { onSettingsChange(settings.copy(language = it)) },
            )
            RefreshSection(
                selectedSeconds = settings.refreshInterval,
                autoIntervalSeconds = autoIntervalSeconds,
                loc = loc,
                onSelected = { onSettingsChange(settings.copy(refreshInterval = it)) },
            )
            DisplaySection(
                settings = settings,
                loc = loc,
                onSettingsChange = onSettingsChange,
            )
            HeartbeatSection(
                settings = settings,
                graphs = trackableGraphOptions(agents, snapshots),
                loc = loc,
                onSettingsChange = onSettingsChange,
            )
            ScreenSection(
                keepScreenOn = settings.keepScreenOn,
                loc = loc,
                onToggle = { onSettingsChange(settings.copy(keepScreenOn = !settings.keepScreenOn)) },
            )
            TerminalBox(title = "INFO") {
                KvRow(key = "version", value = appVersion, keyWidth = 84.dp)
            }
        }
    }
}

@Composable
private fun AccountsSection(agents: List<Agent>, onLogoutAgent: (Agent) -> Unit) {
    TerminalBox(title = "ACCOUNTS") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (agents.isEmpty()) {
                Text(text = "no accounts connected", color = Term.Dim, style = terminalTextStyle(12.sp))
            } else {
                agents.forEach { agent ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = agent.provider.terminalTag,
                            color = agent.provider.terminalColor(),
                            style = terminalTextStyle(13.sp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = agent.provider.displayName.uppercase(),
                                color = Term.Foreground,
                                style = terminalTextStyle(13.sp, FontWeight.SemiBold),
                            )
                            agent.accountLabel?.takeIf(String::isNotBlank)?.let { label ->
                                Text(text = label, color = Term.Dim, style = terminalTextStyle(10.sp))
                            }
                        }
                        TerminalTextButton(
                            text = "[logout]",
                            color = Term.Red,
                            size = 12.sp,
                            weight = FontWeight.Normal,
                            onClick = { onLogoutAgent(agent) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LanguageSection(
    selected: AppLanguage,
    loc: L10n,
    onSelected: (AppLanguage) -> Unit,
) {
    TerminalBox(title = "LANGUAGE") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SegmentedRow {
                AppLanguage.entries.forEach { language ->
                    SegmentButton(
                        title = language.segmentLabel,
                        selected = language == selected,
                        onClick = { onSelected(language) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            HelpText(loc.settingsLanguageHelp)
        }
    }
}

@Composable
private fun RefreshSection(
    selectedSeconds: Int,
    autoIntervalSeconds: Int,
    loc: L10n,
    onSelected: (Int) -> Unit,
) {
    TerminalBox(title = "AUTO-REFRESH") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SegmentedRow {
                RefreshIntervalOption.entries.forEach { option ->
                    SegmentButton(
                        title = option.terminalLabel,
                        selected = option.seconds == selectedSeconds,
                        onClick = { onSelected(option.seconds) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            HelpText(loc.settingsRefreshHelp)
            if (selectedSeconds == RefreshIntervalOption.AUTO.seconds) {
                val currentLabel = if (autoIntervalSeconds < 60) {
                    "${autoIntervalSeconds}s"
                } else {
                    "${autoIntervalSeconds / 60}m"
                }
                Text(
                    text = loc.settingsRefreshAutoHelp(currentLabel),
                    color = Term.Cyan,
                    style = terminalTextStyle(10.sp),
                )
            }
        }
    }
}

@Composable
private fun DisplaySection(
    settings: AppSettings,
    loc: L10n,
    onSettingsChange: (AppSettings) -> Unit,
) {
    TerminalBox(title = "DISPLAY") {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SettingWithHelp(
                checked = settings.hideUnusedWindows,
                title = "hide unused (0%) graphs",
                help = loc.settingsHideUnusedHelp,
                onClick = {
                    onSettingsChange(settings.copy(hideUnusedWindows = !settings.hideUnusedWindows))
                },
            )
            SettingWithHelp(
                checked = settings.gaugeCritter,
                title = "gauge slime",
                help = loc.settingsGaugeCritterHelp,
                onClick = { onSettingsChange(settings.copy(gaugeCritter = !settings.gaugeCritter)) },
                trailing = {
                    AnimatedPixelSpriteView(
                        sprite = PixelSprite.Slime,
                        cell = 2.dp,
                        flatColor = if (settings.gaugeCritter) null else Term.Dim,
                    )
                },
            )
        }
    }
}

@Composable
private fun HeartbeatSection(
    settings: AppSettings,
    graphs: List<GraphOption>,
    loc: L10n,
    onSettingsChange: (AppSettings) -> Unit,
) {
    TerminalBox(title = "HEARTBEAT") {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SettingWithHelp(
                checked = settings.heartbeatCursor,
                title = "heartbeat cursor",
                help = loc.settingsHeartbeatHelp,
                onClick = {
                    onSettingsChange(settings.copy(heartbeatCursor = !settings.heartbeatCursor))
                },
                trailing = {
                    PixelHeart(
                        flatColor = if (settings.heartbeatCursor) null else Term.Dim,
                        size = 15.dp,
                    )
                },
            )

            if (settings.heartbeatCursor) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SegmentedRow {
                        SegmentButton(
                            title = "heart",
                            selected = !settings.heartbeatTracking,
                            onClick = { onSettingsChange(settings.copy(heartbeatTracking = false)) },
                            modifier = Modifier.weight(1f),
                        )
                        SegmentButton(
                            title = "usage",
                            selected = settings.heartbeatTracking,
                            onClick = {
                                onSettingsChange(selectUsageTracking(settings, graphs))
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    HelpText(loc.settingsHeartbeatModeHelp)
                }

                if (settings.heartbeatTracking) {
                    GraphPicker(
                        graphs = graphs,
                        selectedIds = settings.heartbeatTargets,
                        loc = loc,
                        onToggle = { targetId ->
                            onSettingsChange(
                                settings.copy(
                                    heartbeatTargets = toggleTarget(settings.heartbeatTargets, targetId),
                                ),
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun GraphPicker(
    graphs: List<GraphOption>,
    selectedIds: Set<String>,
    loc: L10n,
    onToggle: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "track graph",
            color = Term.Cyan,
            style = terminalTextStyle(11.sp, FontWeight.SemiBold),
        )
        if (graphs.isEmpty()) {
            Text(
                text = loc.settingsHeartbeatNoGraphs,
                color = Term.Dim,
                style = terminalTextStyle(11.sp),
            )
        } else {
            graphs.forEach { graph ->
                GraphOptionRow(
                    graph = graph,
                    selected = graph.id in selectedIds,
                    onClick = { onToggle(graph.id) },
                )
            }
            HelpText(loc.settingsHeartbeatMultiHelp)
        }
    }
}

@Composable
private fun GraphOptionRow(graph: GraphOption, selected: Boolean, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Checkbox,
                onClick = onClick,
            ),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (selected) "[v]" else "[ ]",
            color = if (selected) Term.Green else Term.Dim,
            style = terminalTextStyle(12.sp),
        )
        Text(
            text = graph.agent.provider.terminalTag,
            color = graph.agent.provider.terminalColor(),
            style = terminalTextStyle(12.sp),
        )
        Text(
            text = graph.window.label,
            color = if (selected) Term.Foreground else Term.Dim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = terminalTextStyle(12.sp),
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "${graph.window.usedPercent.roundToInt()}%",
            color = Term.statusColor(graph.window.remainingPercent),
            style = terminalTextStyle(12.sp),
        )
    }
}

@Composable
private fun ScreenSection(keepScreenOn: Boolean, loc: L10n, onToggle: () -> Unit) {
    TerminalBox(title = "SCREEN") {
        SettingWithHelp(
            checked = keepScreenOn,
            title = "keep screen on",
            help = loc.settingsScreenHelp,
            onClick = onToggle,
        )
    }
}

@Composable
private fun SettingWithHelp(
    checked: Boolean,
    title: String,
    help: String,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val interactionSource = remember { MutableInteractionSource() }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    role = Role.Checkbox,
                    onClick = onClick,
                ),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (checked) "[x]" else "[ ]",
                color = if (checked) Term.Green else Term.Dim,
                style = terminalTextStyle(14.sp),
            )
            Text(text = title, color = Term.Foreground, style = terminalTextStyle(14.sp))
            trailing?.invoke()
            Spacer(modifier = Modifier.weight(1f))
        }
        HelpText(help)
    }
}

@Composable
private fun HelpText(text: String) {
    Text(text = text, color = Term.Dim, style = terminalTextStyle(10.sp))
}

@Composable
private fun SegmentedRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Term.Dim.copy(alpha = 0.5f)),
        content = content,
    )
}

@Composable
private fun SegmentButton(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Text(
        text = title,
        color = if (selected) Term.Green else Term.Dim,
        maxLines = 1,
        textAlign = TextAlign.Center,
        style = terminalTextStyle(
            size = 13.sp,
            weight = if (selected) FontWeight.Bold else FontWeight.Normal,
        ),
        modifier = modifier
            .background(if (selected) Term.Green.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(vertical = 9.dp),
    )
}

internal fun trackableGraphOptions(
    agents: List<Agent>,
    snapshots: Map<UUID, AgentSnapshot>,
): List<GraphOption> = agents.flatMap { agent ->
    snapshots[agent.id]
        ?.windows
        .orEmpty()
        .filter(UsageWindow::isGaugeLike)
        .map { GraphOption(agent = agent, window = it) }
}

internal fun selectUsageTracking(
    settings: AppSettings,
    graphs: List<GraphOption>,
): AppSettings {
    val validIds = graphs.mapTo(hashSetOf(), GraphOption::id)
    val targets = if (settings.heartbeatTargets.any(validIds::contains)) {
        settings.heartbeatTargets
    } else {
        graphs.firstOrNull()?.let { setOf(it.id) }.orEmpty()
    }
    return settings.copy(heartbeatTracking = true, heartbeatTargets = targets)
}

internal fun toggleTarget(targets: Set<String>, id: String): Set<String> =
    if (id in targets) targets - id else targets + id
