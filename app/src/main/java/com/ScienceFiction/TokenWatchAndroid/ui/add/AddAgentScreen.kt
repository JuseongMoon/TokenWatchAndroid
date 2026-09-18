package com.ScienceFiction.TokenWatchAndroid.ui.add

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.browser.auth.AuthTabIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ScienceFiction.TokenWatchAndroid.auth.OAuthTokens
import com.ScienceFiction.TokenWatchAndroid.auth.ProviderAuthRegistry
import com.ScienceFiction.TokenWatchAndroid.domain.AgentProvider
import com.ScienceFiction.TokenWatchAndroid.localization.L10n
import com.ScienceFiction.TokenWatchAndroid.ui.auth.InAppBrowser
import com.ScienceFiction.TokenWatchAndroid.ui.auth.LoginWebView
import com.ScienceFiction.TokenWatchAndroid.ui.components.AnimatedPixelSpriteView
import com.ScienceFiction.TokenWatchAndroid.ui.components.BlinkingCursor
import com.ScienceFiction.TokenWatchAndroid.ui.components.PixelSprite
import com.ScienceFiction.TokenWatchAndroid.ui.components.TerminalBox
import com.ScienceFiction.TokenWatchAndroid.ui.components.TerminalButton
import com.ScienceFiction.TokenWatchAndroid.ui.components.TerminalSpinner
import com.ScienceFiction.TokenWatchAndroid.ui.screens.terminalColor
import com.ScienceFiction.TokenWatchAndroid.ui.theme.Term
import kotlinx.coroutines.launch

/**
 * Add-agent flow (terminal style): pick a provider → sign in → exchange → add. The sign-in UI
 * follows the provider's auth kind, as on iOS `AddAgentSheet`:
 * - `OAUTH_BROWSER` (Claude, Grok): in-app sign-in window + loopback callback; Claude also has a
 *   paste-the-code fallback.
 * - `OAUTH_CODE` (Codex): WebView with an intercepted callback.
 * - `OAUTH_DEVICE_FLOW` (Copilot, Cursor): approval page in an in-app tab while polling.
 * - `API_KEY` (the rest): paste a key.
 */
@Composable
internal fun AddAgentScreen(
    flowState: AddAgentFlowViewModel,
    providerAuth: ProviderAuthRegistry,
    loc: L10n,
    onAddAgent: suspend (AgentProvider, OAuthTokens) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val phase = flowState.phase
    val canDismiss = phase != AddAgentPhase.Authenticating
    val dismiss = {
        if (canDismiss) {
            flowState.cancelAndReset()
            onDismiss()
        }
    }
    val signInLauncher = rememberLauncherForActivityResult(AuthTabIntent.AuthenticateUserResultContract()) { result ->
        flowState.onBrowserWindowResult(result.resultCode, result.resultUri?.toString(), providerAuth, loc, onAddAgent)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) flowState.onAppResumed()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(phase) {
        val finished = phase == AddAgentPhase.Authenticating || phase == AddAgentPhase.Completed
        // A login that completed under a still-open tab (approval pages cannot close themselves).
        if (
            finished && flowState.loginWindowOpen &&
            !lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        ) {
            InAppBrowser.bringAppToFront(context)
        }
        if (phase == AddAgentPhase.Completed) {
            flowState.cancelAndReset()
            onDismiss()
        }
    }
    BackHandler { if (canDismiss) dismiss() }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Term.Background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        AddAgentTopBar(enabled = canDismiss, onDismiss = dismiss)
        when (val current = phase) {
            AddAgentPhase.PickProvider -> ProviderPicker(loc) { provider ->
                flowState.selectProvider(provider, providerAuth, loc, onAddAgent)
            }

            is AddAgentPhase.BrowserLogin -> BrowserLoginContent(
                phase = current,
                loc = loc,
                hasManualFallback = providerAuth.manualCodeRedirect(current.provider) != null,
                flowState = flowState,
                providerAuth = providerAuth,
                onAddAgent = onAddAgent,
                launchSignIn = { url, ephemeral -> InAppBrowser.launchSignIn(signInLauncher, url, ephemeral) },
            )

            is AddAgentPhase.OAuthLogin -> {
                val client = remember(current.provider) { providerAuth.oauthClient(current.provider) }
                LoginWebView(
                    startUrl = client.authorizeUrl(current.pkce),
                    callbackParser = client::parseCallback,
                    onCode = { callback ->
                        flowState.exchangeOAuth(callback, providerAuth, loc, onAddAgent)
                    },
                    onError = flowState::fail,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            is AddAgentPhase.ApiKey -> ApiKeyEntry(
                provider = current.provider,
                key = flowState.apiKeyText,
                hint = loc.apiKeyHint(current.provider),
                onKeyChange = flowState::updateApiKey,
                // Key pages need a signed-in account, so they open in the app rather than outside it.
                onOpenKeyPage = { url -> InAppBrowser.open(context, url) },
                onAdd = { flowState.submitApiKey(providerAuth, loc, onAddAgent) },
            )

            is AddAgentPhase.DeviceFlow -> DeviceFlowContent(
                phase = current,
                loc = loc,
                onOpenPage = { url, userCode ->
                    // The code field is under the tab, so the code goes to the clipboard first.
                    userCode?.let { copyToClipboard(context, it) }
                    if (InAppBrowser.open(context, url)) flowState.markLoginWindowOpened()
                },
                onAutoOpened = flowState::consumeAutoOpen,
            )

            AddAgentPhase.Authenticating -> AuthenticatingContent()
            is AddAgentPhase.Failed -> FailureContent(current.message) {
                flowState.retry(providerAuth, loc, onAddAgent)
            }
            AddAgentPhase.Completed -> AuthenticatingContent()
        }
    }
}

@Composable
private fun AddAgentTopBar(enabled: Boolean, onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = "ADD AGENT",
            color = Term.Foreground,
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.Center),
        )
        PlainClickText(
            text = "[esc]",
            color = Term.Dim.copy(alpha = if (enabled) 1f else 0.35f),
            onClick = onDismiss,
            enabled = enabled,
            modifier = Modifier.align(Alignment.CenterStart),
        )
    }
}

@Composable
private fun ProviderPicker(loc: L10n, onSelect: (AgentProvider) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PolicyNotice(loc)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("$", color = Term.Dim, style = terminalStyle(13))
            Text("select a service to login", color = Term.Foreground, style = terminalStyle(13))
            BlinkingCursor(symbol = "_", size = 13.sp)
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AgentProvider.entries.forEachIndexed { index, provider ->
                val interactionSource = remember(provider) { MutableInteractionSource() }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Term.Dim.copy(alpha = 0.5f))
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = { onSelect(provider) },
                        )
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(">", color = Term.Green, style = terminalStyle(15))
                    Text("[${index + 1}]", color = Term.Dim, style = terminalStyle(15))
                    Text(provider.terminalTag, color = provider.terminalColor(), style = terminalStyle(15))
                    Text(
                        provider.displayName.lowercase(),
                        color = Term.Foreground,
                        style = terminalStyle(15),
                    )
                    Spacer(Modifier.weight(1f))
                    Text("❯", color = Term.Dim, style = terminalStyle(15))
                }
            }
        }
    }
}

/**
 * Sign-in and lookups depend on each provider's policies and can stop without notice, which the
 * list says up front (iOS 41f4cde…ee04cbe). Signed by the team with the weekly gauge's green slime
 * hopping in place; the sprite stands still when animations are off and is hidden from TalkBack.
 */
@Composable
private fun PolicyNotice(loc: L10n) {
    TerminalBox(
        title = "NOTE",
        titleColor = Term.Yellow,
        borderColor = Term.Dim.copy(alpha = 0.5f),
        contentPadding = 12.dp,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(loc.addAgentPolicyNotice, color = Term.Dim, style = terminalStyle(12))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(loc.addAgentNoticeSignature, color = Term.Dim, style = terminalStyle(11))
                AnimatedPixelSpriteView(sprite = PixelSprite.Slime, cell = 2.dp)
            }
        }
    }
}

/**
 * Sign-in through the in-app window (`OAUTH_BROWSER`). The flow itself (listener + window) lives in
 * [AddAgentFlowViewModel]; this shows the buttons and state. The result arrives either
 * automatically once the user approves, or, for providers with the fallback, as a code the user
 * copies from the console page.
 */
@Composable
private fun BrowserLoginContent(
    phase: AddAgentPhase.BrowserLogin,
    loc: L10n,
    hasManualFallback: Boolean,
    flowState: AddAgentFlowViewModel,
    providerAuth: ProviderAuthRegistry,
    onAddAgent: suspend (AgentProvider, OAuthTokens) -> Unit,
    launchSignIn: (url: String, ephemeral: Boolean) -> Boolean,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val supportsOtherAccount = remember { InAppBrowser.supportsEphemeralSignIn(context) }
    var codeText by rememberSaveable(phase.provider) { mutableStateOf("") }

    fun signIn(ephemeral: Boolean) {
        scope.launch {
            val url = flowState.beginBrowserLogin(providerAuth, loc, onAddAgent) ?: return@launch
            if (!launchSignIn(url, ephemeral)) flowState.browserLaunchFailed(providerAuth, loc)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        PromptLine("login to ${phase.provider.displayName.lowercase()}")
        if (!phase.manualEntry) {
            Text(loc.browserSheetIntro(phase.provider.displayName), color = Term.Foreground, style = terminalStyle(13))
            if (phase.isPresenting) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TerminalSpinner(size = 12.sp)
                    Text(loc.browserWaiting, color = Term.Foreground, style = terminalStyle(13))
                }
            } else {
                TerminalButton(title = loc.browserSheetOpen, onClick = { signIn(ephemeral = false) })
                if (phase.wasCancelled) {
                    Text(loc.browserCancelledHint, color = Term.Dim, style = terminalStyle(12))
                }
                // Only offered where the browser can really skip its signed-in session; elsewhere it
                // would do exactly what the button above does.
                if (supportsOtherAccount) {
                    Text(loc.browserOtherAccountHint, color = Term.Dim, style = terminalStyle(12))
                    TerminalButton(
                        title = loc.browserOtherAccount,
                        onClick = { signIn(ephemeral = true) },
                        color = Term.Cyan,
                        dashedBorder = true,
                    )
                }
            }
            if (hasManualFallback) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(loc.browserManualHint, color = Term.Dim, style = terminalStyle(12))
                    TerminalButton(
                        title = loc.browserManualButton,
                        onClick = flowState::showManualEntry,
                        color = Term.Dim,
                        dashedBorder = true,
                    )
                }
            }
        } else {
            Text(loc.manualCodePrompt, color = Term.Foreground, style = terminalStyle(13))
            TerminalButton(
                title = loc.manualCodeGet,
                onClick = { flowState.manualAuthorizeUrl(providerAuth)?.let { InAppBrowser.open(context, it) } },
                color = Term.Cyan,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                TerminalTextField(
                    value = codeText,
                    onValueChange = { codeText = it },
                    placeholder = loc.manualCodePlaceholder,
                    modifier = Modifier.weight(1f),
                )
                PlainClickText(
                    text = loc.manualPaste,
                    color = Term.Green,
                    onClick = { pasteFromClipboard(context)?.let { codeText = it } },
                )
            }
            TerminalButton(
                title = loc.manualConnect,
                onClick = { flowState.submitManualCode(codeText, providerAuth, loc, onAddAgent) },
            )
        }
        phase.inlineError?.let { Text(it, color = Term.Red, style = terminalStyle(12)) }
    }
}

@Composable
private fun ApiKeyEntry(
    provider: AgentProvider,
    key: String,
    hint: String?,
    onKeyChange: (String) -> Unit,
    onOpenKeyPage: (String) -> Unit,
    onAdd: () -> Unit,
) {
    val trimmed = key.trim()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PromptLine("paste your ${provider.displayName.lowercase()} api key")
        TerminalTextField(
            value = key,
            onValueChange = onKeyChange,
            placeholder = "api key…",
            secret = true,
            modifier = Modifier.fillMaxWidth(),
        )
        hint?.let { Text(it, color = Term.Dim, style = terminalStyle(12)) }
        provider.apiKeyUrl?.let { url ->
            PlainClickText(
                text = "> [ get api key ↗ ]",
                color = Term.Cyan,
                onClick = { onOpenKeyPage(url) },
            )
        }
        TerminalButton(
            title = "[ ADD ]",
            onClick = onAdd,
            enabled = trimmed.isNotEmpty(),
            color = if (trimmed.isNotEmpty()) Term.Green else Term.Dim,
        )
    }
}

/**
 * Polling login: the approval page opens in an in-app tab while the app polls. Copilot shows a code
 * to enter there; a code-less flow (Cursor) opens its page right away and only needs approval.
 */
@Composable
private fun DeviceFlowContent(
    phase: AddAgentPhase.DeviceFlow,
    loc: L10n,
    onOpenPage: (url: String, userCode: String?) -> Unit,
    onAutoOpened: () -> Unit,
) {
    val login = phase.login
    LaunchedEffect(phase.autoOpenPending) {
        val url = login?.verificationUrl
        if (phase.autoOpenPending && url != null) {
            onAutoOpened()
            onOpenPage(url, null)
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        PromptLine("login to ${phase.provider.displayName.lowercase()}")
        if (login == null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 80.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TerminalSpinner()
                Text(loc.deviceFlowRequesting, color = Term.Foreground, style = terminalStyle(14))
            }
        } else {
            val userCode = login.userCode
            if (userCode != null) {
                Text(loc.deviceFlowPrompt, color = Term.Dim, style = terminalStyle(13))
                SelectionContainer {
                    Text(
                        userCode,
                        color = Term.Green,
                        style = terminalStyle(28).copy(fontWeight = FontWeight.Bold),
                        letterSpacing = 4.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, Term.Dim.copy(alpha = 0.6f))
                            .padding(vertical = 18.dp),
                    )
                }
            } else {
                Text(loc.pollingLoginPrompt(phase.provider.displayName), color = Term.Dim, style = terminalStyle(13))
            }
            login.verificationUrl?.let { url ->
                TerminalButton(
                    title = if (userCode == null) loc.pollingLoginOpen else loc.deviceFlowOpen,
                    onClick = { onOpenPage(url, userCode) },
                    color = Term.Cyan,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TerminalSpinner(size = 12.sp)
                Text(loc.deviceFlowWaiting, color = Term.Dim, style = terminalStyle(12))
            }
        }
    }
}

@Composable
private fun AuthenticatingContent() {
    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TerminalSpinner()
        Text(
            "  authenticating… ",
            color = Term.Foreground,
            style = terminalStyle(15),
        )
        BlinkingCursor(symbol = "_", size = 14.sp)
    }
}

@Composable
private fun FailureContent(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TerminalBox(
            title = "ERROR",
            titleColor = Term.Red,
            borderColor = Term.Red.copy(alpha = 0.7f),
        ) {
            Text(
                "!! LOGIN FAILED",
                color = Term.Red,
                style = terminalStyle(14).copy(fontWeight = FontWeight.Bold),
            )
            Text(message, color = Term.Foreground.copy(alpha = 0.85f), style = terminalStyle(12))
        }
        TerminalButton(title = "[ RETRY ]", onClick = onRetry)
    }
}

@Composable
private fun PlainClickText(
    text: String,
    color: Color,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Text(
        text = text,
        color = color,
        style = terminalStyle(13).copy(fontWeight = FontWeight.SemiBold),
        modifier = modifier.clickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            onClick = onClick,
        ),
    )
}

private fun terminalStyle(size: Int) = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = size.sp,
)

@Composable
private fun PromptLine(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("$", color = Term.Dim, style = terminalStyle(13))
        Text(text, color = Term.Foreground, style = terminalStyle(13))
        BlinkingCursor(symbol = "_", size = 13.sp)
    }
}

/**
 * Single-line terminal input. [secret] masks the value, which also keeps an API key out of the
 * recents screenshot.
 */
@Composable
private fun TerminalTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    secret: Boolean = false,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        textStyle = terminalStyle(14).copy(color = Term.Foreground),
        cursorBrush = SolidColor(Term.Green),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = if (secret) KeyboardType.Ascii else KeyboardType.Uri,
        ),
        decorationBox = { input ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Term.Dim.copy(alpha = 0.5f))
                    .padding(12.dp),
            ) {
                if (value.isEmpty()) {
                    Text(placeholder, color = Term.Dim, style = terminalStyle(14))
                }
                input()
            }
        },
        modifier = modifier,
    )
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    runCatching { clipboard.setPrimaryClip(ClipData.newPlainText("code", text)) }
}

/** Reads the clipboard on an explicit tap only, so the system's paste notice is expected. */
private fun pasteFromClipboard(context: Context): String? {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return null
    return runCatching {
        clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
    }.getOrNull()?.takeIf(String::isNotBlank)
}
