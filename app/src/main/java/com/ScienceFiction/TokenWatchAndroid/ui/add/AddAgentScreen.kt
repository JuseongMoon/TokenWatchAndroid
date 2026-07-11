package com.ScienceFiction.TokenWatchAndroid.ui.add

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ScienceFiction.TokenWatchAndroid.auth.OAuthTokens
import com.ScienceFiction.TokenWatchAndroid.auth.ProviderAuthRegistry
import com.ScienceFiction.TokenWatchAndroid.auth.SessionCaptureMode
import com.ScienceFiction.TokenWatchAndroid.auth.device.CopilotDeviceFlow
import com.ScienceFiction.TokenWatchAndroid.auth.device.DeviceCode
import com.ScienceFiction.TokenWatchAndroid.domain.AgentProvider
import com.ScienceFiction.TokenWatchAndroid.localization.L10n
import com.ScienceFiction.TokenWatchAndroid.ui.auth.LoginWebView
import com.ScienceFiction.TokenWatchAndroid.ui.components.BlinkingCursor
import com.ScienceFiction.TokenWatchAndroid.ui.components.TerminalBox
import com.ScienceFiction.TokenWatchAndroid.ui.components.TerminalButton
import com.ScienceFiction.TokenWatchAndroid.ui.components.TerminalSpinner
import com.ScienceFiction.TokenWatchAndroid.ui.theme.Term

@Composable
internal fun AddAgentScreen(
    flowState: AddAgentFlowViewModel,
    providerAuth: ProviderAuthRegistry,
    deviceFlow: CopilotDeviceFlow,
    loc: L10n,
    onAddAgent: suspend (AgentProvider, OAuthTokens) -> Unit,
    onOpenUrl: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val phase = flowState.phase
    val canDismiss = phase != AddAgentPhase.Authenticating
    val dismiss = {
        if (canDismiss) {
            flowState.cancelAndReset()
            onDismiss()
        }
    }

    LaunchedEffect(phase) {
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
            AddAgentPhase.PickProvider -> ProviderPicker { provider ->
                flowState.selectProvider(provider, providerAuth, deviceFlow, loc, onAddAgent)
            }

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
                onKeyChange = flowState::updateApiKey,
                onOpenUrl = onOpenUrl,
                onAdd = { flowState.submitApiKey(providerAuth, loc, onAddAgent) },
            )

            is AddAgentPhase.DeviceFlow -> DeviceFlowContent(
                provider = current.provider,
                device = current.device,
                loc = loc,
                onOpenUrl = onOpenUrl,
            )

            is AddAgentPhase.SessionLogin -> {
                val startUrl = providerAuth.sessionLoginUrl(current.provider)
                if (startUrl == null) {
                    LaunchedEffect(current.provider) { flowState.fail(loc.errAuthMethodUnavailable) }
                } else {
                    val mode = providerAuth.sessionCaptureMode(current.provider)
                    LoginWebView(
                        startUrl = startUrl,
                        cookieProbeUrls = if (mode == SessionCaptureMode.COOKIE) {
                            listOf(startUrl)
                        } else {
                            emptyList()
                        },
                        sessionProbe = if (mode == SessionCaptureMode.COOKIE) {
                            { cookies -> providerAuth.sessionProbe(current.provider, cookies) }
                        } else {
                            null
                        },
                        localStorageProbe = if (mode == SessionCaptureMode.LOCAL_STORAGE) {
                            { values -> providerAuth.localStorageProbe(current.provider, values) }
                        } else {
                            null
                        },
                        onSession = { tokens ->
                            flowState.acceptSession(current.provider, tokens, loc, onAddAgent)
                        },
                        onError = flowState::fail,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            AddAgentPhase.Authenticating -> AuthenticatingContent()
            is AddAgentPhase.Failed -> FailureContent(current.message, flowState::retry)
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
private fun ProviderPicker(onSelect: (AgentProvider) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
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
                    Text(
                        provider.terminalTag,
                        color = providerTerminalColor(provider),
                        style = terminalStyle(15),
                    )
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

@Composable
private fun ApiKeyEntry(
    provider: AgentProvider,
    key: String,
    onKeyChange: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
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
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("$", color = Term.Dim, style = terminalStyle(13))
            Text(
                "paste your ${provider.displayName.lowercase()} api key",
                color = Term.Foreground,
                style = terminalStyle(13),
            )
            BlinkingCursor(symbol = "_", size = 13.sp)
        }
        BasicTextField(
            value = key,
            onValueChange = onKeyChange,
            singleLine = true,
            textStyle = terminalStyle(14).copy(color = Term.Foreground),
            cursorBrush = SolidColor(Term.Green),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii,
            ),
            decorationBox = { input ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Term.Dim.copy(alpha = 0.5f))
                        .padding(12.dp),
                ) {
                    if (key.isEmpty()) {
                        Text("api key…", color = Term.Dim, style = terminalStyle(14))
                    }
                    input()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        provider.apiKeyUrl?.let { url ->
            PlainClickText(
                text = "> [ get api key ↗ ]",
                color = Term.Cyan,
                onClick = { onOpenUrl(url) },
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

@Composable
private fun DeviceFlowContent(
    provider: AgentProvider,
    device: DeviceCode?,
    loc: L10n,
    onOpenUrl: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("$", color = Term.Dim, style = terminalStyle(13))
            Text(
                "login to ${provider.displayName.lowercase()}",
                color = Term.Foreground,
                style = terminalStyle(13),
            )
            BlinkingCursor(symbol = "_", size = 13.sp)
        }
        val current = device
        if (current == null) {
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
            Text(loc.deviceFlowPrompt, color = Term.Dim, style = terminalStyle(13))
            SelectionContainer {
                Text(
                    current.userCode,
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
            TerminalButton(
                title = loc.deviceFlowOpen,
                onClick = { onOpenUrl(current.verificationUri) },
                color = Term.Cyan,
            )
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
    color: androidx.compose.ui.graphics.Color,
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

private fun providerTerminalColor(provider: AgentProvider) = when (provider.terminalColorKey) {
    com.ScienceFiction.TokenWatchAndroid.domain.TerminalColorKey.YELLOW -> Term.Yellow
    com.ScienceFiction.TokenWatchAndroid.domain.TerminalColorKey.CYAN -> Term.Cyan
    com.ScienceFiction.TokenWatchAndroid.domain.TerminalColorKey.ORANGE -> Term.Orange
    com.ScienceFiction.TokenWatchAndroid.domain.TerminalColorKey.MAGENTA -> Term.Magenta
    com.ScienceFiction.TokenWatchAndroid.domain.TerminalColorKey.BLUE -> Term.Blue
    com.ScienceFiction.TokenWatchAndroid.domain.TerminalColorKey.GREEN -> Term.Green
    com.ScienceFiction.TokenWatchAndroid.domain.TerminalColorKey.PINK -> Term.Pink
    com.ScienceFiction.TokenWatchAndroid.domain.TerminalColorKey.TEAL -> Term.Teal
    com.ScienceFiction.TokenWatchAndroid.domain.TerminalColorKey.FOREGROUND -> Term.Foreground
}
