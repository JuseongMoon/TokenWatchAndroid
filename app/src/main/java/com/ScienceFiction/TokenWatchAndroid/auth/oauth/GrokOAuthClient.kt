package com.ScienceFiction.TokenWatchAndroid.auth.oauth

import com.ScienceFiction.TokenWatchAndroid.auth.Jwt
import com.ScienceFiction.TokenWatchAndroid.auth.OAuthTokens
import com.ScienceFiction.TokenWatchAndroid.auth.Pkce
import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkTransport
import java.time.Instant
import java.util.UUID
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Grok (xAI) OAuth with PKCE, matching iOS `GrokOAuth` (8cff71c).
 *
 * The constants and flow follow the official Grok CLI's default login: an auth.x.ai authorization
 * code with PKCE, returned to a loopback `http://127.0.0.1:PORT/callback` on a fresh port each time.
 * It is a public client with no secret. Two deliberate differences from the CLI: only the minimum
 * scopes for the usage lookup are requested, and the CLI's analytics `referrer` is not sent.
 *
 * Refresh tokens rotate on every refresh (reusing an old one yields invalid_grant), so a refresh is
 * never retried; coalescing and persistence belong to `TokenStore`.
 */
class GrokOAuthClient(
    private val transport: NetworkTransport,
    private val now: () -> Instant = Instant::now,
    private val tokenUrl: String = TOKEN_URL,
) : BrowserOAuthClient {
    override fun loopbackRedirectUri(port: Int): String = "http://127.0.0.1:$port/callback"

    override fun authorizeUrl(pkce: Pkce, redirect: String): String = AUTHORIZE_URL.toHttpUrl().newBuilder()
        .addQueryParameter("response_type", "code")
        .addQueryParameter("client_id", CLIENT_ID)
        .addQueryParameter("redirect_uri", redirect)
        .addQueryParameter("scope", SCOPES.joinToString(" "))
        .addQueryParameter("code_challenge", pkce.challenge)
        .addQueryParameter("code_challenge_method", "S256")
        .addQueryParameter("state", pkce.state)
        // OIDC nonce, fresh per request like the CLI. The id_token is only read for the display
        // email and is not verified.
        .addQueryParameter("nonce", UUID.randomUUID().toString())
        .build()
        .toString()

    /** The code only ever arrives through the loopback listener; there is no paste fallback. */
    override val manualCodeRedirect: String? = null

    override suspend fun exchange(code: String, state: String, pkce: Pkce, redirect: String): OAuthTokens {
        if (state != pkce.state) throw OAuthException.StateMismatch()
        val body = formBody(
            listOf(
                "grant_type" to "authorization_code",
                "code" to code,
                "redirect_uri" to redirect,
                "client_id" to CLIENT_ID,
                "code_verifier" to pkce.verifier,
            ),
        )
        return try {
            tokens(retryingTransientFailureOnce { postToken(body) }, previous = null, now = now())
        } catch (revoked: OAuthException.RefreshRevoked) {
            // invalid_grant at the exchange stage means the code expired or was reused.
            throw OAuthException.CodeExpired()
        } catch (failed: OAuthException.RefreshFailed) {
            throw OAuthException.ExchangeFailed(failed.detail)
        }
    }

    override suspend fun refresh(tokens: OAuthTokens): OAuthTokens {
        val refreshToken = tokens.refreshToken ?: throw OAuthException.NotAuthenticated()
        val body = formBody(
            listOf(
                "grant_type" to "refresh_token",
                "refresh_token" to refreshToken,
                "client_id" to CLIENT_ID,
            ),
        )
        return tokens(postToken(body), previous = tokens, now = now())
    }

    private suspend fun postToken(body: String): String {
        val response = transport.execute(
            Request.Builder()
                .url(tokenUrl)
                .post(body.toRequestBody(FORM_MEDIA_TYPE))
                .header("Accept", "application/json")
                .build(),
        )
        if (response.statusCode !in 200..299) throw tokenError(response.statusCode, response.bodyText())
        return response.bodyText()
    }

    companion object {
        const val CLIENT_ID = "b1a00492-073a-47ea-816f-4c329264a828"
        const val AUTHORIZE_URL = "https://auth.x.ai/oauth2/authorize"
        const val TOKEN_URL = "https://auth.x.ai/oauth2/token"

        /** `grok-cli:access` is what authorizes the usage endpoint (cli-chat-proxy.grok.com). */
        val SCOPES = listOf("openid", "profile", "email", "offline_access", "grok-cli:access")

        private val FORM_MEDIA_TYPE = "application/x-www-form-urlencoded".toMediaType()

        /**
         * Token response to a stored credential. Empty `refresh_token`/`id_token` in a refresh
         * response keep the previous values. The plan is not in the token; it carries over and is
         * updated from the usage response.
         */
        fun tokens(json: String, previous: OAuthTokens?, now: Instant = Instant.now()): OAuthTokens {
            val map = JsonMap.decode(json) ?: throw OAuthException.RefreshFailed("Invalid JSON response")
            val accessToken = map.string("access_token")
                ?: throw OAuthException.RefreshFailed("empty access_token")
            val idToken = map.string("id_token") ?: previous?.idToken
            return OAuthTokens(
                accessToken = accessToken,
                refreshToken = map.string("refresh_token") ?: previous?.refreshToken,
                expiresAt = map.number("expires_in")?.toLong()?.let(now::plusSeconds),
                scopes = map.string("scope")?.split(' ')?.filter(String::isNotEmpty) ?: SCOPES,
                accountEmail = idToken?.let(Jwt::email) ?: previous?.accountEmail,
                plan = previous?.plan,
                idToken = idToken,
            )
        }

        /** 400/401 with invalid_grant is a rejection no retry can fix: the user must log in again. */
        fun tokenError(status: Int, body: String): OAuthException =
            if ((status == 400 || status == 401) && "invalid_grant" in body) {
                OAuthException.RefreshRevoked()
            } else {
                OAuthException.RefreshFailed("HTTP $status: $body")
            }

        /**
         * `application/x-www-form-urlencoded` body that leaves only `A-Za-z0-9-._~` unescaped. A
         * lenient encoder that keeps `+` would have the server decode it as a space and silently
         * change the token.
         */
        fun formBody(params: List<Pair<String, String>>): String =
            params.joinToString("&") { (name, value) ->
                "${percentEncodeStrict(name)}=${percentEncodeStrict(value)}"
            }
    }
}
