package com.ScienceFiction.TokenWatchAndroid.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ScienceFiction.TokenWatchAndroid.account.AccountState
import com.ScienceFiction.TokenWatchAndroid.account.AccountStore
import com.ScienceFiction.TokenWatchAndroid.account.displayAvatarId
import com.ScienceFiction.TokenWatchAndroid.domain.account.AvatarCatalog
import com.ScienceFiction.TokenWatchAndroid.localization.L10n
import com.ScienceFiction.TokenWatchAndroid.localization.blockMessage
import com.ScienceFiction.TokenWatchAndroid.localization.consentAgree
import com.ScienceFiction.TokenWatchAndroid.localization.consentFailed
import com.ScienceFiction.TokenWatchAndroid.localization.consentIntro
import com.ScienceFiction.TokenWatchAndroid.localization.consentLater
import com.ScienceFiction.TokenWatchAndroid.localization.consentNotSent
import com.ScienceFiction.TokenWatchAndroid.localization.consentSent
import com.ScienceFiction.TokenWatchAndroid.localization.consentTitle
import com.ScienceFiction.TokenWatchAndroid.localization.guildBoardConsent
import com.ScienceFiction.TokenWatchAndroid.localization.guildBoardGuestKills
import com.ScienceFiction.TokenWatchAndroid.localization.guildBoardTitle
import com.ScienceFiction.TokenWatchAndroid.localization.leaderboardBlockedNote
import com.ScienceFiction.TokenWatchAndroid.localization.leaderboardEmpty
import com.ScienceFiction.TokenWatchAndroid.localization.leaderboardFailed
import com.ScienceFiction.TokenWatchAndroid.localization.leaderboardGuestHint
import com.ScienceFiction.TokenWatchAndroid.localization.leaderboardIntro
import com.ScienceFiction.TokenWatchAndroid.localization.leaderboardLoading
import com.ScienceFiction.TokenWatchAndroid.localization.leaderboardMine
import com.ScienceFiction.TokenWatchAndroid.localization.leaderboardNotListed
import com.ScienceFiction.TokenWatchAndroid.localization.leaderboardTab
import com.ScienceFiction.TokenWatchAndroid.localization.privacyLink
import com.ScienceFiction.TokenWatchAndroid.localization.reportFailed
import com.ScienceFiction.TokenWatchAndroid.localization.reportIntro
import com.ScienceFiction.TokenWatchAndroid.localization.reportNeedsAccount
import com.ScienceFiction.TokenWatchAndroid.localization.reportReason
import com.ScienceFiction.TokenWatchAndroid.localization.reportSent
import com.ScienceFiction.TokenWatchAndroid.localization.reportTitle
import com.ScienceFiction.TokenWatchAndroid.localization.termsLink
import com.ScienceFiction.TokenWatchAndroid.monster.LeaderboardEntry
import com.ScienceFiction.TokenWatchAndroid.monster.LeaderboardScope
import com.ScienceFiction.TokenWatchAndroid.monster.MonsterApiClient
import com.ScienceFiction.TokenWatchAndroid.monster.MonsterStats
import com.ScienceFiction.TokenWatchAndroid.monster.MyRank
import com.ScienceFiction.TokenWatchAndroid.monster.MonsterStore
import com.ScienceFiction.TokenWatchAndroid.ui.auth.InAppBrowser
import com.ScienceFiction.TokenWatchAndroid.ui.components.AccountNoticeLine
import com.ScienceFiction.TokenWatchAndroid.ui.components.AvatarView
import com.ScienceFiction.TokenWatchAndroid.ui.components.PixelSprite
import com.ScienceFiction.TokenWatchAndroid.ui.components.PixelSpriteView
import com.ScienceFiction.TokenWatchAndroid.ui.components.TerminalBox
import com.ScienceFiction.TokenWatchAndroid.ui.components.TerminalButton
import com.ScienceFiction.TokenWatchAndroid.ui.theme.Term
import java.util.Locale
import kotlinx.coroutines.launch

/*
 * Adventurer ranks (V2) screens, mirroring iOS `GuildViews`: the guild sign in the main header (left of
 * the announcements button), the ranks (this week / all time tabs, top 100, the player's rank, report and
 * block) and the monster hunt consent. Users see the name "adventurer ranks"; the code keeps `Guild…`.
 * - Sign: opens the ranks, or the consent screen before consenting (a cyan dot then).
 * - Ranks: public (readable signed out). The week tab starts over at Monday 00:00 KST. Reporting needs an
 *   account; blocking hides on this device.
 * - Consent: what goes to the server (never usage figures or account information), what is public, and
 *   the terms version 2 agreement.
 */

/** The guild sign: a wooden board with a parchment notice (16 × 12, one still frame). The iOS grid and colors. */
internal val GuildSign: PixelSprite = run {
    val legend = mapOf('B' to 1, 'b' to 2, 'h' to 3, 'p' to 4, 'P' to 5, 'k' to 6)
    val rows = listOf(
        ".BBBBBBBBBBBBBB.",
        "BhhbbbbbbbbbbbbB",
        "BbppppppppppppbB",
        "BbpkkpkkkpkkPpbB",
        "BbppppppppppppbB",
        "BbpkkkpkkpkkPpbB",
        "BbPPPPPPPPPPPPbB",
        "BbbbbbbbbbbbbbbB",
        ".BBBBBBBBBBBBBB.",
        "...Bb......Bb...",
        "...Bb......Bb...",
        "...BB......BB...",
    )
    PixelSprite(
        frames = listOf(rows.map { row -> row.map { legend[it] ?: 0 } }),
        palette = mapOf(
            1 to Color(0xFF6E4220), // wood outline
            2 to Color(0xFFA86A32), // wood
            3 to Color(0xFFD99A5E), // wood highlight
            4 to Color(0xFFF0E2B6), // parchment
            5 to Color(0xFFC9B98A), // parchment shade
            6 to Color(0xFF3A2A1A), // ink
        ),
    )
}

/** One pixel of the sign: 4/3 dp, so the sign is about 21 × 16 dp like the iOS header icon. */
private val SignCell = (4f / 3f).dp

/**
 * The guild sign in the main header. Opens the ranks, or the consent screen before consenting — marked
 * with a cyan dot. Dimmed and disabled in demo mode.
 */
@Composable
internal fun GuildSignButton(
    needsConsent: Boolean,
    isDemo: Boolean,
    loc: L10n,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val showDot = needsConsent && !isDemo
    Box(
        modifier = modifier
            .semantics(mergeDescendants = true) { contentDescription = loc.guildBoardTitle }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = !isDemo,
                role = Role.Button,
                // TalkBack reads it as "double-tap to …", the iOS hint's place.
                onClickLabel = if (showDot) loc.guildBoardConsent else null,
                onClick = onClick,
            )
            // Vertical room widens the touch target around the small sign.
            .padding(horizontal = 2.dp, vertical = 6.dp),
    ) {
        Box {
            PixelSpriteView(
                sprite = GuildSign,
                frameIndex = 0,
                cell = SignCell,
                flatColor = if (isDemo) Term.Dim else null,
            )
            if (showDot) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 3.dp, y = (-3).dp)
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(Term.Cyan),
                )
            }
        }
    }
}

// MARK: Leaderboard

/** The player's line in a tab: rank and score when ranked there, and the kills to show either way. */
internal data class MineLine(val listed: Boolean, val rank: Int?, val score: Int, val commonKills: Int, val specialKills: Int)

/**
 * The player's line for [scope] (pure). Unranked this week shows no kills; unranked all time shows the
 * server's stats.
 */
internal fun mineLine(rank: MyRank?, stats: MonsterStats?, scope: LeaderboardScope): MineLine = when (scope) {
    LeaderboardScope.WEEK -> rank?.week?.takeIf { it.listed }
        ?.let { MineLine(true, it.rank, it.score, it.commonKills, it.specialKills) }
        ?: MineLine(false, null, 0, 0, 0)
    LeaderboardScope.ALL_TIME -> if (rank != null && rank.listed) {
        MineLine(true, rank.rank, rank.stats.totalScore, rank.stats.commonKills, rank.stats.specialKills)
    } else {
        val known = rank?.stats ?: stats ?: MonsterStats()
        MineLine(false, null, 0, known.commonKills, known.specialKills)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LeaderboardScreen(
    monsters: MonsterStore,
    account: AccountStore,
    catalog: AvatarCatalog,
    loc: L10n,
    /** Opens the PLAYER sign-in. */
    onSignIn: () -> Unit,
    onOpenConsent: () -> Unit,
    onDone: () -> Unit,
) {
    val state by monsters.state.collectAsStateWithLifecycle()
    val accountState by account.state.collectAsStateWithLifecycle()
    val isOperator by account.isOperator.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    var menuTarget by remember { mutableStateOf<LeaderboardEntry?>(null) }
    var notice by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var tab by rememberSaveable { mutableStateOf(LeaderboardScope.WEEK) }
    LaunchedEffect(tab) { monsters.refreshGuild(tab) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Term.Background),
    ) {
        ScreenTopBar(
            title = "RANKS",
            modifier = Modifier.statusBarsPadding(),
            trailing = { TerminalTextButton(text = "[done]", color = Term.Green, onClick = onDone) },
        )
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                scope.launch {
                    refreshing = true
                    try {
                        monsters.refreshGuild(tab, force = true)
                    } finally {
                        refreshing = false
                    }
                }
            },
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
                // This week / all time, drawn like the settings segments.
                SegmentedRow {
                    LeaderboardScope.entries.forEach { option ->
                        SegmentButton(
                            title = loc.leaderboardTab(option),
                            selected = option == tab,
                            onClick = { tab = option },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Text(text = loc.leaderboardIntro(tab), color = Term.Dim, style = terminalTextStyle(11.sp))
                TerminalBox(title = "ME") {
                    val profile = (accountState as? AccountState.SignedIn)?.profile
                    when {
                        state.owner != null && profile != null -> Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics(mergeDescendants = true) {},
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AvatarView(preset = catalog.resolve(displayAvatarId(profile, isOperator, catalog)), cell = 2.dp)
                            val mine = mineLine(state.rank, state.stats, tab)
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                NametagText(profile = profile, size = 13.sp)
                                Text(
                                    text = loc.leaderboardMine(mine.commonKills, mine.specialKills),
                                    color = Term.Dim,
                                    style = terminalTextStyle(10.sp),
                                )
                            }
                            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                if (mine.listed) {
                                    Text(
                                        text = mine.rank?.let { "#$it" } ?: "—",
                                        color = Term.Yellow,
                                        style = terminalTextStyle(15.sp, FontWeight.Bold),
                                    )
                                    Text(text = "${mine.score} pts", color = Term.Green, style = terminalTextStyle(11.sp))
                                } else {
                                    Text(text = loc.leaderboardNotListed, color = Term.Dim, style = terminalTextStyle(10.sp))
                                }
                            }
                        }
                        state.needsConsent -> {
                            Text(text = loc.guildBoardConsent, color = Term.Cyan, style = terminalTextStyle(11.sp))
                            TerminalButton(title = "[ start ]", onClick = onOpenConsent)
                        }
                        else -> {
                            Text(text = loc.leaderboardGuestHint, color = Term.Dim, style = terminalTextStyle(11.sp))
                            if (state.guestKillCount > 0) {
                                Text(
                                    text = loc.guildBoardGuestKills(state.guestKillCount),
                                    color = Term.Green,
                                    style = terminalTextStyle(11.sp),
                                )
                            }
                            if (account.isAvailable && account.uid == null) {
                                TerminalButton(title = "[ sign in ]", onClick = onSignIn)
                            }
                        }
                    }
                }
                notice?.let { (text, ok) -> AccountNoticeLine(text = text, color = if (ok) Term.Green else Term.Red) }
                TerminalBox(title = "TOP 100") {
                    val list = state.visibleLeaderboard(tab)
                    if (list.isEmpty()) {
                        Text(
                            text = when {
                                tab in state.leaderboardFailed -> loc.leaderboardFailed
                                state.leaderboardLoadedAtMs[tab] == null -> loc.leaderboardLoading
                                else -> loc.leaderboardEmpty
                            },
                            color = Term.Dim,
                            style = terminalTextStyle(11.sp),
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            list.forEachIndexed { index, entry ->
                                LeaderboardRow(
                                    entry = entry,
                                    rank = index + 1,
                                    isMe = entry.uid == account.uid,
                                    catalog = catalog,
                                    loc = loc,
                                    onMenu = { menuTarget = entry },
                                )
                            }
                        }
                    }
                }
                if (state.blockedUids.isNotEmpty()) {
                    Text(text = loc.leaderboardBlockedNote, color = Term.Dim, style = terminalTextStyle(10.sp))
                }
            }
        }
    }

    menuTarget?.let { entry ->
        ReportBlockDialog(
            entry = entry,
            loc = loc,
            onBlock = {
                monsters.block(entry.uid)
                menuTarget = null
            },
            onReport = { reason ->
                menuTarget = null
                if (account.uid == null) {
                    notice = loc.reportNeedsAccount to false
                } else {
                    scope.launch {
                        val ok = monsters.report(entry.uid, reason)
                        notice = (if (ok) loc.reportSent else loc.reportFailed) to ok
                    }
                }
            },
            onDismiss = { menuTarget = null },
        )
    }
}

@Composable
private fun LeaderboardRow(
    entry: LeaderboardEntry,
    rank: Int,
    isMe: Boolean,
    catalog: AvatarCatalog,
    loc: L10n,
    onMenu: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = String.format(Locale.US, "%3d", rank),
            color = if (rank <= 3) Term.Yellow else Term.Dim,
            style = terminalTextStyle(12.sp, FontWeight.SemiBold),
        )
        AvatarView(preset = catalog.resolve(entry.avatarId), cell = 2.dp)
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = if (isMe) Term.Green else Term.Foreground)) { append(entry.nickname) }
                withStyle(SpanStyle(color = Term.Dim)) { append(" #${entry.tag}") }
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = terminalTextStyle(12.sp, FontWeight.SemiBold),
            modifier = Modifier.weight(1f),
        )
        Text(text = "${entry.score}", color = Term.Green, style = terminalTextStyle(12.sp))
        if (!isMe) {
            TerminalTextButton(
                text = "[⋯]",
                color = Term.Dim,
                size = 12.sp,
                weight = FontWeight.Normal,
                accessibilityLabel = loc.reportTitle("${entry.nickname} #${entry.tag}"),
                onClick = onMenu,
            )
        }
    }
}

/** Report or block one adventurer (a terminal dialog). */
@Composable
private fun ReportBlockDialog(
    entry: LeaderboardEntry,
    loc: L10n,
    onBlock: () -> Unit,
    onReport: (MonsterApiClient.ReportReason) -> Unit,
    onDismiss: () -> Unit,
) {
    var choosingReason by rememberSaveable(entry.uid) { mutableStateOf(false) }
    val scrimInteraction = remember { MutableInteractionSource() }
    val cardInteraction = remember { MutableInteractionSource() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.72f))
                .clickable(interactionSource = scrimInteraction, indication = null, onClick = onDismiss)
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            TerminalBox(
                title = "${entry.nickname} #${entry.tag}",
                borderColor = Term.Cyan,
                // Taps inside the card must not fall through to the scrim.
                modifier = Modifier.clickable(interactionSource = cardInteraction, indication = null, onClick = {}),
            ) {
                if (!choosingReason) {
                    TerminalButton(title = "[ report ]", color = Term.Yellow, onClick = { choosingReason = true })
                    TerminalButton(title = "[ block ]", color = Term.Red, onClick = onBlock)
                    Text(text = loc.blockMessage, color = Term.Dim, style = terminalTextStyle(10.sp))
                } else {
                    Text(text = loc.reportIntro, color = Term.Dim, style = terminalTextStyle(11.sp))
                    MonsterApiClient.ReportReason.entries.forEach { reason ->
                        TerminalButton(title = loc.reportReason(reason), color = Term.Yellow, onClick = { onReport(reason) })
                    }
                }
                TerminalButton(title = "[ cancel ]", color = Term.Dim, onClick = onDismiss)
            }
        }
    }
}

// MARK: Consent

@Composable
fun MonsterConsentScreen(
    monsters: MonsterStore,
    loc: L10n,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var agreed by rememberSaveable { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Term.Background),
    ) {
        ScreenTopBar(
            title = "MONSTERS",
            modifier = Modifier.statusBarsPadding(),
            leading = { TerminalTextButton(text = "[later]", color = Term.Dim, onClick = onDone) },
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
            TerminalBox(title = loc.consentTitle) {
                Text(text = loc.consentIntro, color = Term.Foreground, style = terminalTextStyle(11.sp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    loc.consentSent.forEach { line ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(text = "▸", color = Term.Cyan, style = terminalTextStyle(11.sp))
                            Text(text = line, color = Term.Foreground, style = terminalTextStyle(11.sp), modifier = Modifier.weight(1f))
                        }
                    }
                }
                Text(text = loc.consentNotSent, color = Term.Yellow, style = terminalTextStyle(11.sp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val toggleInteraction = remember { MutableInteractionSource() }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = toggleInteraction,
                            indication = null,
                            role = Role.Checkbox,
                            onClick = { agreed = !agreed },
                        ),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = if (agreed) "[x]" else "[ ]",
                        color = if (agreed) Term.Green else Term.Dim,
                        style = terminalTextStyle(12.sp),
                    )
                    Text(text = loc.consentAgree, color = Term.Foreground, style = terminalTextStyle(12.sp), modifier = Modifier.weight(1f))
                }
                Row(modifier = Modifier.padding(start = 30.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    TerminalTextButton(
                        loc.termsLink,
                        onClick = { InAppBrowser.open(context, AccountLinks.TERMS) },
                        color = Term.Cyan,
                        size = 11.sp,
                        weight = FontWeight.Normal,
                    )
                    TerminalTextButton(
                        loc.privacyLink,
                        onClick = { InAppBrowser.open(context, AccountLinks.PRIVACY) },
                        color = Term.Cyan,
                        size = 11.sp,
                        weight = FontWeight.Normal,
                    )
                }
            }
            TerminalButton(
                title = if (working) "[ … ]" else "[ agree & start ]",
                color = if (agreed) Term.Green else Term.Dim,
                enabled = agreed && !working,
                onClick = {
                    scope.launch {
                        working = true
                        failed = false
                        val ok = monsters.acceptConsent()
                        working = false
                        if (ok) onDone() else failed = true
                    }
                },
            )
            if (failed) AccountNoticeLine(text = loc.consentFailed)
            Text(text = loc.consentLater, color = Term.Dim, style = terminalTextStyle(10.sp))
        }
    }
}
