package com.ScienceFiction.TokenWatchAndroid.auth

import com.ScienceFiction.TokenWatchAndroid.auth.oauth.OAuthCallback
import com.ScienceFiction.TokenWatchAndroid.auth.oauth.OAuthCodeClient
import com.ScienceFiction.TokenWatchAndroid.auth.oauth.OAuthException
import com.ScienceFiction.TokenWatchAndroid.domain.AgentProvider
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class TokenStoreTest {
    private val now = Instant.parse("2026-07-11T00:00:00Z")

    @Test
    fun validCredentialIsReturnedWithoutRefresh(): Unit = runBlocking {
        val id = UUID.randomUUID()
        val stored = OAuthTokens(
            accessToken = "valid",
            refreshToken = "refresh",
            expiresAt = now.plusSeconds(3_600),
        )
        val vault = MemoryVault(id to stored)
        var refreshCount = 0
        val store = TokenStore(
            vault = vault,
            refresher = TokenRefresher { _, _ ->
                refreshCount += 1
                error("must not refresh")
            },
            now = { now },
        )

        assertSame(stored, store.validTokens(id, AgentProvider.CLAUDE))
        assertEquals(0, refreshCount)
    }

    @Test
    fun expirySafetyWindowRefreshesAndPersistsRotatedCredential(): Unit = runBlocking {
        val id = UUID.randomUUID()
        val expired = OAuthTokens(
            accessToken = "old",
            refreshToken = "refresh",
            expiresAt = now.plusSeconds(30),
        )
        val refreshed = OAuthTokens(
            accessToken = "new",
            refreshToken = "rotated",
            expiresAt = now.plusSeconds(7_200),
        )
        val vault = MemoryVault(id to expired)
        val store = TokenStore(
            vault = vault,
            refresher = TokenRefresher { provider, received ->
                assertEquals(AgentProvider.CODEX, provider)
                assertSame(expired, received)
                refreshed
            },
            now = { now },
        )

        assertSame(refreshed, store.validTokens(id, AgentProvider.CODEX))
        assertSame(refreshed, vault.load(id))
    }

    @Test
    fun forceRefreshIgnoresExpiryButRequiresRefreshToken(): Unit = runBlocking {
        val id = UUID.randomUUID()
        val stored = OAuthTokens("valid", refreshToken = "refresh", expiresAt = now.plusSeconds(9_000))
        val refreshed = OAuthTokens("forced", refreshToken = "refresh-2")
        val vault = MemoryVault(id to stored)
        val store = TokenStore(vault, TokenRefresher { _, _ -> refreshed }, now = { now })

        assertSame(refreshed, store.forceRefresh(id, AgentProvider.CLAUDE))

        val apiKeyId = UUID.randomUUID()
        vault.save(apiKeyId, OAuthTokens.apiKey("key"))
        assertThrows(OAuthException.NotAuthenticated::class.java) {
            runBlocking { store.forceRefresh(apiKeyId, AgentProvider.OPENROUTER) }
        }
    }

    @Test
    fun providerRefresherSupportsOnlyClaudeAndCodex(): Unit = runBlocking {
        val input = OAuthTokens("old", refreshToken = "refresh")
        val claudeResult = OAuthTokens("claude")
        val codexResult = OAuthTokens("codex")
        val dispatcher = ProviderTokenRefresher(
            claude = FakeOAuthClient(claudeResult),
            codex = FakeOAuthClient(codexResult),
        )

        assertSame(claudeResult, dispatcher.refresh(AgentProvider.CLAUDE, input))
        assertSame(codexResult, dispatcher.refresh(AgentProvider.CODEX, input))
        assertThrows(OAuthException.NotAuthenticated::class.java) {
            runBlocking { dispatcher.refresh(AgentProvider.COPILOT, input) }
        }
    }

    private class MemoryVault(vararg initial: Pair<UUID, OAuthTokens>) : CredentialVault {
        private val values = initial.toMap().toMutableMap()

        override fun save(agentId: UUID, tokens: OAuthTokens) {
            values[agentId] = tokens
        }

        override fun load(agentId: UUID): OAuthTokens? = values[agentId]

        override fun delete(agentId: UUID) {
            values.remove(agentId)
        }
    }

    private class FakeOAuthClient(private val refreshResult: OAuthTokens) : OAuthCodeClient {
        override fun authorizeUrl(pkce: Pkce): String = error("unused")
        override fun parseCallback(url: String): OAuthCallback? = error("unused")
        override suspend fun exchange(callback: OAuthCallback, pkce: Pkce): OAuthTokens = error("unused")
        override suspend fun refresh(tokens: OAuthTokens): OAuthTokens = refreshResult
    }
}
