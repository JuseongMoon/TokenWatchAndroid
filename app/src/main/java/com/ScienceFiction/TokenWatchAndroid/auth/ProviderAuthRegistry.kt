package com.ScienceFiction.TokenWatchAndroid.auth

import com.ScienceFiction.TokenWatchAndroid.auth.oauth.OAuthCodeClient
import com.ScienceFiction.TokenWatchAndroid.domain.AgentProvider
import com.ScienceFiction.TokenWatchAndroid.domain.AuthKind

/** Authentication dispatch for the supported provider catalog. */
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
}
