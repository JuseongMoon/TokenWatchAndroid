package com.ScienceFiction.TokenWatchAndroid.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ScienceFiction.TokenWatchAndroid.data.AppSettings
import com.ScienceFiction.TokenWatchAndroid.domain.Agent
import com.ScienceFiction.TokenWatchAndroid.domain.AgentProvider
import com.ScienceFiction.TokenWatchAndroid.domain.AgentSnapshot
import com.ScienceFiction.TokenWatchAndroid.domain.ServiceHealth
import com.ScienceFiction.TokenWatchAndroid.localization.L10n
import com.ScienceFiction.TokenWatchAndroid.ui.components.BlinkingCursor
import com.ScienceFiction.TokenWatchAndroid.ui.components.BlinkingHeart
import com.ScienceFiction.TokenWatchAndroid.ui.components.HeartHealthBar
import com.ScienceFiction.TokenWatchAndroid.ui.components.TerminalButton
import com.ScienceFiction.TokenWatchAndroid.ui.components.TerminalBox
import com.ScienceFiction.TokenWatchAndroid.ui.theme.Term
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MainScreen(
    agents: List<Agent>,
    snapshots: Map<UUID, AgentSnapshot>,
    loadingAgentIds: Set<UUID>,
    serviceStatus: Map<AgentProvider, ServiceHealth>,
    settings: AppSettings,
    loc: L10n,
    appVersion: String,
    isRefreshingAll: Boolean,
    onSettings: () -> Unit,
    onAddAgent: () -> Unit,
    onOpenAgent: (Agent) -> Unit,
    onMoveUp: (Agent) -> Unit,
    onMoveDown: (Agent) -> Unit,
    onRefreshAgent: (Agent) -> Unit,
    onDeleteAgent: (Agent) -> Unit,
    onRefreshAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Term.Background),
    ) {
        MainHeader(
            agents = agents,
            snapshots = snapshots,
            settings = settings,
            loc = loc,
            appVersion = appVersion,
            onSettings = onSettings,
            modifier = Modifier.statusBarsPadding(),
        )

        PullToRefreshBox(
            isRefreshing = isRefreshingAll,
            onRefresh = onRefreshAll,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            indicator = {},
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (agents.isEmpty()) {
                    item(key = "empty-hint") { EmptyAgentHint() }
                }

                itemsIndexed(
                    items = agents,
                    key = { _, agent -> agent.id },
                ) { index, agent ->
                    AgentCardItem(
                        agent = agent,
                        index = index,
                        agentCount = agents.size,
                        snapshot = snapshots[agent.id],
                        isLoading = agent.id in loadingAgentIds,
                        serviceHealth = serviceStatus[agent.provider],
                        settings = settings,
                        loc = loc,
                        onOpen = { onOpenAgent(agent) },
                        onMoveUp = { onMoveUp(agent) },
                        onMoveDown = { onMoveDown(agent) },
                        onRefresh = { onRefreshAgent(agent) },
                        onDelete = { onDeleteAgent(agent) },
                    )
                }

                item(key = "add-agent") {
                    TerminalButton(
                        title = "[ + ADD AGENT ]",
                        color = Term.Green,
                        dashedBorder = true,
                        onClick = onAddAgent,
                    )
                }
            }
        }
    }
}

@Composable
private fun MainHeader(
    agents: List<Agent>,
    snapshots: Map<UUID, AgentSnapshot>,
    settings: AppSettings,
    loc: L10n,
    appVersion: String,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Term.Background)
            .padding(start = 16.dp, end = 14.dp, top = 8.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier.weight(1f),
        ) {
            TokenWatchTitle(appVersion)
            StatusPrompt(
                agentCount = agents.size,
                trackedUsedPercent = trackedUsedPercent(
                    agents = agents,
                    snapshots = snapshots,
                    targetIds = settings.heartbeatTargets,
                ).takeIf { settings.heartbeatTracking },
                settings = settings,
                loc = loc,
            )
        }
        TerminalTextButton(
            text = "[SETTINGS]",
            color = Term.Cyan,
            onClick = onSettings,
            accessibilityLabel = loc.a11ySettings,
        )
    }
}

@Composable
private fun TokenWatchTitle(appVersion: String) {
    val titleColors = remember {
        listOf(
            Color(0xFFFF8787),
            Color(0xFFFFAF87),
            Color(0xFFFFD787),
            Color(0xFFAFD7AF),
            Color(0xFFAFAFD7),
            Color(0xFFAFAFD7),
            Color(0xFFD7AFD7),
            Color(0xFFFF8787),
            Color(0xFFFFAF87),
            Color(0xFFFFD787),
        )
    }
    Row(
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier.clearAndSetSemantics {
            contentDescription = "tokenwatch v$appVersion"
        },
    ) {
        "tokenwatch".forEachIndexed { index, character ->
            Text(
                text = character.toString(),
                color = titleColors[index],
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    shadow = Shadow(
                        color = titleColors[index].copy(alpha = 0.5f),
                        offset = Offset.Zero,
                        blurRadius = 2f,
                    ),
                ),
            )
        }
        Text(
            text = "  v$appVersion",
            color = Term.Dim,
            style = terminalTextStyle(11.sp),
        )
    }
}

@Composable
private fun StatusPrompt(
    agentCount: Int,
    trackedUsedPercent: Double?,
    settings: AppSettings,
    loc: L10n,
) {
    val statusLine = if (agentCount == 0) {
        "no agents connected"
    } else {
        "watching $agentCount agent${if (agentCount == 1) "" else "s"}"
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "$", color = Term.Dim, style = terminalTextStyle(12.sp))
        Text(text = statusLine, color = Term.Foreground, style = terminalTextStyle(12.sp))
        when {
            !settings.heartbeatCursor -> BlinkingCursor(symbol = "_", size = 13.sp)
            trackedUsedPercent != null -> HeartHealthBar(
                usedPercent = trackedUsedPercent,
                size = 11.dp,
                usedContentDescription = loc::a11yUsed,
            )
            else -> BlinkingHeart(size = 11.dp)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AgentCardItem(
    agent: Agent,
    index: Int,
    agentCount: Int,
    snapshot: AgentSnapshot?,
    isLoading: Boolean,
    serviceHealth: ServiceHealth?,
    settings: AppSettings,
    loc: L10n,
    onOpen: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    var showActions by rememberSaveable(agent.id) { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        AgentCard(
            agent = agent,
            snapshot = snapshot,
            isLoading = isLoading,
            serviceHealth = serviceHealth,
            hideUnusedWindows = settings.hideUnusedWindows,
            gaugeCritterEnabled = settings.gaugeCritter,
            loc = loc,
            modifier = Modifier.combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onOpen,
                onLongClick = { showActions = true },
            ),
        )
        ReorderControls(
            canMoveUp = index > 0,
            canMoveDown = index < agentCount - 1,
            loc = loc,
            onMoveUp = onMoveUp,
            onMoveDown = onMoveDown,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 7.dp, end = 8.dp),
        )
    }

    if (showActions) {
        AgentActionDialog(
            agent = agent,
            loc = loc,
            onRefresh = {
                showActions = false
                onRefresh()
            },
            onDelete = {
                showActions = false
                onDelete()
            },
            onDismiss = { showActions = false },
        )
    }
}

@Composable
private fun AgentActionDialog(
    agent: Agent,
    loc: L10n,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
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
                .padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            TerminalBox(
                title = agentCardTitle(agent),
                titleColor = agent.provider.terminalColor(),
                borderColor = Term.Dim,
            ) {
                Text(
                    text = "$ choose an action",
                    color = Term.Dim,
                    style = terminalTextStyle(12.sp),
                )
                TerminalButton(
                    title = "[ ${loc.menuRefresh.uppercase(loc.dateLocale)} ]",
                    color = Term.Cyan,
                    onClick = onRefresh,
                )
                TerminalButton(
                    title = "[ ${loc.menuDelete.uppercase(loc.dateLocale)} ]",
                    color = Term.Red,
                    onClick = onDelete,
                )
                TerminalButton(
                    title = "[ ${loc.cancel.uppercase(loc.dateLocale)} ]",
                    color = Term.Dim,
                    onClick = onDismiss,
                )
            }
        }
    }
}

@Composable
private fun ReorderControls(
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    loc: L10n,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .background(Term.Background)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        ReorderArrow("▲", canMoveUp, loc.a11yMoveUp, onMoveUp)
        ReorderArrow("▼", canMoveDown, loc.a11yMoveDown, onMoveDown)
    }
}

@Composable
private fun ReorderArrow(
    glyph: String,
    enabled: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(width = 26.dp, height = 20.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = glyph,
            color = if (enabled) Term.Green else Term.Dim.copy(alpha = 0.3f),
            style = terminalTextStyle(16.5.sp),
        )
    }
}

@Composable
private fun EmptyAgentHint() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text = "$ tap", color = Term.Dim, style = terminalTextStyle(12.sp))
            Text(text = "[+ ADD AGENT]", color = Term.Green, style = terminalTextStyle(12.sp))
            Text(text = "below to login", color = Term.Dim, style = terminalTextStyle(12.sp))
        }
        Text(
            text = "  tokens are stored only on this device",
            color = Term.Dim.copy(alpha = 0.7f),
            style = terminalTextStyle(12.sp),
        )
    }
}

/** Mean usage of selected gauge windows. Missing IDs and balance windows are ignored. */
internal fun trackedUsedPercent(
    agents: List<Agent>,
    snapshots: Map<UUID, AgentSnapshot>,
    targetIds: Set<String>,
): Double? {
    if (targetIds.isEmpty()) return null
    val used = buildList {
        agents.forEach { agent ->
            snapshots[agent.id]?.windows.orEmpty().forEach { window ->
                val id = "${agent.id}|${window.label}"
                if (window.isGaugeLike && id in targetIds) add(window.usedPercent)
            }
        }
    }
    return used.takeIf { it.isNotEmpty() }?.average()
}
