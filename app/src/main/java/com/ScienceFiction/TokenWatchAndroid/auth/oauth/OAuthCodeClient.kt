package com.ScienceFiction.TokenWatchAndroid.auth.oauth

import com.ScienceFiction.TokenWatchAndroid.auth.OAuthTokens
import com.ScienceFiction.TokenWatchAndroid.auth.Pkce
import java.io.IOException
import java.net.URLEncoder
import kotlinx.coroutines.delay

data class OAuthCallback(val code: String, val state: String)

sealed class OAuthException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class StateMismatch : OAuthException("OAuth callback state did not match the request")
    class ExchangeFailed(val detail: String) : OAuthException("Token exchange failed: $detail")
    class RefreshFailed(val detail: String) : OAuthException("Token refresh failed: $detail")
    class RefreshRevoked : OAuthException("Authentication expired. Please log in again.")
    class NotAuthenticated : OAuthException("Login required")

    /**
     * `invalid_grant` while exchanging a code: the code expired or was already used. Kept apart from
     * [RefreshRevoked] so the paste-the-code flow does not tell the user to "log in again" when
     * what actually failed is the code they pasted.
     */
    class CodeExpired : OAuthException("The code expired or was already used")
}

/** OAuth code flow completed inside a WebView whose callback redirect is intercepted (Codex). */
interface OAuthCodeClient {
    fun authorizeUrl(pkce: Pkce): String
    fun parseCallback(url: String): OAuthCallback?
    suspend fun exchange(callback: OAuthCallback, pkce: Pkce): OAuthTokens
    suspend fun refresh(tokens: OAuthTokens): OAuthTokens
}

/**
 * OAuth code flow completed in the in-app sign-in window: the authorization server redirects to a
 * loopback address the app is listening on (Claude, Grok). Matches iOS `ProviderAuth`'s
 * `loopbackRedirectURI` / `authorizeURL(redirect:)` / `manualCodeRedirect` / `exchange(redirect:)`.
 */
interface BrowserOAuthClient {
    fun loopbackRedirectUri(port: Int): String
    fun authorizeUrl(pkce: Pkce, redirect: String): String

    /** Redirect of the paste-the-code fallback (a console code page), or null when there is none. */
    val manualCodeRedirect: String?

    /** [redirect] must be the exact value used for [authorizeUrl]. */
    suspend fun exchange(code: String, state: String, pkce: Pkce, redirect: String): OAuthTokens
    suspend fun refresh(tokens: OAuthTokens): OAuthTokens
}

/**
 * A network failure before any response arrived. For a code exchange the code is then still
 * unused, which is what makes a retry — automatic or the failure screen's RETRY — safe.
 */
fun isTransientNetworkError(error: Throwable): Boolean = error is IOException

/**
 * Exchange-only: the first request after returning from the sign-in window often dies on a socket
 * the system closed while the app was in the background. Such a failure never reached the server,
 * so the code is still valid and one more attempt is safe. Never used for refresh: a refresh that
 * did reach the server has already rotated the token, and resending it would kill the credential.
 */
internal suspend fun <T> retryingTransientFailureOnce(block: suspend () -> T): T = try {
    block()
} catch (error: IOException) {
    delay(TRANSIENT_RETRY_DELAY_MILLIS)
    block()
}

private const val TRANSIENT_RETRY_DELAY_MILLIS = 1_000L

/**
 * Percent-encodes everything except `A-Za-z0-9-._~`. Lenient encoders keep `+`, which a form or
 * query decoder reads back as a space and so silently changes a token or code.
 */
internal fun percentEncodeStrict(text: String): String =
    URLEncoder.encode(text, Charsets.UTF_8.name())
        .replace("+", "%20")
        .replace("*", "%2A")
        .replace("%7E", "~")
