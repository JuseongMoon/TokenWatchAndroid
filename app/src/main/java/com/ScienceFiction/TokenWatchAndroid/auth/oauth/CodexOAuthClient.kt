package com.ScienceFiction.TokenWatchAndroid.auth.oauth

import com.ScienceFiction.TokenWatchAndroid.auth.Jwt
import com.ScienceFiction.TokenWatchAndroid.auth.OAuthTokens
import com.ScienceFiction.TokenWatchAndroid.auth.Pkce
import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkTransport
import java.time.Instant
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

class CodexOAuthClient(
    private val transport: NetworkTransport,
    private val now: () -> Instant = Instant::now,
    private val tokenUrl: String = TOKEN_URL,
) : OAuthCodeClient {
    override fun authorizeUrl(pkce: Pkce): String = AUTHORIZE_URL.toHttpUrl().newBuilder()
        .addQueryParameter("response_type", "code")
        .addQueryParameter("client_id", CLIENT_ID)
        .addQueryParameter("redirect_uri", REDIRECT_URI)
        .addQueryParameter("scope", SCOPES.joinToString(" "))
        .addQueryParameter("code_challenge", pkce.challenge)
        .addQueryParameter("code_challenge_method", "S256")
        .addQueryParameter("id_token_add_organizations", "true")
        .addQueryParameter("codex_cli_simplified_flow", "true")
        .addQueryParameter("state", pkce.state)
        .build()
        .toString()

    override fun parseCallback(url: String): OAuthCallback? {
        if (!url.startsWith(CALLBACK_PREFIX)) return null
        val parsed = url.toHttpUrlOrNull() ?: return null
        val code = parsed.queryParameter("code") ?: return null
        return OAuthCallback(code, parsed.queryParameter("state").orEmpty())
    }

    override suspend fun exchange(callback: OAuthCallback, pkce: Pkce): OAuthTokens {
        if (callback.state != pkce.state) throw OAuthException.StateMismatch()
        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", callback.code)
            .add("redirect_uri", REDIRECT_URI)
            .add("client_id", CLIENT_ID)
            .add("code_verifier", pkce.verifier)
            .build()
        return postToken(body, previous = null, exchange = true)
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
            if (response.statusCode in listOf(400, 401) && "invalid_grant" in responseBody) {
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
        const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
        const val AUTHORIZE_URL = "https://auth.openai.com/oauth/authorize"
        const val TOKEN_URL = "https://auth.openai.com/oauth/token"
        const val REDIRECT_URI = "http://localhost:1455/auth/callback"
        const val CALLBACK_PREFIX = REDIRECT_URI
        val SCOPES = listOf("openid", "profile", "email", "offline_access")
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
