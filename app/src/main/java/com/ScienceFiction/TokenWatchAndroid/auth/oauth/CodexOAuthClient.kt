package com.ScienceFiction.TokenWatchAndroid.auth.oauth

import com.ScienceFiction.TokenWatchAndroid.auth.Jwt
import com.ScienceFiction.TokenWatchAndroid.auth.OAuthTokens
import com.ScienceFiction.TokenWatchAndroid.auth.Pkce
import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkTransport
import java.time.Instant
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Codex (OpenAI) OAuth with PKCE, using the official Codex CLI's public client and authorize
 * parameters. Sign-in runs in the in-app sign-in window with a loopback redirect, like Claude and
 * Grok: an embedded WebView was blocked for many users abroad (Cloudflare's browser check, identity
 * providers that refuse embedded browsers).
 *
 * The client is registered on one exact redirect, so the listener must take port [REDIRECT_PORT] on
 * [CALLBACK_PATH], and authorize and exchange both send [REDIRECT_URI] unchanged. There is no
 * paste-the-code fallback: the code only ever arrives at that address. Stored credentials and their
 * refresh are unchanged by the move from the WebView.
 */
class CodexOAuthClient(
    private val transport: NetworkTransport,
    private val now: () -> Instant = Instant::now,
    private val tokenUrl: String = TOKEN_URL,
) : BrowserOAuthClient {
    override val loopbackPort: Int = REDIRECT_PORT
    override val loopbackCallbackPath: String = CALLBACK_PATH

    override fun loopbackRedirectUri(port: Int): String = "http://localhost:$port$CALLBACK_PATH"

    override fun authorizeUrl(pkce: Pkce, redirect: String): String = AUTHORIZE_URL.toHttpUrl().newBuilder()
        .addQueryParameter("response_type", "code")
        .addQueryParameter("client_id", CLIENT_ID)
        .addQueryParameter("redirect_uri", redirect)
        .addQueryParameter("scope", SCOPES.joinToString(" "))
        .addQueryParameter("code_challenge", pkce.challenge)
        .addQueryParameter("code_challenge_method", "S256")
        .addQueryParameter("id_token_add_organizations", "true")
        .addQueryParameter("codex_cli_simplified_flow", "true")
        .addQueryParameter("state", pkce.state)
        .build()
        .toString()

    override val manualCodeRedirect: String? = null

    override suspend fun exchange(code: String, state: String, pkce: Pkce, redirect: String): OAuthTokens {
        if (state != pkce.state) throw OAuthException.StateMismatch()
        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", redirect)
            .add("client_id", CLIENT_ID)
            .add("code_verifier", pkce.verifier)
            .build()
        return retryingTransientFailureOnce { postToken(body, previous = null, exchange = true) }
    }

    override suspend fun refresh(tokens: OAuthTokens): OAuthTokens {
        val refreshToken = tokens.refreshToken ?: throw OAuthException.NotAuthenticated()
        val json = JsonMap.encode(
            mapOf(
                "client_id" to CLIENT_ID,
                "grant_type" to "refresh_token",
                "refresh_token" to refreshToken,
                "scope" to "openid profile email",
            ),
        )
        return postToken(
            body = json.toRequestBody(JSON_MEDIA_TYPE),
            previous = tokens,
            exchange = false,
        )
    }

    private suspend fun postToken(
        body: RequestBody,
        previous: OAuthTokens?,
        exchange: Boolean,
    ): OAuthTokens {
        val response = transport.execute(
            Request.Builder()
                .url(tokenUrl)
                .post(body)
                .header("Accept", "application/json")
                .build(),
        )
        if (response.statusCode !in 200..299) {
            val responseBody = response.bodyText()
            val detail = "HTTP ${response.statusCode}: $responseBody"
            if (exchange) throw OAuthException.ExchangeFailed(detail)
            if (isPermanentRefreshFailure(response.statusCode, responseBody)) {
                throw OAuthException.RefreshRevoked()
            }
            throw OAuthException.RefreshFailed(detail)
        }
        val json = JsonMap.decode(response.bodyText())
            ?: throw stageError(exchange, "Invalid JSON response")
        val accessToken = json.string("access_token")
            ?: throw stageError(exchange, "Missing access_token")
        val idToken = json.string("id_token") ?: previous?.idToken
        val expiresIn = json.number("expires_in")?.toLong()
        return OAuthTokens(
            accessToken = accessToken,
            refreshToken = json.string("refresh_token") ?: previous?.refreshToken,
            expiresAt = expiresIn?.let { now().plusSeconds(it) },
            scopes = SCOPES,
            accountEmail = idToken?.let(Jwt::email) ?: previous?.accountEmail,
            plan = idToken?.let(Jwt::plan) ?: previous?.plan,
            idToken = idToken,
            accountId = idToken?.let(Jwt::accountId) ?: previous?.accountId,
        )
    }

    private fun stageError(exchange: Boolean, detail: String): OAuthException =
        if (exchange) OAuthException.ExchangeFailed(detail) else OAuthException.RefreshFailed(detail)

    companion object {
        /** Codes that are unrecoverable whatever the HTTP status (official Codex CLI classifier). */
        private val PERMANENT_REFRESH_CODES = setOf(
            "refresh_token_expired",
            "refresh_token_reused",
            "refresh_token_invalidated",
        )

        /**
         * Mirrors the official Codex CLI (`codex-rs/login` `classify_refresh_token_failure` and its
         * caller): a refresh is permanently dead on any 401, on one of [PERMANENT_REFRESH_CODES]
         * (case-insensitive, any status), or on 400 with `invalid_grant`. Everything else — 429,
         * 5xx, other 4xx codes, unreadable bodies — is transient and keeps the credential.
         */
        internal fun isPermanentRefreshFailure(statusCode: Int, body: String): Boolean {
            if (statusCode == 401) return true
            val code = refreshErrorCode(body)?.lowercase() ?: return false
            if (code in PERMANENT_REFRESH_CODES) return true
            return statusCode == 400 && code == "invalid_grant"
        }

        /** Reads `error` (string), `error.code`, or a top-level `code` from a token error body. */
        internal fun refreshErrorCode(body: String): String? {
            val json = JsonMap.decode(body) ?: return null
            return json.string("error")
                ?: json.map("error")?.string("code")
                ?: json.string("code")
        }

        const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
        const val AUTHORIZE_URL = "https://auth.openai.com/oauth/authorize"
        const val TOKEN_URL = "https://auth.openai.com/oauth/token"
        /** The registered redirect: [loopbackRedirectUri] of [REDIRECT_PORT]. */
        const val REDIRECT_URI = "http://localhost:1455/auth/callback"
        const val REDIRECT_PORT = 1455
        const val CALLBACK_PATH = "/auth/callback"
        val SCOPES = listOf("openid", "profile", "email", "offline_access")
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
