package com.ScienceFiction.TokenWatchAndroid.auth.device

import com.ScienceFiction.TokenWatchAndroid.auth.OAuthTokens
import com.ScienceFiction.TokenWatchAndroid.auth.Pkce
import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkResponse
import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkTransport
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** Cursor login pure logic, ported from iOS CursorAuthTests. The real login and polling are device checks. */
class CursorAuthTest {
    private fun jwt(payload: String): String =
        "e30.${Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray())}.sig"

    @Test fun loginPageUsesTheSdkHandshake() {
        val pkce = Pkce.create()
        val handshake = CursorAuth.makeHandshake(pkce, uuid = "11111111-2222-4333-8444-555555555555")
        val url = handshake.loginUrl.toHttpUrl()
        assertEquals("cursor.com", url.host)
        assertEquals("/loginDeepControl", url.encodedPath)
        // challenge = base64url(SHA-256(the verifier string)), not of raw bytes.
        assertEquals(Pkce.challengeForVerifier(pkce.verifier), url.queryParameter("challenge"))
        assertEquals("11111111-2222-4333-8444-555555555555", url.queryParameter("uuid"))
        assertEquals("login", url.queryParameter("mode"))
        assertEquals("cli", url.queryParameter("redirectTarget"))
        assertEquals(pkce.verifier, handshake.verifier)
        // The verifier never goes into the login page URL.
        assertNull(url.queryParameter("verifier"))
    }

    @Test fun pendingIsToldApartFromAMissingPostRoute() {
        assertEquals(CursorAuth.PollOutcome.Pending, CursorAuth.pollOutcome(404, "Not found", false, false))
        val postMissing = """{"message":"Route POST:/auth/poll not found"}"""
        assertEquals(CursorAuth.PollOutcome.SwitchToGet, CursorAuth.pollOutcome(404, postMissing, false, false))
        val getMissing = """{"message":"Route GET:/auth/poll not found"}"""
        assertEquals(CursorAuth.PollOutcome.Unavailable, CursorAuth.pollOutcome(404, getMissing, true, false))
        // Once confirmed, any later 404 is pending whatever its body.
        assertEquals(CursorAuth.PollOutcome.Pending, CursorAuth.pollOutcome(404, postMissing, false, true))
    }

    @Test fun approvalYieldsTheTokenAndOddResponsesAreRejected() {
        assertEquals(
            CursorAuth.PollOutcome.Tokens("at"),
            CursorAuth.pollOutcome(200, """{"accessToken":"at","refreshToken":"rt","authId":"a"}""", false, true),
        )
        assertEquals(CursorAuth.PollOutcome.Malformed, CursorAuth.pollOutcome(200, """{"accessToken":"at"}""", false, true))
        for (status in listOf(400, 401, 403, 410)) {
            assertEquals(CursorAuth.PollOutcome.Denied, CursorAuth.pollOutcome(status, "", false, true))
        }
        assertEquals(CursorAuth.PollOutcome.Retry, CursorAuth.pollOutcome(502, "", false, true))
    }

    /** POST carries the verifier in the body (out of URLs and logs); only the GET fallback uses the query. */
    @Test fun pollRequestsPostByDefaultAndGetWithQuery() {
        val handshake = CursorAuth.makeHandshake(Pkce.create(), uuid = "u-1")
        val post = CursorAuth.pollRequest(handshake, useGet = false)
        assertEquals("POST", post.method)
        assertNull(post.url.query)
        val body = Buffer().also { post.body?.writeTo(it) }.readUtf8()
        assertEquals("""{"uuid":"u-1","verifier":"${handshake.verifier}"}""", body)

        val get = CursorAuth.pollRequest(handshake, useGet = true)
        assertEquals("GET", get.method)
        assertEquals(handshake.verifier, get.url.queryParameter("verifier"))
    }

    @Test fun sessionCookieJoinsTheLastSubSegmentAndTheToken() {
        val token = jwt("""{"sub":"auth0|user_01ABCDEF","exp":1800000000}""")
        assertEquals("user_01ABCDEF", CursorAuth.userId(token))
        assertEquals(Instant.ofEpochSecond(1_800_000_000), CursorAuth.expiry(token))
        assertEquals(
            "WorkosCursorSessionToken=user_01ABCDEF%3A%3A$token",
            CursorAuth.cookieHeader("user_01ABCDEF", token),
        )
        // A stored credential without the user id falls back to the JWT.
        assertEquals("WorkosCursorSessionToken=user_01ABCDEF%3A%3A$token", CursorAuth.cookieHeader(OAuthTokens(token)))
        assertNull(CursorAuth.userId("not-a-jwt"))
    }

    @Test fun loginCredentialHasNoRefreshButExpiryAndUserId() {
        val token = jwt("""{"sub":"google-oauth2|user_9","exp":1800000000}""")
        val tokens = CursorAuth.credential(token, "user_9", "c@example.com")
        assertNull(tokens.refreshToken)
        assertEquals(Instant.ofEpochSecond(1_800_000_000), tokens.expiresAt)
        assertEquals("user_9", tokens.accountId)
        assertEquals("c@example.com", tokens.accountEmail)
    }

    /** Pending, then a missing POST route, then approval over GET; the email comes from auth/me. */
    @Test fun completeLoginPollsUntilApprovedAndReadsTheEmail() = runBlocking {
        val token = jwt("""{"sub":"auth0|user_7","exp":1800000000}""")
        val requests = mutableListOf<Request>()
        val responses = ArrayDeque(
            listOf(
                404 to """{"message":"Route POST:/auth/poll not found"}""",
                404 to "Not found",
                200 to """{"accessToken":"$token","refreshToken":"rt"}""",
                200 to """{"email":"  me@example.com "}""",
            ),
        )
        val transport = NetworkTransport { request ->
            requests += request
            val (status, body) = responses.removeFirst()
            NetworkResponse(status, emptyMap(), body.toByteArray())
        }
        val sleeps = mutableListOf<Long>()
        val tokens = CursorAuth(transport) { sleeps += it }.completeLogin(CursorAuth.makeHandshake())

        assertEquals(listOf("POST", "GET", "GET", "GET"), requests.map { it.method })
        assertEquals(CursorAuth.ME_URL, requests.last().url.toString())
        assertEquals("WorkosCursorSessionToken=user_7%3A%3A$token", requests.last().header("Cookie"))
        assertEquals("me@example.com", tokens.accountEmail)
        assertEquals("user_7", tokens.accountId)
        assertEquals(1, sleeps.size)
    }

    @Test fun aRejectedLoginIsDenied() {
        val transport = NetworkTransport { NetworkResponse(410, emptyMap(), ByteArray(0)) }
        assertThrows(DeviceFlowException.Denied::class.java) {
            runBlocking { CursorAuth(transport) {}.pollForAccessToken(CursorAuth.makeHandshake()) }
        }
    }

    @Test fun pollingThatNeverApprovesTimesOut() {
        val transport = NetworkTransport { NetworkResponse(404, emptyMap(), "Not found".toByteArray()) }
        assertThrows(DeviceFlowException.TimedOut::class.java) {
            runBlocking { CursorAuth(transport) {}.pollForAccessToken(CursorAuth.makeHandshake()) }
        }
    }
}
