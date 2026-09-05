package com.ScienceFiction.TokenWatchAndroid.auth.oauth

import com.ScienceFiction.TokenWatchAndroid.auth.OAuthTokens
import com.ScienceFiction.TokenWatchAndroid.auth.Pkce
import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkResponse
import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkTransport
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OAuthCodeClientsTest {
    private val now = Instant.parse("2026-07-11T00:00:00Z")
    private val pkce = Pkce(
        verifier = "verifier",
        challenge = Pkce.challengeForVerifier("verifier"),
        state = "expected-state",
    )

    @Test
    fun authorizeUrlsAndCallbacksCarryPkce() {
        val transport = FakeTransport("{}")
        val claude = ClaudeOAuthClient(transport)
        val codex = CodexOAuthClient(transport)

        assertTrue(claude.authorizeUrl(pkce).contains("code_challenge=${pkce.challenge}"))
        assertTrue(codex.authorizeUrl(pkce).contains("codex_cli_simplified_flow=true"))
        assertEquals(
            OAuthCallback("abc", pkce.state),
            claude.parseCallback("${ClaudeOAuthClient.CALLBACK_PREFIX}?code=abc&state=${pkce.state}"),
        )
        assertEquals(
            OAuthCallback("xyz", pkce.state),
            codex.parseCallback("${CodexOAuthClient.CALLBACK_PREFIX}?code=xyz&state=${pkce.state}"),
        )
    }

    @Test(expected = OAuthException.StateMismatch::class)
    fun exchangeRejectsMismatchedStateBeforeNetwork(): Unit = runBlocking {
        ClaudeOAuthClient(FakeTransport("{}"))
            .exchange(OAuthCallback("code", "wrong"), pkce)
        Unit
    }

    @Test
    fun claudeExchangeMapsAccountAndFormBody() = runBlocking {
        val transport = FakeTransport(
            """{"access_token":"access","refresh_token":"refresh","expires_in":3600,"account":{"email_address":"a@example.com"},"organization":{"name":"pro"}}""",
        )
        val tokens = ClaudeOAuthClient(transport, now = { now })
            .exchange(OAuthCallback("code", pkce.state), pkce)

        assertEquals("access", tokens.accessToken)
        assertEquals("refresh", tokens.refreshToken)
        assertEquals("a@example.com", tokens.accountEmail)
        assertEquals("pro", tokens.plan)
        assertEquals(now.plusSeconds(3600), tokens.expiresAt)
        assertTrue(transport.lastRequest.bodyText().contains("code_verifier=verifier"))
        assertEquals("application/x-www-form-urlencoded", transport.lastRequest.body?.contentType().toString())
    }

    @Test
    fun codexExchangeExtractsJwtClaims() = runBlocking {
        val idToken = jwt(
            """{"email":"codex@example.com","chatgpt_plan_type":"chatgpt_plus","https://api.openai.com/auth":{"chatgpt_account_id":"acc"}}""",
        )
        val transport = FakeTransport(
            """{"access_token":"access","refresh_token":"refresh","id_token":"$idToken","expires_in":7200}""",
        )
        val tokens = CodexOAuthClient(transport, now = { now })
            .exchange(OAuthCallback("code", pkce.state), pkce)

        assertEquals("codex@example.com", tokens.accountEmail)
        assertEquals("Chatgpt Plus", tokens.plan)
        assertEquals("acc", tokens.accountId)
        assertNotNull(tokens.idToken)
    }

    @Test
    fun refreshPreservesRotatingFieldsWhenResponseOmitsThem() = runBlocking {
        val previous = OAuthTokens(
            accessToken = "old",
            refreshToken = "keep-refresh",
            accountEmail = "keep@example.com",
            plan = "keep-plan",
        )
        val claudeTransport = FakeTransport("""{"access_token":"new","expires_in":60}""")
        val refreshed = ClaudeOAuthClient(claudeTransport, now = { now }).refresh(previous)
        assertEquals("keep-refresh", refreshed.refreshToken)
        assertEquals("keep@example.com", refreshed.accountEmail)
        assertEquals("keep-plan", refreshed.plan)

        val codexTransport = FakeTransport("""{"access_token":"newer","expires_in":60}""")
        CodexOAuthClient(codexTransport, now = { now }).refresh(previous)
        assertTrue(codexTransport.lastRequest.bodyText().contains("refresh_token"))
        assertEquals(
            "application/json",
            codexTransport.lastRequest.body?.contentType()?.let { "${it.type}/${it.subtype}" },
        )
    }

    @Test
    fun invalidGrantIsClassifiedAsPermanentRefreshRevocation() {
        val tokens = OAuthTokens("old", refreshToken = "revoked")
        listOf<OAuthCodeClient>(
            ClaudeOAuthClient(FakeTransport("""{"error":"invalid_grant"}""", 400)),
            CodexOAuthClient(FakeTransport("""{"error":"invalid_grant"}""", 401)),
        ).forEach { client ->
            assertThrows(OAuthException.RefreshRevoked::class.java) {
                runBlocking { client.refresh(tokens) }
            }
        }
    }

    private class FakeTransport(
        private val json: String,
        private val statusCode: Int = 200,
    ) : NetworkTransport {
        lateinit var lastRequest: Request
        override suspend fun execute(request: Request): NetworkResponse {
            lastRequest = request
            return NetworkResponse(statusCode, emptyMap(), json.toByteArray())
        }
    }

    private fun Request.bodyText(): String = Buffer().also { body?.writeTo(it) }.readUtf8()

    private fun jwt(payload: String): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        return listOf("{}", payload, "signature")
            .joinToString(".") { encoder.encodeToString(it.toByteArray()) }
    }
}
