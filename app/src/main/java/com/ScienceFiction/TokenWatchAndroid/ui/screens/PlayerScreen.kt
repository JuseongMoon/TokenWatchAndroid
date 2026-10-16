package com.ScienceFiction.TokenWatchAndroid.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ScienceFiction.TokenWatchAndroid.BuildConfig
import com.ScienceFiction.TokenWatchAndroid.account.AccountState
import com.ScienceFiction.TokenWatchAndroid.account.AccountStore
import com.ScienceFiction.TokenWatchAndroid.account.SaveOutcome
import com.ScienceFiction.TokenWatchAndroid.analytics.ScreenName
import com.ScienceFiction.TokenWatchAndroid.domain.account.AccountMethod
import com.ScienceFiction.TokenWatchAndroid.domain.account.AvatarCatalog
import com.ScienceFiction.TokenWatchAndroid.domain.account.NicknameError
import com.ScienceFiction.TokenWatchAndroid.domain.account.NicknamePolicy
import com.ScienceFiction.TokenWatchAndroid.domain.account.PlayerProfile
import com.ScienceFiction.TokenWatchAndroid.localization.L10n
import com.ScienceFiction.TokenWatchAndroid.localization.Lang
import com.ScienceFiction.TokenWatchAndroid.localization.accountAvatarHelp
import com.ScienceFiction.TokenWatchAndroid.localization.accountDelete
import com.ScienceFiction.TokenWatchAndroid.localization.accountDeleteMessage
import com.ScienceFiction.TokenWatchAndroid.localization.accountDeleteTitle
import com.ScienceFiction.TokenWatchAndroid.localization.accountGoogleNotConfigured
import com.ScienceFiction.TokenWatchAndroid.localization.accountLinkHelp
import com.ScienceFiction.TokenWatchAndroid.localization.accountLoginIntro
import com.ScienceFiction.TokenWatchAndroid.localization.accountMethodName
import com.ScienceFiction.TokenWatchAndroid.localization.accountNotice
import com.ScienceFiction.TokenWatchAndroid.localization.accountSignOut
import com.ScienceFiction.TokenWatchAndroid.localization.accountSignOutMessage
import com.ScienceFiction.TokenWatchAndroid.localization.accountSignOutTitle
import com.ScienceFiction.TokenWatchAndroid.localization.nicknameCooldownRemaining
import com.ScienceFiction.TokenWatchAndroid.localization.nicknameError
import com.ScienceFiction.TokenWatchAndroid.localization.nicknamePlaceholder
import com.ScienceFiction.TokenWatchAndroid.localization.nicknameRule
import com.ScienceFiction.TokenWatchAndroid.localization.playerDeleting
import com.ScienceFiction.TokenWatchAndroid.localization.playerLoading
import com.ScienceFiction.TokenWatchAndroid.localization.playerSigningIn
import com.ScienceFiction.TokenWatchAndroid.localization.privacyLink
import com.ScienceFiction.TokenWatchAndroid.localization.profileSetupIntro
import com.ScienceFiction.TokenWatchAndroid.localization.profileSetupQuit
import com.ScienceFiction.TokenWatchAndroid.localization.termsAgree
import com.ScienceFiction.TokenWatchAndroid.localization.termsLink
import com.ScienceFiction.TokenWatchAndroid.ui.auth.InAppBrowser
import com.ScienceFiction.TokenWatchAndroid.ui.components.AccountBrandButtons
import com.ScienceFiction.TokenWatchAndroid.ui.components.AccountNoticeLine
import com.ScienceFiction.TokenWatchAndroid.ui.components.AvatarPicker
import com.ScienceFiction.TokenWatchAndroid.ui.components.AvatarView
import com.ScienceFiction.TokenWatchAndroid.ui.components.NicknameField
import com.ScienceFiction.TokenWatchAndroid.ui.components.TerminalBox
import com.ScienceFiction.TokenWatchAndroid.ui.components.TerminalButton
import com.ScienceFiction.TokenWatchAndroid.ui.components.TerminalConfirmDialog
import com.ScienceFiction.TokenWatchAndroid.ui.components.TerminalSpinner
import com.ScienceFiction.TokenWatchAndroid.ui.theme.Term
import kotlinx.coroutines.launch

/** Terms and privacy pages, opened in the in-app browser. */
internal object AccountLinks {
    const val TERMS = "https://sciencefiction.co.kr/tokenwatch/terms/"
    const val PRIVACY = "https://sciencefiction.co.kr/tokenwatch/privacy/"
}

/**
 * The app account screen. It shows one of three screens by state — sign-in (`account_login`), profile
 * setup (`profile_setup`) and account (`account`) — and logs each as it appears, the same unit as iOS.
 * Entered to sign in or finish signing up, it closes once a profile exists; entered to manage, it closes
 * after a sign-out or a deletion.
 */
@Composable
fun PlayerScreen(
    entry: PlayerEntry,
    account: AccountStore,
    catalog: AvatarCatalog,
    loc: L10n,
    onScreen: (ScreenName) -> Unit,
    onDone: () -> Unit,
) {
    val state by account.state.collectAsStateWithLifecycle()

    BackHandler(onBack = onDone)
    LaunchedEffect(state, entry) {
        when {
            state is AccountState.SignedIn && entry != PlayerEntry.MANAGE -> onDone()
            state == AccountState.SignedOut && entry != PlayerEntry.LOGIN -> onDone()
            state == AccountState.Unavailable -> onDone()
        }
    }
    DisposableEffect(account) { onDispose { account.clearNotice() } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Term.Background),
    ) {
        ScreenTopBar(
            title = "PLAYER",
            modifier = Modifier.statusBarsPadding(),
            trailing = { TerminalTextButton(text = "[done]", color = Term.Green, onClick = onDone) },
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .imePadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (val current = state) {
                AccountState.Unavailable -> Unit
                AccountState.SignedOut, is AccountState.SigningIn, AccountState.LoadFailed ->
                    AccountLoginContent(account, current, loc, onScreen)
                AccountState.Loading -> StatusLine(loc.playerLoading)
                AccountState.NeedsProfile -> ProfileSetupContent(account, catalog, loc, onScreen)
                is AccountState.SignedIn -> AccountContent(account, current.profile, catalog, loc, onScreen)
                AccountState.Deleting -> StatusLine(loc.playerDeleting)
            }
        }
    }
}

@Composable
private fun StatusLine(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        TerminalSpinner(color = Term.Cyan, size = 12.sp)
        Text(text = text, color = Term.Dim, style = terminalTextStyle(12.sp))
    }
}

// MARK: Sign-in

@Composable
private fun AccountLoginContent(
    account: AccountStore,
    state: AccountState,
    loc: L10n,
    onScreen: (ScreenName) -> Unit,
) {
    val notice by account.notice.collectAsStateWithLifecycle()
    val canStart = state == AccountState.SignedOut || state == AccountState.LoadFailed
    LaunchedEffect(Unit) { onScreen(ScreenName.ACCOUNT_LOGIN) }

    TerminalBox(title = "SIGN IN") {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(text = loc.accountLoginIntro, color = Term.Dim, style = terminalTextStyle(11.sp))
            AccountBrandButtons(
                showApple = true,
                showGoogle = account.isGoogleAvailable,
                enabled = canStart,
                onApple = {
                    account.clearNotice()
                    account.perform { signIn(AccountMethod.APPLE) }
                },
                onGoogle = {
                    account.clearNotice()
                    account.perform { signIn(AccountMethod.GOOGLE) }
                },
            )
            if (BuildConfig.DEBUG && !account.isGoogleAvailable) {
                Text(text = loc.accountGoogleNotConfigured, color = Term.Yellow, style = terminalTextStyle(10.sp))
            }
            if (state is AccountState.SigningIn) StatusLine(loc.playerSigningIn)
            notice?.let { AccountNoticeLine(text = loc.accountNotice(it)) }
        }
    }
}

// MARK: Profile setup (first sign-in)

@Composable
private fun ProfileSetupContent(
    account: AccountStore,
    catalog: AvatarCatalog,
    loc: L10n,
    onScreen: (ScreenName) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val notice by account.notice.collectAsStateWithLifecycle()
    val isWorking by account.isWorking.collectAsStateWithLifecycle()
    var nickname by rememberSaveable { mutableStateOf("") }
    var avatarId by rememberSaveable { mutableStateOf(catalog.random().id) }
    var termsAccepted by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<NicknameError?>(null) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { onScreen(ScreenName.PROFILE_SETUP) }

    TerminalBox(title = "PROFILE") {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(text = loc.profileSetupIntro, color = Term.Dim, style = terminalTextStyle(11.sp))

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FieldLabel("nickname")
                NicknameField(
                    value = nickname,
                    onValueChange = {
                        nickname = it
                        error = null
                    },
                    placeholder = loc.nicknamePlaceholder,
                )
                Text(text = loc.nicknameRule, color = Term.Dim, style = terminalTextStyle(10.sp))
                error?.takeIf { it != NicknameError.TERMS_REQUIRED }?.let {
                    AccountNoticeLine(text = loc.nicknameError(it))
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FieldLabel("avatar")
                AvatarPicker(
                    catalog = catalog,
                    selection = avatarId,
                    korean = loc.lang == Lang.KO,
                    onSelect = { avatarId = it },
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TermsToggle(
                    checked = termsAccepted,
                    title = loc.termsAgree,
                    onClick = {
                        termsAccepted = !termsAccepted
                        if (termsAccepted && error == NicknameError.TERMS_REQUIRED) error = null
                    },
                )
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
                if (error == NicknameError.TERMS_REQUIRED) {
                    AccountNoticeLine(text = loc.nicknameError(NicknameError.TERMS_REQUIRED))
                }
            }

            TerminalButton(
                title = if (isWorking) "[ saving… ]" else "[ save ]",
                enabled = !isWorking,
                onClick = {
                    account.clearNotice()
                    scope.launch {
                        val outcome = account.perform { createProfile(nickname, avatarId, termsAccepted) }.await()
                        error = (outcome as? SaveOutcome.Invalid)?.error
                    }
                },
            )
            notice?.let { AccountNoticeLine(text = loc.accountNotice(it)) }
        }
    }

    // A way out after only signing in: the Auth record must be deletable too (account deletion rules).
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = loc.profileSetupQuit, color = Term.Dim, style = terminalTextStyle(10.sp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TerminalTextButton(
                "[ sign out ]",
                onClick = { account.perform { signOut() } },
                color = Term.Cyan,
                enabled = !isWorking,
                size = 12.sp,
                weight = FontWeight.Normal,
            )
            TerminalTextButton(
                "[ delete account ]",
                onClick = { confirmDelete = true },
                color = Term.Red,
                enabled = !isWorking,
                size = 12.sp,
                weight = FontWeight.Normal,
            )
        }
    }

    if (confirmDelete) {
        TerminalConfirmDialog(
            title = loc.accountDeleteTitle,
            message = loc.accountDeleteMessage,
            confirmLabel = "[ ${loc.accountDelete} ]",
            cancelLabel = "[ ${loc.cancel} ]",
            onConfirm = {
                confirmDelete = false
                account.perform { deleteAccount() }
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

// MARK: Account

@Composable
private fun AccountContent(
    account: AccountStore,
    profile: PlayerProfile,
    catalog: AvatarCatalog,
    loc: L10n,
    onScreen: (ScreenName) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val notice by account.notice.collectAsStateWithLifecycle()
    val methods by account.methods.collectAsStateWithLifecycle()
    val isWorking by account.isWorking.collectAsStateWithLifecycle()
    val korean = loc.lang == Lang.KO
    var nickname by rememberSaveable(profile.nickname) { mutableStateOf(profile.nickname) }
    var nicknameError by rememberSaveable { mutableStateOf<NicknameError?>(null) }
    var avatarId by rememberSaveable(profile.avatarId) { mutableStateOf(profile.avatarId) }
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { onScreen(ScreenName.ACCOUNT) }

    val avatar = catalog.resolve(profile.avatarId)
    TerminalBox(title = "PROFILE") {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                AvatarView(preset = avatar, cell = 4.dp, contentDescription = avatar.name(korean))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = profile.nickname,
                        color = Term.Foreground,
                        style = terminalTextStyle(16.sp, FontWeight.SemiBold),
                    )
                    Text(
                        text = methods.joinToString(" · ") { loc.accountMethodName(it) },
                        color = Term.Dim,
                        style = terminalTextStyle(10.sp),
                    )
                }
            }
            notice?.let {
                AccountNoticeLine(text = loc.accountNotice(it), color = if (it.isPositive) Term.Green else Term.Red)
            }
        }
    }

    val cooldown = remember(profile.nicknameChangedAtMs, isWorking) {
        val ms = account.nicknameCooldownRemainingMs()
        if (ms <= 0) {
            null
        } else {
            // Rounded up, so "0 hours left" never shows.
            val hours = ((ms + 3_599_999) / 3_600_000).toInt()
            hours / 24 to hours % 24
        }
    }
    TerminalBox(title = "NICKNAME") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            NicknameField(
                value = nickname,
                onValueChange = {
                    nickname = it
                    nicknameError = null
                },
                placeholder = loc.nicknamePlaceholder,
                enabled = cooldown == null,
            )
            if (cooldown != null) {
                Text(
                    text = "${loc.nicknameError(NicknameError.COOLDOWN)} " +
                        loc.nicknameCooldownRemaining(cooldown.first, cooldown.second),
                    color = Term.Yellow,
                    style = terminalTextStyle(10.sp),
                )
            } else {
                Text(text = loc.nicknameRule, color = Term.Dim, style = terminalTextStyle(10.sp))
                if (NicknamePolicy.normalize(nickname) != profile.nickname) {
                    TerminalButton(
                        title = if (isWorking) "[ saving… ]" else "[ save nickname ]",
                        enabled = !isWorking,
                        onClick = {
                            account.clearNotice()
                            scope.launch {
                                val outcome = account.perform { changeNickname(nickname) }.await()
                                nicknameError = (outcome as? SaveOutcome.Invalid)?.error
                            }
                        },
                    )
                }
            }
            nicknameError?.let { AccountNoticeLine(text = loc.nicknameError(it)) }
        }
    }

    TerminalBox(title = "AVATAR") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AvatarPicker(catalog = catalog, selection = avatarId, korean = korean, onSelect = { avatarId = it })
            if (avatarId != profile.avatarId) {
                TerminalButton(
                    title = if (isWorking) "[ saving… ]" else "[ save avatar ]",
                    enabled = !isWorking,
                    onClick = {
                        account.clearNotice()
                        account.perform { changeAvatar(avatarId) }
                    },
                )
            } else {
                Text(text = loc.accountAvatarHelp, color = Term.Dim, style = terminalTextStyle(10.sp))
            }
        }
    }

    val missing = AccountMethod.entries.filter {
        it !in methods && (it != AccountMethod.GOOGLE || account.isGoogleAvailable)
    }
    if (missing.isNotEmpty()) {
        TerminalBox(title = "LINK") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text = loc.accountLinkHelp, color = Term.Dim, style = terminalTextStyle(10.sp))
                AccountBrandButtons(
                    showApple = AccountMethod.APPLE in missing,
                    showGoogle = AccountMethod.GOOGLE in missing,
                    enabled = !isWorking,
                    continueLabels = true,
                    onApple = {
                        account.clearNotice()
                        account.perform { link(AccountMethod.APPLE) }
                    },
                    onGoogle = {
                        account.clearNotice()
                        account.perform { link(AccountMethod.GOOGLE) }
                    },
                )
            }
        }
    }

    TerminalBox(title = "SESSION") {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TerminalTextButton(
                "[ sign out ]",
                onClick = { confirmSignOut = true },
                color = Term.Cyan,
                enabled = !isWorking,
                weight = FontWeight.Normal,
            )
            Spacer(modifier = Modifier.weight(1f))
            TerminalTextButton(
                "[ delete account ]",
                onClick = { confirmDelete = true },
                color = Term.Red,
                enabled = !isWorking,
                weight = FontWeight.Normal,
            )
        }
    }

    if (confirmSignOut) {
        TerminalConfirmDialog(
            title = loc.accountSignOutTitle,
            accountLabel = profile.nickname,
            message = loc.accountSignOutMessage,
            confirmLabel = "[ ${loc.accountSignOut} ]",
            cancelLabel = "[ ${loc.cancel} ]",
            onConfirm = {
                confirmSignOut = false
                account.perform { signOut() }
            },
            onDismiss = { confirmSignOut = false },
        )
    }
    if (confirmDelete) {
        TerminalConfirmDialog(
            title = loc.accountDeleteTitle,
            accountLabel = profile.nickname,
            message = loc.accountDeleteMessage,
            confirmLabel = "[ ${loc.accountDelete} ]",
            cancelLabel = "[ ${loc.cancel} ]",
            onConfirm = {
                confirmDelete = false
                account.perform { deleteAccount() }
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text = text, color = Term.Cyan, style = terminalTextStyle(11.sp, FontWeight.SemiBold))
}

@Composable
private fun TermsToggle(checked: Boolean, title: String, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(interactionSource = interactionSource, indication = null, role = Role.Checkbox, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = if (checked) "[x]" else "[ ]",
            color = if (checked) Term.Green else Term.Dim,
            style = terminalTextStyle(12.sp),
        )
        Text(text = title, color = Term.Foreground, style = terminalTextStyle(12.sp), modifier = Modifier.weight(1f))
    }
}
