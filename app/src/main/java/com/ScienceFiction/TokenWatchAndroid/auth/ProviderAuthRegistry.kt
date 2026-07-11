package com.ScienceFiction.TokenWatchAndroid.auth

import com.ScienceFiction.TokenWatchAndroid.auth.oauth.OAuthCodeClient
import com.ScienceFiction.TokenWatchAndroid.auth.session.BrowserCookie
import com.ScienceFiction.TokenWatchAndroid.auth.session.CursorSessionAuth
import com.ScienceFiction.TokenWatchAndroid.auth.session.GrokSessionAuth
import com.ScienceFiction.TokenWatchAndroid.auth.session.WindsurfSessionAuth
import com.ScienceFiction.TokenWatchAndroid.domain.AgentProvider
import com.ScienceFiction.TokenWatchAndroid.domain.AuthKind

enum class SessionCaptureMode {
    COOKIE,
    LOCAL_STORAGE,
}

/** Authentication dispatch equivalent to the clean iOS `ProviderAuth` entry point. */
class ProviderAuthRegistry(
    private val claudeOAuth: OAuthCodeClient,
    private val codexOAuth: OAuthCodeClient,
) {
    fun oauthClient(provider: AgentProvider): OAuthCodeClient {
        require(provider.authKind == AuthKind.OAUTH_CODE) {
            "OAuth code flow is unavailable for ${provider.wireId}"
        }
        return when (provider) {
            AgentProvider.CLAUDE -> claudeOAuth
            AgentProvider.CODEX -> codexOAuth
            else -> error("OAuth registry mismatch for ${provider.wireId}")
        }
    }

    fun apiKeyCredential(provider: AgentProvider, apiKey: String): OAuthTokens {
        require(provider.authKind == AuthKind.API_KEY) {
            "API-key flow is unavailable for ${provider.wireId}"
        }
        return OAuthTokens.apiKey(apiKey)
    }

    fun sessionCaptureMode(provider: AgentProvider): SessionCaptureMode {
        require(provider.authKind == AuthKind.SESSION_CAPTURE) {
            "Session capture is unavailable for ${provider.wireId}"
        }
        return if (provider == AgentProvider.WINDSURF) {
            SessionCaptureMode.LOCAL_STORAGE
        } else {
            SessionCaptureMode.COOKIE
        }
    }

    fun sessionLoginUrl(provider: AgentProvider): String? = when (provider) {
        AgentProvider.CURSOR -> CursorSessionAuth.loginUrl
        AgentProvider.GROK -> GrokSessionAuth.loginUrl
        AgentProvider.WINDSURF -> WindsurfSessionAuth.loginUrl
        else -> null
    }

    fun sessionProbe(provider: AgentProvider, cookies: List<BrowserCookie>): OAuthTokens? =
        when (provider) {
            AgentProvider.CURSOR -> CursorSessionAuth.probe(cookies)
            AgentProvider.GROK -> GrokSessionAuth.probe(cookies)
            else -> null
        }

    fun localStorageProbe(provider: AgentProvider, values: Map<String, String>): OAuthTokens? =
        when (provider) {
            AgentProvider.WINDSURF -> WindsurfSessionAuth.probe(values)
            else -> null
        }
}
