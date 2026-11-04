package com.ScienceFiction.TokenWatchAndroid.auth.oauth

import com.ScienceFiction.TokenWatchAndroid.auth.CredentialVault
import com.ScienceFiction.TokenWatchAndroid.auth.OAuthTokens
import com.ScienceFiction.TokenWatchAndroid.auth.Pkce
import com.ScienceFiction.TokenWatchAndroid.auth.TokenRefresher
import com.ScienceFiction.TokenWatchAndroid.auth.TokenStore
import com.ScienceFiction.TokenWatchAndroid.domain.AgentProvider
import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkResponse
import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkTransport
import java.time.Instant
import java.util.Base64
import java.util.UUID
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

    // region A2: Codex permanent refresh failures

    private val permanentCodexCodes = listOf(
        "invalid_grant",
        "refresh_token_expired",
        "refresh_token_reused",
        "refresh_token_invalidated",
    )

    /** The three body shapes token endpoints use for a machine-readable error code. */
    private fun errorBodies(code: String) = listOf(
        """{"error":"$code","error_description":"Please sign in again."}""",
        """{"error":{"code":"$code","message":"Please sign in again."}}""",
        """{"code":"$code","message":"Please sign in again."}""",
    )

    @Test
    fun codexPermanentRefreshCodesAreRevocationsAtAnyStatusInEveryBodyShape() {
        val tokens = OAuthTokens("old", refreshToken = "dead")
        for (code in permanentCodexCodes - "invalid_grant") {
            for (body in errorBodies(code) + errorBodies(code.uppercase())) {
                for (status in listOf(400, 401, 403, 429, 500)) {
                    assertThrows("$status $body", OAuthException.RefreshRevoked::class.java) {
                        runBlocking { CodexOAuthClient(FakeTransport(body, status)).refresh(tokens) }
                    }
                }
            }
        }
    }

    @Test
    fun codexAny401AndA400InvalidGrantAreRevocations() {
        val tokens = OAuthTokens("old", refreshToken = "dead")
        val cases = listOf(
            401 to """{"error":{"code":"token_unknown"}}""",
            401 to "not json",
            401 to "",
            400 to """{"error":"invalid_grant"}""",
            400 to """{"error":{"code":"INVALID_GRANT"}}""",
            400 to """{"code":"invalid_grant"}""",
            403 to """{"error":{"code":"refresh_token_reused"}}""",
        )
        for ((status, body) in cases) {
            assertThrows("$status $body", OAuthException.RefreshRevoked::class.java) {
                runBlocking { CodexOAuthClient(FakeTransport(body, status)).refresh(tokens) }
            }
        }
    }

    @Test
    fun codexTransientOrUnknownRefreshFailuresKeepCredentialRetryable() {
        val tokens = OAuthTokens("old", refreshToken = "maybe-valid")
        val cases = listOf(
            429 to "{}",
            500 to """{"error":"server_error"}""",
            503 to "upstream unavailable",
            400 to """{"error":"invalid_request"}""",
            400 to "not json",
            // invalid_grant counts only with 400, and only as a machine-readable code.
            403 to """{"error":"invalid_grant"}""",
            500 to """{"error":{"code":"invalid_grant"}}""",
            400 to """{"error":"server_error","error_description":"not an invalid_grant"}""",
            400 to """{"message":"refresh_token_expired"}""",
            400 to "invalid_grant",
        )
        for ((status, body) in cases) {
            assertThrows("$status $body", OAuthException.RefreshFailed::class.java) {
                runBlocking { CodexOAuthClient(FakeTransport(body, status)).refresh(tokens) }
            }
        }
        // A transport failure never reaches classification and is rethrown unchanged.
        assertThrows(java.io.IOException::class.java) {
            runBlocking {
                CodexOAuthClient(NetworkTransport { throw java.io.IOException("offline") }).refresh(tokens)
            }
        }
    }

    @Test
    fun codexExchangeFailureIsNeverClassifiedAsRefreshRevocation() {
        assertThrows(OAuthException.ExchangeFailed::class.java) {
            runBlocking {
                CodexOAuthClient(FakeTransport("""{"error":"refresh_token_expired"}""", 400))
                    .exchange(OAuthCallback("code", pkce.state), pkce)
            }
        }
    }

    @Test
    fun codexPermanentFailureIsNotRequestedAgainThroughTokenStore(): Unit = runBlocking {
        for (code in permanentCodexCodes) {
            var requests = 0
            val transport = NetworkTransport {
                requests += 1
                NetworkResponse(401, emptyMap(), """{"error":{"code":"$code"}}""".toByteArray())
            }
            val client = CodexOAuthClient(transport)
            val id = UUID.randomUUID()
            val vault = object : CredentialVault {
                val values = mutableMapOf<UUID, OAuthTokens>()
                @Synchronized override fun save(agentId: UUID, tokens: OAuthTokens) { values[agentId] = tokens }
                @Synchronized override fun load(agentId: UUID) = values[agentId]
                @Synchronized override fun delete(agentId: UUID) { values.remove(agentId) }
            }
            val store = TokenStore(
                vault,
                TokenRefresher { _, input -> client.refresh(input) },
            )
            store.save(id, OAuthTokens("access", refreshToken = "dead"))

            assertThrows(code, OAuthException.RefreshRevoked::class.java) {
                runBlocking { store.forceRefresh(id, AgentProvider.CODEX) }
            }
            // The second attempt fails locally as "sign in again" without resending the dead token.
            assertThrows(code, OAuthException.NotAuthenticated::class.java) {
                runBlocking { store.forceRefresh(id, AgentProvider.CODEX) }
            }
            assertEquals(code, 1, requests)
            assertEquals(code, null, store.tokens(id)?.refreshToken)
            assertEquals(code, "access", store.tokens(id)?.accessToken)
        }
    }

    @Test
    fun codexTransientFailureKeepsRefreshTokenThroughTokenStore(): Unit = runBlocking {
        val client = CodexOAuthClient(FakeTransport("""{"error":"temporarily_unavailable"}""", 503))
        val id = UUID.randomUUID()
        val vault = object : CredentialVault {
            val values = mutableMapOf<UUID, OAuthTokens>()
            @Synchronized override fun save(agentId: UUID, tokens: OAuthTokens) { values[agentId] = tokens }
            @Synchronized override fun load(agentId: UUID) = values[agentId]
            @Synchronized override fun delete(agentId: UUID) { values.remove(agentId) }
        }
        val store = TokenStore(
            vault,
            TokenRefresher { _, input -> client.refresh(input) },
        )
        store.save(id, OAuthTokens("access", refreshToken = "still-valid"))

        assertThrows(OAuthException.RefreshFailed::class.java) {
            runBlocking { store.forceRefresh(id, AgentProvider.CODEX) }
        }
        assertEquals("still-valid", store.tokens(id)?.refreshToken)
    }

    // endregion

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
