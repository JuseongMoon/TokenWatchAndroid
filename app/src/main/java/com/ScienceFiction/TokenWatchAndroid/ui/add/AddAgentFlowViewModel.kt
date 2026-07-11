package com.ScienceFiction.TokenWatchAndroid.ui.add

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ScienceFiction.TokenWatchAndroid.auth.OAuthTokens
import com.ScienceFiction.TokenWatchAndroid.auth.Pkce
import com.ScienceFiction.TokenWatchAndroid.auth.ProviderAuthRegistry
import com.ScienceFiction.TokenWatchAndroid.auth.device.CopilotDeviceFlow
import com.ScienceFiction.TokenWatchAndroid.auth.device.DeviceCode
import com.ScienceFiction.TokenWatchAndroid.auth.device.DeviceFlowException
import com.ScienceFiction.TokenWatchAndroid.auth.oauth.OAuthCallback
import com.ScienceFiction.TokenWatchAndroid.auth.oauth.OAuthException
import com.ScienceFiction.TokenWatchAndroid.domain.AgentProvider
import com.ScienceFiction.TokenWatchAndroid.domain.AuthKind
import com.ScienceFiction.TokenWatchAndroid.localization.L10n
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal sealed interface AddAgentPhase {
    data object PickProvider : AddAgentPhase
    data class OAuthLogin(val provider: AgentProvider, val pkce: Pkce) : AddAgentPhase
    data class ApiKey(val provider: AgentProvider) : AddAgentPhase
    data class DeviceFlow(val provider: AgentProvider, val device: DeviceCode? = null) : AddAgentPhase
    data class SessionLogin(val provider: AgentProvider) : AddAgentPhase
    data object Authenticating : AddAgentPhase
    data class Failed(val message: String) : AddAgentPhase
    data object Completed : AddAgentPhase
}

/** Activity-retained owner for login state and in-flight authentication work. */
internal class AddAgentFlowViewModel : ViewModel() {
    var phase: AddAgentPhase by mutableStateOf(AddAgentPhase.PickProvider)
        private set

    var apiKeyText: String by mutableStateOf("")
        private set

    private var flowJob: Job? = null

    fun selectProvider(
        provider: AgentProvider,
        providerAuth: ProviderAuthRegistry,
        deviceFlow: CopilotDeviceFlow,
        loc: L10n,
        onAddAgent: suspend (AgentProvider, OAuthTokens) -> Unit,
    ) {
        flowJob?.cancel()
        apiKeyText = ""
        phase = when (provider.authKind) {
            AuthKind.OAUTH_CODE -> AddAgentPhase.OAuthLogin(provider, Pkce.create())
            AuthKind.API_KEY -> AddAgentPhase.ApiKey(provider)
            AuthKind.SESSION_CAPTURE -> AddAgentPhase.SessionLogin(provider)
            AuthKind.OAUTH_DEVICE_FLOW -> AddAgentPhase.DeviceFlow(provider)
        }

        // Validate the session route before presenting a browser that cannot complete.
        if (
            phase is AddAgentPhase.SessionLogin &&
            providerAuth.sessionLoginUrl(provider) == null
        ) {
            phase = AddAgentPhase.Failed(loc.errAuthMethodUnavailable)
        } else if (phase is AddAgentPhase.DeviceFlow) {
            startDeviceFlow(provider, deviceFlow, loc, onAddAgent)
        }
    }

    fun updateApiKey(value: String) {
        if (phase is AddAgentPhase.ApiKey) apiKeyText = value
    }

    fun submitApiKey(
        providerAuth: ProviderAuthRegistry,
        loc: L10n,
        onAddAgent: suspend (AgentProvider, OAuthTokens) -> Unit,
    ) {
        val current = phase as? AddAgentPhase.ApiKey ?: return
        val key = apiKeyText.trim()
        if (key.isEmpty()) return
        val tokens = providerAuth.apiKeyCredential(current.provider, key)
        apiKeyText = ""
        addTokens(current.provider, tokens, loc, onAddAgent)
    }

    fun exchangeOAuth(
        callback: OAuthCallback,
        providerAuth: ProviderAuthRegistry,
        loc: L10n,
        onAddAgent: suspend (AgentProvider, OAuthTokens) -> Unit,
    ) {
        val current = phase as? AddAgentPhase.OAuthLogin ?: return
        phase = AddAgentPhase.Authenticating
        flowJob = viewModelScope.launch {
            try {
                val client = providerAuth.oauthClient(current.provider)
                val tokens = client.exchange(callback, current.pkce)
                onAddAgent(current.provider, tokens)
                phase = AddAgentPhase.Completed
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                phase = AddAgentPhase.Failed(authErrorMessage(error, loc))
            }
        }
    }

    fun acceptSession(
        provider: AgentProvider,
        tokens: OAuthTokens,
        loc: L10n,
        onAddAgent: suspend (AgentProvider, OAuthTokens) -> Unit,
    ) {
        if ((phase as? AddAgentPhase.SessionLogin)?.provider != provider) return
        addTokens(provider, tokens, loc, onAddAgent)
    }

    fun fail(message: String) {
        if (phase != AddAgentPhase.Completed) phase = AddAgentPhase.Failed(message)
    }

    fun retry() {
        flowJob?.cancel()
        flowJob = null
        apiKeyText = ""
        phase = AddAgentPhase.PickProvider
    }

    fun cancelAndReset() = retry()

    private fun addTokens(
        provider: AgentProvider,
        tokens: OAuthTokens,
        loc: L10n,
        onAddAgent: suspend (AgentProvider, OAuthTokens) -> Unit,
    ) {
        phase = AddAgentPhase.Authenticating
        flowJob = viewModelScope.launch {
            try {
                onAddAgent(provider, tokens)
                phase = AddAgentPhase.Completed
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                phase = AddAgentPhase.Failed(authErrorMessage(error, loc))
            }
        }
    }

    private fun startDeviceFlow(
        provider: AgentProvider,
        deviceFlow: CopilotDeviceFlow,
        loc: L10n,
        onAddAgent: suspend (AgentProvider, OAuthTokens) -> Unit,
    ) {
        flowJob = viewModelScope.launch {
            try {
                val device = deviceFlow.requestDeviceCode()
                phase = AddAgentPhase.DeviceFlow(provider, device)
                val tokens = deviceFlow.pollForToken(device)
                phase = AddAgentPhase.Authenticating
                onAddAgent(provider, tokens)
                phase = AddAgentPhase.Completed
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                phase = AddAgentPhase.Failed(authErrorMessage(error, loc))
            }
        }
    }
}

internal fun authErrorMessage(error: Throwable, loc: L10n): String = when (error) {
    is OAuthException.NotAuthenticated -> loc.errNotAuthenticated
    is OAuthException.ExchangeFailed -> loc.errTokenExchange(error.message.orEmpty().substringAfter(": "))
    is OAuthException.RefreshFailed -> loc.errTokenRefresh(error.message.orEmpty().substringAfter(": "))
    is OAuthException.StateMismatch -> loc.errTokenExchange(error.message.orEmpty())
    is DeviceFlowException.Expired -> loc.deviceFlowExpired
    is DeviceFlowException.Denied -> loc.deviceFlowDenied
    else -> error.localizedMessage?.takeIf(String::isNotBlank) ?: error::class.java.simpleName
}
