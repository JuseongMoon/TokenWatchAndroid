package com.ScienceFiction.TokenWatchAndroid.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ScienceFiction.TokenWatchAndroid.account.AccountNotice
import com.ScienceFiction.TokenWatchAndroid.account.AccountState
import com.ScienceFiction.TokenWatchAndroid.account.displayAvatarId
import com.ScienceFiction.TokenWatchAndroid.domain.account.AccountMethod
import com.ScienceFiction.TokenWatchAndroid.domain.account.AvatarCatalog
import com.ScienceFiction.TokenWatchAndroid.domain.account.AvatarPreset
import com.ScienceFiction.TokenWatchAndroid.domain.account.PlayerProfile
import com.ScienceFiction.TokenWatchAndroid.localization.L10n
import com.ScienceFiction.TokenWatchAndroid.localization.Lang
import com.ScienceFiction.TokenWatchAndroid.localization.accountMethodName
import com.ScienceFiction.TokenWatchAndroid.localization.accountNotice
import com.ScienceFiction.TokenWatchAndroid.localization.playerDeleting
import com.ScienceFiction.TokenWatchAndroid.localization.playerLoadFailed
import com.ScienceFiction.TokenWatchAndroid.localization.playerLoading
import com.ScienceFiction.TokenWatchAndroid.localization.playerNeedsProfile
import com.ScienceFiction.TokenWatchAndroid.localization.playerSignedOutHelp
import com.ScienceFiction.TokenWatchAndroid.localization.playerSigningIn
import com.ScienceFiction.TokenWatchAndroid.monster.DemoAdventure
import com.ScienceFiction.TokenWatchAndroid.ui.components.AccountNoticeLine
import com.ScienceFiction.TokenWatchAndroid.ui.components.AvatarView
import com.ScienceFiction.TokenWatchAndroid.ui.components.TerminalBox
import com.ScienceFiction.TokenWatchAndroid.ui.components.TerminalButton
import com.ScienceFiction.TokenWatchAndroid.ui.theme.Term

/** Which app account screen the PLAYER box opens. */
enum class PlayerEntry(val wireId: String) {
    LOGIN("login"),
    SETUP("setup"),
    MANAGE("manage"),
    ;

    companion object {
        fun fromWireId(value: String?): PlayerEntry? = entries.firstOrNull { it.wireId == value }
    }
}

/**
 * The settings PLAYER box — a summary of the app account only (distinct from the provider ACCOUNTS
 * box). Signed out: help + `[ sign in ]`; no profile: `[ finish profile ]`; signed in: avatar and
 * `nickname #tag` + `[ manage ]`. The screens themselves live in [PlayerScreen]. Demo mode shows the sample
 * adventurer ([DemoAdventure]) instead. Callers hide the box entirely while the account feature is unavailable.
 */
@Composable
fun PlayerSection(
    state: AccountState,
    methods: List<AccountMethod>,
    notice: AccountNotice?,
    isDemo: Boolean,
    /** Operator accounts draw with the operator avatar (when bundled) instead of the chosen one. */
    isOperator: Boolean,
    catalog: AvatarCatalog,
    loc: L10n,
    onOpen: (PlayerEntry) -> Unit,
    onRetry: () -> Unit,
) {
    TerminalBox(title = "PLAYER") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (isDemo) {
                val profile = DemoAdventure.profile(loc.lang == Lang.KO)
                SignedInRow(
                    profile = profile,
                    avatar = catalog.resolve(profile.avatarId),
                    methods = DemoAdventure.METHODS,
                    loc = loc,
                    onManage = { onOpen(PlayerEntry.MANAGE) },
                )
                return@Column
            }
            when (state) {
                AccountState.Unavailable -> Unit
                AccountState.SignedOut -> {
                    PlayerHelp(loc.playerSignedOutHelp)
                    TerminalButton("[ sign in ]", onClick = { onOpen(PlayerEntry.LOGIN) })
                }
                is AccountState.SigningIn -> PlayerHelp(loc.playerSigningIn)
                AccountState.Loading -> PlayerHelp(loc.playerLoading)
                AccountState.NeedsProfile -> {
                    PlayerHelp(loc.playerNeedsProfile)
                    TerminalButton("[ finish profile ]", onClick = { onOpen(PlayerEntry.SETUP) })
                }
                AccountState.LoadFailed -> {
                    PlayerHelp(loc.playerLoadFailed)
                    TerminalButton("[ retry ]", onClick = onRetry)
                }
                AccountState.Deleting -> PlayerHelp(loc.playerDeleting)
                is AccountState.SignedIn -> SignedInRow(
                    profile = state.profile,
                    avatar = catalog.resolve(displayAvatarId(state.profile, isOperator, catalog)),
                    methods = methods,
                    loc = loc,
                    onManage = { onOpen(PlayerEntry.MANAGE) },
                )
            }
            notice?.let {
                AccountNoticeLine(text = loc.accountNotice(it), color = if (it.isPositive) Term.Green else Term.Red)
            }
        }
    }
}

/** Avatar, `nickname #tag` and the sign-in methods, with `[ manage ]`. */
@Composable
private fun SignedInRow(
    profile: PlayerProfile,
    avatar: AvatarPreset,
    methods: List<AccountMethod>,
    loc: L10n,
    onManage: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AvatarView(preset = avatar, cell = 3.dp, contentDescription = avatar.name(loc.lang == Lang.KO))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            NametagText(profile = profile, size = 15.sp)
            Text(
                text = methods.joinToString(" · ") { loc.accountMethodName(it) },
                color = Term.Dim,
                style = terminalTextStyle(10.sp),
            )
        }
        TerminalTextButton("[ manage ]", onClick = onManage, size = 12.sp)
    }
}

/** `nickname #1234`: the tag is always shown, dimmed, one space after the nickname (battle-tag style). */
@Composable
internal fun NametagText(profile: PlayerProfile, size: TextUnit) {
    Text(
        text = buildAnnotatedString {
            append(profile.nickname)
            withStyle(SpanStyle(color = Term.Dim)) { append(" #${profile.tag}") }
        },
        color = Term.Foreground,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = terminalTextStyle(size, FontWeight.SemiBold),
        modifier = Modifier.semantics { contentDescription = profile.nametag },
    )
}

@Composable
internal fun PlayerHelp(text: String) {
    Text(text = text, color = Term.Dim, style = terminalTextStyle(10.sp))
}
