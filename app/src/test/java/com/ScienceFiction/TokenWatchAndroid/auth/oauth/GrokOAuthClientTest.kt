package com.ScienceFiction.TokenWatchAndroid.auth.oauth

import com.ScienceFiction.TokenWatchAndroid.auth.OAuthTokens
import com.ScienceFiction.TokenWatchAndroid.auth.Pkce
import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkTransport
import java.time.Instant
import java.util.Base64
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Grok OAuth pure logic, ported from iOS GrokOAuthTests. The real login is a device check. */
class GrokOAuthClientTest {
    private val client = GrokOAuthClient(NetworkTransport { error("unused") })

    /** A fake id_token whose payload alone matters (it is never verified). */
    private fun idToken(payload: String): String =
        "e30.${Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray())}.sig"

    @Test fun authorizeUrlUsesTheCliParameters() {
        val pkce = Pkce.create()
        val redirect = client.loopbackRedirectUri(54321)
        val url = client.authorizeUrl(pkce, redirect).toHttpUrl()
        assertEquals("auth.x.ai", url.host)
        assertEquals("/oauth2/authorize", url.encodedPath)
        assertEquals("code", url.queryParameter("response_type"))
        assertEquals(GrokOAuthClient.CLIENT_ID, url.queryParameter("client_id"))
        assertEquals("http://127.0.0.1:54321/callback", url.queryParameter("redirect_uri"))
        assertEquals("openid profile email offline_access grok-cli:access", url.queryParameter("scope"))
        assertEquals(pkce.challenge, url.queryParameter("code_challenge"))
        assertEquals("S256", url.queryParameter("code_challenge_method"))
        assertEquals(pkce.state, url.queryParameter("state"))
        assertFalse(url.queryParameter("nonce").isNullOrEmpty())
        // No analytics referrer, and none of Claude's `code=true`.
        assertNull(url.queryParameter("referrer"))
        assertNull(url.queryParameter("code"))
        // The code only ever arrives through the loopback listener.
        assertNull(client.manualCodeRedirect)
    }

    @Test fun tokenResponseYieldsEmailAndExpiry() {
        val now = Instant.ofEpochSecond(1_800_000_000)
        val json = """{"access_token":"at-1","refresh_token":"rt-1","expires_in":3600,"token_type":"Bearer",
            |"id_token":"${idToken("""{"sub":"user-1","email":"grok@example.com"}""")}"}""".trimMargin()
        val tokens = GrokOAuthClient.tokens(json, previous = null, now = now)
        assertEquals("at-1", tokens.accessToken)
        assertEquals("rt-1", tokens.refreshToken)
        assertEquals(now.plusSeconds(3600), tokens.expiresAt)
        assertEquals("grok@example.com", tokens.accountEmail)
        assertEquals(GrokOAuthClient.SCOPES, tokens.scopes)
    }

    /** Empty refresh_token/id_token in a refresh response keep the previous ones, with email and plan. */
    @Test fun valuesMissingFromARefreshKeepThePreviousOnes() {
        val previous = OAuthTokens(
            accessToken = "old",
            refreshToken = "rt-old",
            scopes = GrokOAuthClient.SCOPES,
            accountEmail = "grok@example.com",
            plan = "SuperGrok",
            idToken = "not-a-jwt",
        )
        val tokens = GrokOAuthClient.tokens("""{"access_token":"new","refresh_token":"","expires_in":600}""", previous)
        assertEquals("new", tokens.accessToken)
        assertEquals("rt-old", tokens.refreshToken)
        assertEquals("not-a-jwt", tokens.idToken)
        assertEquals("grok@example.com", tokens.accountEmail)
        assertEquals("SuperGrok", tokens.plan)
    }

    @Test fun anEmptyAccessTokenIsRejected() {
        assertThrows(OAuthException::class.java) { GrokOAuthClient.tokens("""{"access_token":""}""", null) }
    }

    @Test fun invalidGrantIsARejectionThatNeedsANewLogin() {
        val body = """{"error":"invalid_grant","error_description":"refresh token reused"}"""
        for (status in listOf(400, 401)) {
            assertTrue(GrokOAuthClient.tokenError(status, body) is OAuthException.RefreshRevoked)
        }
        assertTrue(GrokOAuthClient.tokenError(500, "oops") is OAuthException.RefreshFailed)
    }

    /** `+`, `/`, `=`, `&` and spaces in a refresh token must reach the server unchanged. */
    @Test fun formBodyEncodesEveryReservedCharacter() {
        assertEquals(
            "grant_type=refresh_token&refresh_token=a%2Bb%2Fc%3Dd%26e%20f",
            GrokOAuthClient.formBody(listOf("grant_type" to "refresh_token", "refresh_token" to "a+b/c=d&e f")),
        )
        assertEquals("k=-._~%2A", GrokOAuthClient.formBody(listOf("k" to "-._~*")))
    }
}
