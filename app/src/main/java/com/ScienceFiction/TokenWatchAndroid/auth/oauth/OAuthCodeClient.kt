package com.ScienceFiction.TokenWatchAndroid.auth.oauth

import com.ScienceFiction.TokenWatchAndroid.auth.OAuthTokens
import com.ScienceFiction.TokenWatchAndroid.auth.Pkce

data class OAuthCallback(val code: String, val state: String)

sealed class OAuthException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class StateMismatch : OAuthException("OAuth callback state did not match the request")
    class ExchangeFailed(detail: String) : OAuthException("Token exchange failed: $detail")
    class RefreshFailed(detail: String) : OAuthException("Token refresh failed: $detail")
    class NotAuthenticated : OAuthException("Login required")
}

interface OAuthCodeClient {
    fun authorizeUrl(pkce: Pkce): String
    fun parseCallback(url: String): OAuthCallback?
    suspend fun exchange(callback: OAuthCallback, pkce: Pkce): OAuthTokens
    suspend fun refresh(tokens: OAuthTokens): OAuthTokens
}
