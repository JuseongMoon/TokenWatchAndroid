package com.ScienceFiction.TokenWatchAndroid.auth

import com.ScienceFiction.TokenWatchAndroid.auth.device.CopilotDeviceFlow
import com.ScienceFiction.TokenWatchAndroid.auth.device.CursorAuth
import com.ScienceFiction.TokenWatchAndroid.auth.oauth.BrowserOAuthClient
import com.ScienceFiction.TokenWatchAndroid.auth.oauth.OAuthCodeClient
import com.ScienceFiction.TokenWatchAndroid.domain.AgentProvider
import com.ScienceFiction.TokenWatchAndroid.domain.AuthKind
import com.ScienceFiction.TokenWatchAndroid.network.providers.apikey.KimiUsageClient

/**
 * One polling login: the approval page opened in an in-app tab, and a poller that waits until it is
 * approved. Copilot shows a code to enter; a page-approval-only flow (Cursor) has none.
 */
class PollingLogin(
    /** Code to enter on the approval page; null when approving the page is enough. */
    val userCode: String?,
    val verificationUrl: String?,
    /** Suspends until approved. Throws on expiry or refusal; cancellation ends it quietly. */
    val poll: suspend () -> OAuthTokens,
)

/**
 * Authentication dispatch for the supported provider catalog, mirroring iOS `ProviderAuth`.
 *
 * The `when` expressions list every provider explicitly instead of using `else`: adding a provider
 * and forgetting it here must be a compile error, not a silent "login required" at runtime.
 */
class ProviderAuthRegistry(
    private val claudeOAuth: BrowserOAuthClient,
    private val codexOAuth: OAuthCodeClient,
    private val grokOAuth: BrowserOAuthClient,
    private val copilotDeviceFlow: CopilotDeviceFlow,
    private val cursorAuth: CursorAuth,
    private val kimi: KimiUsageClient,
) {
    /** WebView login with an intercepted callback (`OAUTH_CODE`). */
    fun oauthClient(provider: AgentProvider): OAuthCodeClient {
        require(provider.authKind == AuthKind.OAUTH_CODE) {
            "OAuth code flow is unavailable for ${provider.wireId}"
        }
        return when (provider) {
            AgentProvider.CODEX -> codexOAuth
            AgentProvider.CLAUDE, AgentProvider.COPILOT, AgentProvider.GROK, AgentProvider.CURSOR,
            AgentProvider.KIMI, AgentProvider.OPENROUTER, AgentProvider.DEEPSEEK, AgentProvider.POE,
            AgentProvider.ELEVENLABS,
            -> error("OAuth registry mismatch for ${provider.wireId}")
        }
    }

    /** In-app sign-in window with a loopback callback (`OAUTH_BROWSER`). */
    fun browserClient(provider: AgentProvider): BrowserOAuthClient {
        require(provider.authKind == AuthKind.OAUTH_BROWSER) {
            "Browser sign-in is unavailable for ${provider.wireId}"
        }
        return when (provider) {
            AgentProvider.CLAUDE -> claudeOAuth
            AgentProvider.GROK -> grokOAuth
            AgentProvider.CODEX, AgentProvider.COPILOT, AgentProvider.CURSOR, AgentProvider.KIMI,
            AgentProvider.OPENROUTER, AgentProvider.DEEPSEEK, AgentProvider.POE, AgentProvider.ELEVENLABS,
            -> error("Browser sign-in registry mismatch for ${provider.wireId}")
        }
    }

    /**
     * Redirect of the paste-the-code fallback. Null for providers without one, and the sign-in
     * screen then hides the manual entry.
     */
    fun manualCodeRedirect(provider: AgentProvider): String? =
        if (provider.authKind == AuthKind.OAUTH_BROWSER) browserClient(provider).manualCodeRedirect else null

    /**
     * Wraps a pasted key as a stored credential. Most keys are validated by the first usage fetch;
     * Kimi first finds the regional host the key works on and throws when both reject it, so no
     * card is created for a bad key.
     */
    suspend fun apiKeyCredential(provider: AgentProvider, apiKey: String): OAuthTokens {
        require(provider.authKind == AuthKind.API_KEY) {
            "API-key flow is unavailable for ${provider.wireId}"
        }
        return when (provider) {
            AgentProvider.KIMI -> kimi.prepareCredential(apiKey)
            AgentProvider.OPENROUTER, AgentProvider.DEEPSEEK, AgentProvider.POE, AgentProvider.ELEVENLABS ->
                OAuthTokens.apiKey(apiKey)
            AgentProvider.CLAUDE, AgentProvider.CODEX, AgentProvider.COPILOT, AgentProvider.GROK,
            AgentProvider.CURSOR,
            -> error("API-key registry mismatch for ${provider.wireId}")
        }
    }

    /** Starts a polling login (`OAUTH_DEVICE_FLOW`): the approval page and its poller. */
    suspend fun startPollingLogin(provider: AgentProvider): PollingLogin {
        require(provider.authKind == AuthKind.OAUTH_DEVICE_FLOW) {
            "Polling login is unavailable for ${provider.wireId}"
        }
        return when (provider) {
            AgentProvider.COPILOT -> {
                val device = copilotDeviceFlow.requestDeviceCode()
                PollingLogin(device.userCode, device.verificationUri) { copilotDeviceFlow.pollForToken(device) }
            }
            AgentProvider.CURSOR -> {
                val handshake = CursorAuth.makeHandshake()
                PollingLogin(userCode = null, verificationUrl = handshake.loginUrl) {
                    cursorAuth.completeLogin(handshake)
                }
            }
            AgentProvider.CLAUDE, AgentProvider.CODEX, AgentProvider.GROK, AgentProvider.KIMI,
            AgentProvider.OPENROUTER, AgentProvider.DEEPSEEK, AgentProvider.POE, AgentProvider.ELEVENLABS,
            -> error("Polling login registry mismatch for ${provider.wireId}")
        }
    }
}
