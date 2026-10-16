package com.ScienceFiction.TokenWatchAndroid.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ScienceFiction.TokenWatchAndroid.account.AccountNotice
import com.ScienceFiction.TokenWatchAndroid.account.AccountState
import com.ScienceFiction.TokenWatchAndroid.domain.account.AccountMethod
import com.ScienceFiction.TokenWatchAndroid.domain.account.AvatarCatalog
import com.ScienceFiction.TokenWatchAndroid.localization.L10n
import com.ScienceFiction.TokenWatchAndroid.localization.Lang
import com.ScienceFiction.TokenWatchAndroid.localization.accountMethodName
import com.ScienceFiction.TokenWatchAndroid.localization.accountNotice
import com.ScienceFiction.TokenWatchAndroid.localization.playerDeleting
import com.ScienceFiction.TokenWatchAndroid.localization.playerDemoDisabled
import com.ScienceFiction.TokenWatchAndroid.localization.playerLoadFailed
import com.ScienceFiction.TokenWatchAndroid.localization.playerLoading
import com.ScienceFiction.TokenWatchAndroid.localization.playerNeedsProfile
import com.ScienceFiction.TokenWatchAndroid.localization.playerSignedOutHelp
import com.ScienceFiction.TokenWatchAndroid.localization.playerSigningIn
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
 * nickname + `[ manage ]`. The screens themselves live in [PlayerScreen]. Demo mode shows only a
 * disabled notice. Callers hide the box entirely while the account feature is unavailable.
 */
@Composable
fun PlayerSection(
    state: AccountState,
    methods: List<AccountMethod>,
    notice: AccountNotice?,
    isDemo: Boolean,
    catalog: AvatarCatalog,
    loc: L10n,
    onOpen: (PlayerEntry) -> Unit,
    onRetry: () -> Unit,
) {
    TerminalBox(title = "PLAYER") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (isDemo) {
                PlayerHelp(loc.playerDemoDisabled)
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
                is AccountState.SignedIn -> {
                    val avatar = catalog.resolve(state.profile.avatarId)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AvatarView(
                            preset = avatar,
                            cell = 3.dp,
                            contentDescription = avatar.name(loc.lang == Lang.KO),
                        )
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                text = state.profile.nickname,
                                color = Term.Foreground,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = terminalTextStyle(15.sp, FontWeight.SemiBold),
                            )
                            Text(
                                text = methods.joinToString(" · ") { loc.accountMethodName(it) },
                                color = Term.Dim,
                                style = terminalTextStyle(10.sp),
                            )
                        }
                        TerminalTextButton("[ manage ]", onClick = { onOpen(PlayerEntry.MANAGE) }, size = 12.sp)
                    }
                }
            }
            notice?.let {
                AccountNoticeLine(text = loc.accountNotice(it), color = if (it.isPositive) Term.Green else Term.Red)
            }
        }
    }
}

@Composable
internal fun PlayerHelp(text: String) {
    Text(text = text, color = Term.Dim, style = terminalTextStyle(10.sp))
}
