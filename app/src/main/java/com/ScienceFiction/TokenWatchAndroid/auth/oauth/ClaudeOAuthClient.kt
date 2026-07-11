package com.ScienceFiction.TokenWatchAndroid.auth.oauth

import com.ScienceFiction.TokenWatchAndroid.auth.OAuthTokens
import com.ScienceFiction.TokenWatchAndroid.auth.Pkce
import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkTransport
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.time.Instant
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

class ClaudeOAuthClient(
    private val transport: NetworkTransport,
    private val now: () -> Instant = Instant::now,
    private val tokenUrl: String = TOKEN_URL,
) : OAuthCodeClient {
    override fun authorizeUrl(pkce: Pkce): String = AUTHORIZE_URL.toHttpUrl().newBuilder()
        .addQueryParameter("code", "true")
        .addQueryParameter("client_id", CLIENT_ID)
        .addQueryParameter("response_type", "code")
        .addQueryParameter("redirect_uri", REDIRECT_URI)
        .addQueryParameter("scope", SCOPES.joinToString(" "))
        .addQueryParameter("code_challenge", pkce.challenge)
        .addQueryParameter("code_challenge_method", "S256")
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
            .add("state", callback.state)
            .add("client_id", CLIENT_ID)
            .add("redirect_uri", REDIRECT_URI)
            .add("code_verifier", pkce.verifier)
            .build()
        return postToken(body, previous = null, exchange = true)
    }

    override suspend fun refresh(tokens: OAuthTokens): OAuthTokens {
        val refreshToken = tokens.refreshToken ?: throw OAuthException.NotAuthenticated()
        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", CLIENT_ID)
            .build()
        return postToken(body, previous = tokens, exchange = false)
    }

    private suspend fun postToken(
        body: FormBody,
        previous: OAuthTokens?,
        exchange: Boolean,
    ): OAuthTokens {
        val request = Request.Builder()
            .url(tokenUrl)
            .post(body)
            .header("Accept", "application/json")
            .build()
        val response = transport.execute(request)
        if (response.statusCode !in 200..299) {
            val detail = "HTTP ${response.statusCode}: ${response.bodyText()}"
            if (exchange) throw OAuthException.ExchangeFailed(detail)
            throw OAuthException.RefreshFailed(detail)
        }
        val json = JsonMap.decode(response.bodyText())
            ?: throw stageError(exchange, "Invalid JSON response")
        val accessToken = json.string("access_token")
            ?: throw stageError(exchange, "Missing access_token")
        val account = json.map("account")
        val organization = json.map("organization")
        val expiresIn = json.number("expires_in")?.toLong()
        return OAuthTokens(
            accessToken = accessToken,
            refreshToken = json.string("refresh_token") ?: previous?.refreshToken,
            expiresAt = expiresIn?.let { now().plusSeconds(it) },
            scopes = SCOPES,
            accountEmail = account?.string("email_address")
                ?: account?.string("email")
                ?: previous?.accountEmail,
            plan = organization?.string("name") ?: previous?.plan,
            idToken = previous?.idToken,
            accountId = previous?.accountId,
        )
    }

    private fun stageError(exchange: Boolean, detail: String): OAuthException =
        if (exchange) OAuthException.ExchangeFailed(detail) else OAuthException.RefreshFailed(detail)

    companion object {
        const val CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"
        const val AUTHORIZE_URL = "https://claude.ai/oauth/authorize"
        const val TOKEN_URL = "https://platform.claude.com/v1/oauth/token"
        const val REDIRECT_URI = "https://console.anthropic.com/oauth/code/callback"
        const val CALLBACK_PREFIX = REDIRECT_URI
        val SCOPES = listOf("org:create_api_key", "user:profile", "user:inference")
    }
}

internal object JsonMap {
    private val type = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    private val adapter = Moshi.Builder().build().adapter<Map<String, Any?>>(type)

    fun decode(json: String): Map<String, Any?>? = runCatching { adapter.fromJson(json) }.getOrNull()
    fun encode(value: Map<String, *>): String = adapter.toJson(value)
}

internal fun Map<String, Any?>.string(key: String): String? =
    (this[key] as? String)?.trim()?.takeIf(String::isNotEmpty)

internal fun Map<String, Any?>.number(key: String): Number? = this[key] as? Number

@Suppress("UNCHECKED_CAST")
internal fun Map<String, Any?>.map(key: String): Map<String, Any?>? = this[key] as? Map<String, Any?>
