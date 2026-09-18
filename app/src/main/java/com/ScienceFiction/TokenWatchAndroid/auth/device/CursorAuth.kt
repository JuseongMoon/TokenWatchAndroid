package com.ScienceFiction.TokenWatchAndroid.auth.device

import com.ScienceFiction.TokenWatchAndroid.auth.Jwt
import com.ScienceFiction.TokenWatchAndroid.auth.OAuthTokens
import com.ScienceFiction.TokenWatchAndroid.auth.Pkce
import com.ScienceFiction.TokenWatchAndroid.auth.oauth.JsonMap
import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkTransport
import java.io.IOException
import java.time.Instant
import java.util.UUID
import kotlin.math.min
import kotlin.math.pow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

fun interface PollSleeper {
    suspend fun sleep(millis: Long)
}

/**
 * Cursor login — the same "approve the login page, then poll" flow as Cursor's official SDK and CLI,
 * ported from iOS `CursorAuth` (d856eac).
 *
 * 1. A verifier (32 random bytes, base64url), its challenge (base64url(SHA-256(verifier string))),
 *    and a uuid are created.
 * 2. `cursor.com/loginDeepControl?challenge&uuid&mode=login&redirectTarget=cli` opens in an in-app
 *    tab. The page never calls back into the app.
 * 3. `api2.cursor.sh/auth/poll` is polled: 404 `Not found` while pending, 200
 *    `{accessToken, refreshToken}` once approved. POST with a JSON body is the default; a server
 *    without the POST route (a different 404 body) switches to GET once.
 *
 * Not an official usage API: usage is read from the dashboard's `api/usage-summary` with a session
 * cookie. v1 does not refresh — the token lasts about 60 days, then the user signs in again — so the
 * refresh token is not stored.
 *
 * [transport] must not follow redirects: the session cookie is attached by hand and must never
 * travel to another host.
 */
class CursorAuth(
    private val transport: NetworkTransport,
    private val sleeper: PollSleeper = PollSleeper { millis -> delay(millis) },
) {
    /** One login's secret and its login page. */
    data class Handshake(
        val uuid: String,
        /** Proves this app started the login. Sent nowhere but `auth/poll`. */
        val verifier: String,
        val loginUrl: String,
    )

    sealed interface PollOutcome {
        data class Tokens(val accessToken: String) : PollOutcome

        /** Not approved yet (404). */
        data object Pending : PollOutcome

        /** The server has no POST route; ask with GET from now on. */
        data object SwitchToGet : PollOutcome

        /** No GET route either. */
        data object Unavailable : PollOutcome

        /** The login was rejected or expired. */
        data object Denied : PollOutcome

        /** 200 without tokens. */
        data object Malformed : PollOutcome

        /** Transient failure; ask again. */
        data object Retry : PollOutcome
    }

    /** Polls until approved and builds the stored credential; the email comes from `auth/me`. */
    suspend fun completeLogin(handshake: Handshake): OAuthTokens {
        val accessToken = pollForAccessToken(handshake)
        val userId = userId(accessToken) ?: throw DeviceFlowException.Http("Cursor token")
        // The email only labels the card and prevents duplicates; its failure does not fail login.
        val email = try {
            fetchEmail(accessToken, userId)
        } catch (error: IOException) {
            null
        }
        return credential(accessToken, userId, email)
    }

    /**
     * Waits with backoff while pending (404). Returns the access token once approved; throws on
     * rejection, a missing route, repeated errors, or when the attempts run out.
     */
    suspend fun pollForAccessToken(handshake: Handshake): String {
        var useGet = false
        var checkedPendingBody = false
        var consecutiveErrors = 0
        for (attempt in 0 until MAX_POLL_ATTEMPTS) {
            currentCoroutineContext().ensureActive()
            val delayMillis = (min(POLL_BASE_SECONDS * 1.2.pow(attempt), POLL_MAX_SECONDS) * 1_000).toLong()

            val response = try {
                transport.execute(pollRequest(handshake, useGet))
            } catch (error: IOException) {
                // A poll that runs for minutes should not restart the login over one dropped request.
                consecutiveErrors += 1
                if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) throw error
                sleeper.sleep(delayMillis)
                continue
            }

            when (val outcome = pollOutcome(response.statusCode, response.bodyText(), useGet, checkedPendingBody)) {
                is PollOutcome.Tokens -> return outcome.accessToken
                PollOutcome.Pending -> {
                    checkedPendingBody = true
                    consecutiveErrors = 0
                    sleeper.sleep(delayMillis)
                }
                PollOutcome.SwitchToGet -> useGet = true
                PollOutcome.Unavailable -> throw DeviceFlowException.Http("Cursor auth/poll unavailable")
                PollOutcome.Denied -> throw DeviceFlowException.Denied()
                PollOutcome.Malformed -> throw DeviceFlowException.Http("Cursor auth/poll: unexpected response")
                PollOutcome.Retry -> {
                    consecutiveErrors += 1
                    if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                        throw DeviceFlowException.Http("HTTP ${response.statusCode}")
                    }
                    sleeper.sleep(delayMillis)
                }
            }
        }
        throw DeviceFlowException.TimedOut()
    }

    /** Account email right after login. A session the server does not accept answers 204 → null. */
    suspend fun fetchEmail(accessToken: String, userId: String): String? {
        val response = transport.execute(
            Request.Builder()
                .url(ME_URL)
                .get()
                .header("Cookie", cookieHeader(userId, accessToken))
                .header("Accept", "application/json")
                .build(),
        )
        if (response.statusCode != 200) return null
        return JsonMap.decode(response.bodyText())?.get("email")
            ?.let { it as? String }
            ?.trim()
            ?.takeIf(String::isNotEmpty)
    }

    companion object {
        const val LOGIN_URL = "https://cursor.com/loginDeepControl"
        const val POLL_URL = "https://api2.cursor.sh/auth/poll"
        const val ME_URL = "https://cursor.com/api/auth/me"

        /** Shown on the login page as what is being signed into; CLI tokens work for usage-summary. */
        const val REDIRECT_TARGET = "cli"

        /** SDK defaults: up to 150 polls, from 1 s growing ×1.2 to a 10 s cap (about 20 minutes). */
        const val MAX_POLL_ATTEMPTS = 150
        private const val POLL_BASE_SECONDS = 1.0
        private const val POLL_MAX_SECONDS = 10.0
        private const val MAX_CONSECUTIVE_ERRORS = 3
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        fun makeHandshake(
            pkce: Pkce = Pkce.create(),
            uuid: String = UUID.randomUUID().toString().lowercase(),
        ): Handshake {
            val url = LOGIN_URL.toHttpUrl().newBuilder()
                .addQueryParameter("challenge", pkce.challenge)
                .addQueryParameter("uuid", uuid)
                .addQueryParameter("mode", "login")
                .addQueryParameter("redirectTarget", REDIRECT_TARGET)
                .build()
                .toString()
            return Handshake(uuid = uuid, verifier = pkce.verifier, loginUrl = url)
        }

        /** Classifies one `auth/poll` response, following the SDK's `pollForLoginTokens` rules. */
        fun pollOutcome(status: Int, body: String, usingGet: Boolean, checkedPendingBody: Boolean): PollOutcome =
            when (status) {
                404 -> {
                    val text = body.trim()
                    when {
                        checkedPendingBody -> PollOutcome.Pending
                        !usingGet && text != "Not found" -> PollOutcome.SwitchToGet
                        usingGet && isRouteNotFound(text) -> PollOutcome.Unavailable
                        else -> PollOutcome.Pending
                    }
                }
                in 200..299 -> {
                    val json = JsonMap.decode(body)
                    val accessToken = json?.get("accessToken") as? String
                    if (accessToken.isNullOrEmpty() || json?.get("refreshToken") !is String) {
                        PollOutcome.Malformed
                    } else {
                        PollOutcome.Tokens(accessToken)
                    }
                }
                400, 401, 403, 410 -> PollOutcome.Denied
                else -> PollOutcome.Retry
            }

        /** A 404 meaning the route itself is missing (`{"message":"Route GET:/auth/poll not found"}`). */
        fun isRouteNotFound(text: String): Boolean {
            if (!text.startsWith("{")) return false
            val message = JsonMap.decode(text)?.get("message") as? String ?: return false
            return message.startsWith("Route ") && "not found" in message
        }

        /** POST carries the verifier in the body (out of URLs and access logs); only GET uses the query. */
        fun pollRequest(handshake: Handshake, useGet: Boolean): Request {
            val builder = if (useGet) {
                Request.Builder()
                    .url(
                        POLL_URL.toHttpUrl().newBuilder()
                            .addQueryParameter("uuid", handshake.uuid)
                            .addQueryParameter("verifier", handshake.verifier)
                            .build(),
                    )
                    .get()
            } else {
                val body = JsonMap.encode(mapOf("uuid" to handshake.uuid, "verifier" to handshake.verifier))
                Request.Builder()
                    .url(POLL_URL)
                    .post(body.toRequestBody(JSON_MEDIA_TYPE))
            }
            return builder.header("Accept", "application/json").build()
        }

        /**
         * The session cookie cursor.com's dashboard API accepts. The token alone, or a Bearer header,
         * is a 401: it has to be paired with the user id.
         */
        fun cookieHeader(userId: String, accessToken: String): String =
            "WorkosCursorSessionToken=$userId%3A%3A$accessToken"

        /** Cookie for a stored credential; the user id falls back to the JWT `sub`. */
        fun cookieHeader(tokens: OAuthTokens): String? {
            val userId = tokens.accountId?.takeIf(String::isNotEmpty) ?: userId(tokens.accessToken) ?: return null
            return cookieHeader(userId, tokens.accessToken)
        }

        /** The part after the last `|` of the JWT `sub` (e.g. `auth0|user_01ABC`) is the cookie's user id. */
        fun userId(jwt: String): String? {
            val sub = Jwt.payload(jwt)?.get("sub") as? String ?: return null
            return sub.substringAfterLast('|').trim().takeIf(String::isNotEmpty)
        }

        /** JWT `exp` (seconds). Unknown expiry means a 401 is what eventually asks for a new login. */
        fun expiry(jwt: String): Instant? {
            val exp = (Jwt.payload(jwt)?.get("exp") as? Number)?.toDouble() ?: return null
            return Instant.ofEpochMilli((exp * 1_000).toLong())
        }

        /** Stored credential: no refresh token (v1), expiry from the JWT, user id in `accountId`. */
        fun credential(accessToken: String, userId: String, email: String?): OAuthTokens = OAuthTokens(
            accessToken = accessToken,
            refreshToken = null,
            expiresAt = expiry(accessToken),
            accountEmail = email,
            accountId = userId,
        )
    }
}
