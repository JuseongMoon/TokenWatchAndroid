package com.ScienceFiction.TokenWatchAndroid.ui.add

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ScienceFiction.TokenWatchAndroid.analytics.LoginFailureCode
import com.ScienceFiction.TokenWatchAndroid.analytics.LoginStage
import com.ScienceFiction.TokenWatchAndroid.auth.OAuthTokens
import com.ScienceFiction.TokenWatchAndroid.auth.Pkce
import com.ScienceFiction.TokenWatchAndroid.auth.ProviderAuthRegistry
import com.ScienceFiction.TokenWatchAndroid.auth.device.DeviceFlowException
import com.ScienceFiction.TokenWatchAndroid.auth.oauth.ClaudeOAuthClient
import com.ScienceFiction.TokenWatchAndroid.auth.oauth.LoopbackCallbackServer
import com.ScienceFiction.TokenWatchAndroid.auth.oauth.OAuthCallback
import com.ScienceFiction.TokenWatchAndroid.auth.oauth.OAuthException
import com.ScienceFiction.TokenWatchAndroid.auth.oauth.isTransientNetworkError
import com.ScienceFiction.TokenWatchAndroid.domain.AgentProvider
import com.ScienceFiction.TokenWatchAndroid.domain.AuthKind
import com.ScienceFiction.TokenWatchAndroid.localization.L10n
import com.ScienceFiction.TokenWatchAndroid.network.core.UsageException
import com.ScienceFiction.TokenWatchAndroid.network.orchestration.UsageGateway
import com.ScienceFiction.TokenWatchAndroid.network.providers.apikey.KimiKeyException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Approval page details of a polling login, without its poller. */
internal data class PollingLoginInfo(val userCode: String?, val verificationUrl: String?)

internal sealed interface AddAgentPhase {
    data object PickProvider : AddAgentPhase

    /** `OAUTH_BROWSER`: in-app sign-in window with a loopback callback, or the pasted-code fallback. */
    data class BrowserLogin(
        val provider: AgentProvider,
        val pkce: Pkce,
        /** The sign-in window is open. */
        val isPresenting: Boolean = false,
        /** The user closed the window before approving (not an error; drives a hint). */
        val wasCancelled: Boolean = false,
        /** Showing the paste-the-code fallback instead of the sign-in buttons. */
        val manualEntry: Boolean = false,
        val inlineError: String? = null,
    ) : AddAgentPhase

    /** `OAUTH_CODE`: WebView login with an intercepted callback. */
    data class OAuthLogin(val provider: AgentProvider, val pkce: Pkce) : AddAgentPhase
    data class ApiKey(val provider: AgentProvider) : AddAgentPhase

    /** `OAUTH_DEVICE_FLOW`: approval page in an in-app tab while the app polls. */
    data class DeviceFlow(
        val provider: AgentProvider,
        val login: PollingLoginInfo? = null,
        /** A code-less flow opens its page by itself once, as soon as it is known. */
        val autoOpenPending: Boolean = false,
    ) : AddAgentPhase

    data object Authenticating : AddAgentPhase

    /**
     * [stage] and [code] feed the login_fail analytics event; the message never does. [canRetryExchange]
     * means the received code was kept after a network failure, so RETRY only repeats the exchange.
     */
    data class Failed(
        val message: String,
        val stage: LoginStage? = null,
        val code: String? = null,
        val canRetryExchange: Boolean = false,
    ) : AddAgentPhase

    data object Completed : AddAgentPhase
}

/** Activity-retained owner for login state and in-flight authentication work. */
internal class AddAgentFlowViewModel : ViewModel() {
    var phase: AddAgentPhase by mutableStateOf(AddAgentPhase.PickProvider)
        private set

    var apiKeyText: String by mutableStateOf("")
        private set

    /**
     * A sign-in or approval tab was opened on top of the app. When the login then completes while
     * the app is still covered, the screen brings the app back (the tab cannot be closed from here).
     */
    var loginWindowOpen: Boolean by mutableStateOf(false)
        private set

    private var flowJob: Job? = null

    /** Code kept after an exchange failed before reaching the server; RETRY exchanges it again. */
    private var pendingExchange: PendingExchange? = null

    private class PendingExchange(
        val provider: AgentProvider,
        val pkce: Pkce,
        val code: String,
        val state: String,
        val redirect: String,
    )

    private var loopback: LoopbackCallbackServer? = null
    private var browserRedirect: String? = null

    /** Incremented per sign-in attempt so a late callback of an earlier attempt is dropped. */
    private var browserAttempt = 0

    /** A code for the current attempt was accepted; later deliveries are ignored. */
    private var browserFinished = true

    fun selectProvider(
        provider: AgentProvider,
        providerAuth: ProviderAuthRegistry,
        loc: L10n,
        onAddAgent: suspend (AgentProvider, OAuthTokens) -> Unit,
    ) {
        resetFlow()
        phase = when (provider.authKind) {
            AuthKind.OAUTH_BROWSER -> AddAgentPhase.BrowserLogin(provider, Pkce.create())
            AuthKind.OAUTH_CODE -> AddAgentPhase.OAuthLogin(provider, Pkce.create())
            AuthKind.API_KEY -> AddAgentPhase.ApiKey(provider)
            AuthKind.OAUTH_DEVICE_FLOW -> AddAgentPhase.DeviceFlow(provider)
        }
        if (provider.authKind == AuthKind.OAUTH_DEVICE_FLOW) {
            startPollingLogin(provider, providerAuth, loc, onAddAgent)
        }
    }

    // region Sign-in window (OAUTH_BROWSER)

    /**
     * Starts the loopback listener for a new attempt and returns the authorize URL the screen should
     * open, or null when nothing should open (the phase then explains why).
     */
    suspend fun beginBrowserLogin(
        providerAuth: ProviderAuthRegistry,
        loc: L10n,
        onAddAgent: suspend (AgentProvider, OAuthTokens) -> Unit,
    ): String? {
        val current = phase as? AddAgentPhase.BrowserLogin ?: return null
        if (current.isPresenting) return null
        stopBrowserAttempt()
        val attempt = browserAttempt
        browserFinished = false
        val client = providerAuth.browserClient(current.provider)

        val server = LoopbackCallbackServer(current.pkce.state, viewModelScope) { code, state ->
            viewModelScope.launch { receiveLoopbackCode(attempt, code, state, providerAuth, loc, onAddAgent) }
        }
        val port = try {
            server.start()
        } catch (error: IOException) {
            server.stop()
            if (attempt == browserAttempt) failBrowserSession(current.provider, providerAuth, loc, "loopback_listen")
            return null
        }
        // Dismissed or restarted while the listener was coming up: drop this attempt.
        val latest = phase as? AddAgentPhase.BrowserLogin
        if (attempt != browserAttempt || latest == null || browserFinished) {
            server.stop()
            return null
        }
        loopback = server
        val redirect = client.loopbackRedirectUri(port)
        browserRedirect = redirect
        loginWindowOpen = true
        phase = latest.copy(isPresenting = true, wasCancelled = false, inlineError = null)
        return client.authorizeUrl(current.pkce, redirect)
    }

    /** The sign-in window could not be opened at all (no browser). */
    fun browserLaunchFailed(providerAuth: ProviderAuthRegistry, loc: L10n) {
        val current = phase as? AddAgentPhase.BrowserLogin ?: return
        loginWindowOpen = false
        failBrowserSession(current.provider, providerAuth, loc, "auth_tab_start")
    }

    /**
     * The sign-in window reported back: the loopback's redirect URL, a close, or a failure. The
     * listener normally delivers the same code first, in which case this is a no-op.
     */
    fun onBrowserWindowResult(
        resultCode: Int,
        resultUri: String?,
        providerAuth: ProviderAuthRegistry,
        loc: L10n,
        onAddAgent: suspend (AgentProvider, OAuthTokens) -> Unit,
    ) {
        val current = phase as? AddAgentPhase.BrowserLogin ?: return
        if (browserFinished || !current.isPresenting) return
        val callback = resultUri?.let(LoopbackCallbackServer::parseSessionCallback)
        val redirect = browserRedirect
        when {
            callback != null && redirect != null -> {
                browserFinished = true
                stopLoopback()
                exchangeBrowser(current.provider, current.pkce, callback, redirect, providerAuth, loc, onAddAgent)
            }
            // Closed without approving. The listener stays up until the next attempt or dismissal,
            // which also covers browsers that return a result immediately while still signing in.
            resultCode == RESULT_CANCELED -> phase = current.copy(isPresenting = false, wasCancelled = true)
            else -> failBrowserSession(current.provider, providerAuth, loc, "auth_tab_$resultCode")
        }
    }

    fun showManualEntry() {
        val current = phase as? AddAgentPhase.BrowserLogin ?: return
        phase = current.copy(manualEntry = true, inlineError = null)
    }

    /** Authorize URL ending on the console code page (same PKCE), for the paste-the-code fallback. */
    fun manualAuthorizeUrl(providerAuth: ProviderAuthRegistry): String? {
        val current = phase as? AddAgentPhase.BrowserLogin ?: return null
        val redirect = providerAuth.manualCodeRedirect(current.provider) ?: return null
        return providerAuth.browserClient(current.provider).authorizeUrl(current.pkce, redirect)
    }

    fun submitManualCode(
        text: String,
        providerAuth: ProviderAuthRegistry,
        loc: L10n,
        onAddAgent: suspend (AgentProvider, OAuthTokens) -> Unit,
    ) {
        val current = phase as? AddAgentPhase.BrowserLogin ?: return
        val redirect = providerAuth.manualCodeRedirect(current.provider) ?: return
        val parsed = ClaudeOAuthClient.parseManualCode(text, fallbackState = current.pkce.state)
        if (parsed == null) {
            phase = current.copy(inlineError = loc.errCodeInvalid)
            return
        }
        browserFinished = true
        stopLoopback()
        // Issued for the console code page, so it is exchanged with that redirect.
        exchangeBrowser(current.provider, current.pkce, parsed, redirect, providerAuth, loc, onAddAgent)
    }

    private fun receiveLoopbackCode(
        attempt: Int,
        code: String,
        state: String,
        providerAuth: ProviderAuthRegistry,
        loc: L10n,
        onAddAgent: suspend (AgentProvider, OAuthTokens) -> Unit,
    ) {
        val current = phase as? AddAgentPhase.BrowserLogin ?: return
        val redirect = browserRedirect ?: return
        if (attempt != browserAttempt || browserFinished) return
        browserFinished = true
        // The listener folds itself once its response is out; stopping it here could cut the 302.
        loopback = null
        exchangeBrowser(current.provider, current.pkce, OAuthCallback(code, state), redirect, providerAuth, loc, onAddAgent)
    }

    private fun exchangeBrowser(
        provider: AgentProvider,
        pkce: Pkce,
        callback: OAuthCallback,
        redirect: String,
        providerAuth: ProviderAuthRegistry,
        loc: L10n,
        onAddAgent: suspend (AgentProvider, OAuthTokens) -> Unit,
    ) {
        // CSRF defence: a callback must carry the state this login created.
        if (callback.state != pkce.state) {
            pendingExchange = null
            phase = AddAgentPhase.Failed(loc.errStateMismatch, LoginStage.STATE_MISMATCH, "state_mismatch")
            return
        }
        phase = AddAgentPhase.Authenticating
        flowJob = viewModelScope.launch {
            val tokens = try {
                providerAuth.browserClient(provider).exchange(callback.code, callback.state, pkce, redirect)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                // A request that never reached the server leaves the code valid; keep it for RETRY.
                pendingExchange = if (isTransientNetworkError(error)) {
                    PendingExchange(provider, pkce, callback.code, callback.state, redirect)
                } else {
                    null
                }
                phase = AddAgentPhase.Failed(
                    message = authErrorMessage(error, loc),
                    stage = LoginStage.EXCHANGE,
                    code = LoginFailureCode.from(error),
                    canRetryExchange = pendingExchange != null,
                )
                return@launch
            }
            pendingExchange = null
            addTokens(provider, tokens, loc, onAddAgent)
        }
    }

    private fun failBrowserSession(
        provider: AgentProvider,
        providerAuth: ProviderAuthRegistry,
        loc: L10n,
        code: String,
    ) {
        stopBrowserAttempt()
        val manualFallback = providerAuth.manualCodeRedirect(provider) != null
        phase = AddAgentPhase.Failed(loc.browserSessionFailed(manualFallback), LoginStage.BROWSER_WAIT, code)
    }

    private fun stopLoopback() {
        loopback?.stop()
        loopback = null
    }

    /** Ends the current sign-in attempt: its listener stops and any late result is ignored. */
    private fun stopBrowserAttempt() {
        browserAttempt += 1
        browserFinished = true
        browserRedirect = null
        stopLoopback()
    }

    // endregion

    // region API key

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
        apiKeyText = ""
        phase = AddAgentPhase.Authenticating
        flowJob = viewModelScope.launch {
            val tokens = try {
                // Kimi checks the key against both regional hosts before a card exists.
                providerAuth.apiKeyCredential(current.provider, key)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                phase = AddAgentPhase.Failed(
                    authErrorMessage(error, loc),
                    LoginStage.API_KEY_ENTRY,
                    LoginFailureCode.from(error),
                )
                return@launch
            }
            addTokens(current.provider, tokens, loc, onAddAgent)
        }
    }

    // endregion

    // region WebView login (OAUTH_CODE)

    fun exchangeOAuth(
        callback: OAuthCallback,
        providerAuth: ProviderAuthRegistry,
        loc: L10n,
        onAddAgent: suspend (AgentProvider, OAuthTokens) -> Unit,
    ) {
        val current = phase as? AddAgentPhase.OAuthLogin ?: return
        phase = AddAgentPhase.Authenticating
        flowJob = viewModelScope.launch {
            val tokens = try {
                providerAuth.oauthClient(current.provider).exchange(callback, current.pkce)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                phase = AddAgentPhase.Failed(
                    authErrorMessage(error, loc),
                    LoginStage.EXCHANGE,
                    LoginFailureCode.from(error),
                )
                return@launch
            }
            addTokens(current.provider, tokens, loc, onAddAgent)
        }
    }

    // endregion

    // region Polling login (OAUTH_DEVICE_FLOW)

    private fun startPollingLogin(
        provider: AgentProvider,
        providerAuth: ProviderAuthRegistry,
        loc: L10n,
        onAddAgent: suspend (AgentProvider, OAuthTokens) -> Unit,
    ) {
        flowJob = viewModelScope.launch {
            val tokens = try {
                val login = providerAuth.startPollingLogin(provider)
                phase = AddAgentPhase.DeviceFlow(
                    provider = provider,
                    login = PollingLoginInfo(login.userCode, login.verificationUrl),
                    // Without a code to read first, the approval page opens right away.
                    autoOpenPending = login.userCode == null && login.verificationUrl != null,
                )
                login.poll()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                phase = AddAgentPhase.Failed(
                    authErrorMessage(error, loc),
                    LoginStage.DEVICE_POLL,
                    LoginFailureCode.from(error),
                )
                return@launch
            }
            addTokens(provider, tokens, loc, onAddAgent)
        }
    }

    fun consumeAutoOpen() {
        val current = phase as? AddAgentPhase.DeviceFlow ?: return
        if (current.autoOpenPending) phase = current.copy(autoOpenPending = false)
    }

    // endregion

    fun markLoginWindowOpened() {
        loginWindowOpen = true
    }

    /** The app is in front again, so no tab covers it any more. */
    fun onAppResumed() {
        loginWindowOpen = false
    }

    fun fail(message: String) {
        if (phase != AddAgentPhase.Completed) phase = AddAgentPhase.Failed(message)
    }

    /**
     * RETRY: with a kept code, repeat only the exchange rather than the whole sign-in; otherwise go
     * back to the provider list.
     */
    fun retry(
        providerAuth: ProviderAuthRegistry,
        loc: L10n,
        onAddAgent: suspend (AgentProvider, OAuthTokens) -> Unit,
    ) {
        val pending = pendingExchange
        if (pending != null && phase is AddAgentPhase.Failed) {
            exchangeBrowser(
                pending.provider,
                pending.pkce,
                OAuthCallback(pending.code, pending.state),
                pending.redirect,
                providerAuth,
                loc,
                onAddAgent,
            )
            return
        }
        resetFlow()
        phase = AddAgentPhase.PickProvider
    }

    fun cancelAndReset() {
        resetFlow()
        phase = AddAgentPhase.PickProvider
    }

    override fun onCleared() {
        stopBrowserAttempt()
        super.onCleared()
    }

    private fun resetFlow() {
        flowJob?.cancel()
        flowJob = null
        pendingExchange = null
        apiKeyText = ""
        loginWindowOpen = false
        stopBrowserAttempt()
    }

    private suspend fun addTokens(
        provider: AgentProvider,
        tokens: OAuthTokens,
        loc: L10n,
        onAddAgent: suspend (AgentProvider, OAuthTokens) -> Unit,
    ) {
        phase = AddAgentPhase.Authenticating
        try {
            onAddAgent(provider, tokens)
            phase = AddAgentPhase.Completed
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            phase = AddAgentPhase.Failed(authErrorMessage(error, loc), LoginStage.KEYSTORE, "keystore_save")
        }
    }

    private companion object {
        /** `Activity.RESULT_CANCELED`, which Auth Tab reports for a window closed without result. */
        const val RESULT_CANCELED = 0
    }
}

internal fun authErrorMessage(error: Throwable, loc: L10n): String = when (error) {
    is OAuthException.NotAuthenticated -> loc.errNotAuthenticated
    is OAuthException.ExchangeFailed -> loc.errTokenExchange(error.detail)
    is OAuthException.RefreshFailed -> loc.errTokenRefresh(error.detail)
    is OAuthException.StateMismatch -> loc.errStateMismatch
    is OAuthException.CodeExpired -> loc.errTokenExchange(loc.errCodeExpired)
    is DeviceFlowException.Expired -> loc.deviceFlowExpired
    is DeviceFlowException.Denied -> loc.deviceFlowDenied
    is DeviceFlowException.TimedOut -> loc.pollingLoginTimedOut
    is KimiKeyException -> loc.errInvalidApiKey
    is UsageException -> UsageGateway.localizedMessage(error, loc)
    else -> error.localizedMessage?.takeIf(String::isNotBlank) ?: error::class.java.simpleName
}
