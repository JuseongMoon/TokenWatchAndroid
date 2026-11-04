package com.ScienceFiction.TokenWatchAndroid.auth

import com.ScienceFiction.TokenWatchAndroid.auth.oauth.BrowserOAuthClient
import com.ScienceFiction.TokenWatchAndroid.auth.oauth.OAuthCallback
import com.ScienceFiction.TokenWatchAndroid.auth.oauth.OAuthCodeClient
import com.ScienceFiction.TokenWatchAndroid.auth.oauth.OAuthException
import com.ScienceFiction.TokenWatchAndroid.domain.AgentProvider
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
    fun callerCancellationDoesNotAbandonRotatedCredential(): Unit = runBlocking {
        val id = UUID.randomUUID()
        val stored = OAuthTokens("old", refreshToken = "refresh")
        val rotated = OAuthTokens("new", refreshToken = "rotated")
        val vault = MemoryVault(id to stored)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store = TokenStore(vault, TokenRefresher { _, _ ->
            started.complete(Unit)
            release.await()
            rotated
        })

        val caller = launch { store.forceRefresh(id, AgentProvider.CLAUDE) }
        started.await()
        caller.cancelAndJoin()
        release.complete(Unit)

        withTimeout(2_000) {
            while (vault.load(id) != rotated) delay(10)
        }
        assertEquals(rotated, vault.load(id))
    }

    @Test
    fun revokedRefreshTokenIsInvalidatedLocally(): Unit = runBlocking {
        val id = UUID.randomUUID()
        val stored = OAuthTokens("old", refreshToken = "revoked", plan = "Plus")
        val vault = MemoryVault(id to stored)
        val store = TokenStore(vault, TokenRefresher { _, _ -> throw OAuthException.RefreshRevoked() })

        assertThrows(OAuthException.RefreshRevoked::class.java) {
            runBlocking { store.forceRefresh(id, AgentProvider.CODEX) }
        }
        assertEquals(null, vault.load(id)?.refreshToken)
        assertEquals("old", vault.load(id)?.accessToken)
        assertEquals("Plus", vault.load(id)?.plan)
    }

    // region A1: a late refresh must not touch a newer sign-in's credential

    /** Starts a refresh for the old credential that blocks until [release] completes. */
    private class GatedRefresher(private val outcome: () -> OAuthTokens) : TokenRefresher {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
            private set

        override suspend fun refresh(provider: AgentProvider, tokens: OAuthTokens): OAuthTokens {
            calls += 1
            started.complete(Unit)
            release.await()
            return outcome()
        }
    }

    @Test
    fun lateRefreshSuccessDoesNotOverwriteNewSignIn(): Unit = runBlocking {
        val id = UUID.randomUUID()
        val vault = MemoryVault(id to OAuthTokens("old", refreshToken = "old-refresh", expiresAt = Instant.EPOCH))
        val refresher = GatedRefresher { OAuthTokens("old-rotated", refreshToken = "old-rotated-refresh") }
        val store = TokenStore(vault, refresher, now = { now })
        val newLogin = OAuthTokens("new-login", refreshToken = "new-refresh", expiresAt = now.plusSeconds(3_600))

        val pending = async { store.validTokens(id, AgentProvider.CLAUDE) }
        refresher.started.await()
        store.save(id, newLogin)
        refresher.release.complete(Unit)

        // The caller of the superseded refresh is handed the new credential, not the stale one.
        assertEquals(newLogin, pending.await())
        assertEquals(newLogin, vault.load(id))
    }

    @Test
    fun lateRefreshRevocationDoesNotClearNewSignInRefreshToken(): Unit = runBlocking {
        val id = UUID.randomUUID()
        val vault = MemoryVault(id to OAuthTokens("old", refreshToken = "old-refresh", expiresAt = Instant.EPOCH))
        val refresher = GatedRefresher { throw OAuthException.RefreshRevoked() }
        val store = TokenStore(vault, refresher, now = { now })
        val newLogin = OAuthTokens("new-login", refreshToken = "new-refresh", expiresAt = now.plusSeconds(3_600))

        val pending = async { store.validTokens(id, AgentProvider.CODEX) }
        refresher.started.await()
        store.save(id, newLogin)
        refresher.release.complete(Unit)

        assertEquals(newLogin, pending.await())
        assertEquals("new-refresh", vault.load(id)?.refreshToken)
        assertEquals("new-login", vault.load(id)?.accessToken)
    }

    @Test
    fun lateRefreshDoesNotRecreateDeletedCredential(): Unit = runBlocking {
        val id = UUID.randomUUID()
        val vault = MemoryVault(id to OAuthTokens("old", refreshToken = "old-refresh", expiresAt = Instant.EPOCH))
        val refresher = object : TokenRefresher {
            val started = CompletableDeferred<Unit>()
            override suspend fun refresh(provider: AgentProvider, tokens: OAuthTokens): OAuthTokens {
                started.complete(Unit)
                // Ignores cancellation, like a response that was already on its way back.
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { delay(100) }
                return OAuthTokens("rotated", refreshToken = "rotated-refresh")
            }
        }
        val store = TokenStore(vault, refresher, now = { now })

        val pending = async { runCatching { store.validTokens(id, AgentProvider.CLAUDE) } }
        refresher.started.await()
        store.delete(id)
        pending.await()
        delay(200)

        assertEquals(null, vault.load(id))
    }

    @Test
    fun refreshAfterNewSignInRunsAgainstNewCredential(): Unit = runBlocking {
        val id = UUID.randomUUID()
        val vault = MemoryVault(id to OAuthTokens("old", refreshToken = "old-refresh", expiresAt = Instant.EPOCH))
        val seen = mutableListOf<String?>()
        val oldRelease = CompletableDeferred<Unit>()
        val oldStarted = CompletableDeferred<Unit>()
        val store = TokenStore(vault, TokenRefresher { _, tokens ->
            seen += tokens.refreshToken
            if (tokens.refreshToken == "old-refresh") {
                oldStarted.complete(Unit)
                oldRelease.await()
                OAuthTokens("old-rotated", refreshToken = "old-rotated-refresh")
            } else {
                OAuthTokens("new-rotated", refreshToken = "new-rotated-refresh")
            }
        }, now = { now })

        val old = async { store.validTokens(id, AgentProvider.CLAUDE) }
        oldStarted.await()
        store.save(id, OAuthTokens("new-login", refreshToken = "new-refresh"))
        // A 401 on the new credential must not join the superseded refresh (which would wait on it).
        val forced = try {
            withTimeout(2_000) { store.forceRefresh(id, AgentProvider.CLAUDE) }
        } finally {
            oldRelease.complete(Unit)
        }
        old.await()

        assertEquals("new-rotated", forced.accessToken)
        assertEquals(listOf("old-refresh", "new-refresh"), seen)
        assertEquals("new-rotated-refresh", vault.load(id)?.refreshToken)
    }

    @Test
    fun concurrentRefreshesOfOneAccountCoalesceAndOtherAccountsStayIndependent(): Unit = runBlocking {
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        val vault = MemoryVault(
            first to OAuthTokens("a", refreshToken = "a-refresh", expiresAt = Instant.EPOCH),
            second to OAuthTokens("b", refreshToken = "b-refresh", expiresAt = Instant.EPOCH),
        )
        val release = CompletableDeferred<Unit>()
        val calls = mutableMapOf<String?, Int>()
        val store = TokenStore(vault, TokenRefresher { _, tokens ->
            synchronized(calls) { calls[tokens.refreshToken] = (calls[tokens.refreshToken] ?: 0) + 1 }
            release.await()
            OAuthTokens(tokens.accessToken + "-rotated", refreshToken = tokens.refreshToken + "-2")
        }, now = { now })

        val requests = listOf(first, first, first, second).map { id ->
            async { store.validTokens(id, AgentProvider.CLAUDE) }
        }
        withTimeout(2_000) { while (synchronized(calls) { calls.values.sum() } < 2) delay(10) }
        release.complete(Unit)
        val results = requests.map { it.await() }

        assertEquals(mapOf<String?, Int>("a-refresh" to 1, "b-refresh" to 1), calls)
        assertEquals(listOf("a-rotated", "a-rotated", "a-rotated", "b-rotated"), results.map { it.accessToken })
        assertEquals("a-refresh-2", vault.load(first)?.refreshToken)
        assertEquals("b-refresh-2", vault.load(second)?.refreshToken)
    }

    @Test
    fun planUpdateDuringRefreshDoesNotDiscardRotation(): Unit = runBlocking {
        val id = UUID.randomUUID()
        val vault = MemoryVault(id to OAuthTokens("old", refreshToken = "old-refresh", expiresAt = Instant.EPOCH))
        val refresher = GatedRefresher { OAuthTokens("rotated", refreshToken = "rotated-refresh") }
        val store = TokenStore(vault, refresher, now = { now })

        val pending = async { store.validTokens(id, AgentProvider.CLAUDE) }
        refresher.started.await()
        store.updatePlan(id, "Max")
        refresher.release.complete(Unit)

        // A plan label is metadata, not a new sign-in: the rotated token must still be stored.
        assertEquals("rotated", pending.await().accessToken)
        assertEquals("rotated-refresh", vault.load(id)?.refreshToken)
    }

    // endregion

    @Test
    fun providerRefresherSupportsOnlyRotatingOAuthProviders(): Unit = runBlocking {
        val input = OAuthTokens("old", refreshToken = "refresh")
        val claudeResult = OAuthTokens("claude")
        val codexResult = OAuthTokens("codex")
        val grokResult = OAuthTokens("grok")
        val dispatcher = ProviderTokenRefresher(
            claude = FakeBrowserClient(claudeResult),
            codex = FakeOAuthClient(codexResult),
            grok = FakeBrowserClient(grokResult),
        )

        assertSame(claudeResult, dispatcher.refresh(AgentProvider.CLAUDE, input))
        assertSame(codexResult, dispatcher.refresh(AgentProvider.CODEX, input))
        assertSame(grokResult, dispatcher.refresh(AgentProvider.GROK, input))
        // Device flow and API keys have no refresh; Cursor v1 signs in again instead.
        for (provider in listOf(AgentProvider.COPILOT, AgentProvider.CURSOR, AgentProvider.KIMI)) {
            assertThrows(OAuthException.NotAuthenticated::class.java) {
                runBlocking { dispatcher.refresh(provider, input) }
            }
        }
    }

    private class MemoryVault(vararg initial: Pair<UUID, OAuthTokens>) : CredentialVault {
        private val values = initial.toMap().toMutableMap()

        @Synchronized override fun save(agentId: UUID, tokens: OAuthTokens) {
            values[agentId] = tokens
        }

        @Synchronized override fun load(agentId: UUID): OAuthTokens? = values[agentId]

        @Synchronized override fun delete(agentId: UUID) {
            values.remove(agentId)
        }
    }

    private class FakeBrowserClient(private val refreshResult: OAuthTokens) : BrowserOAuthClient {
        override fun loopbackRedirectUri(port: Int): String = error("unused")
        override fun authorizeUrl(pkce: Pkce, redirect: String): String = error("unused")
        override val manualCodeRedirect: String? = null
        override suspend fun exchange(code: String, state: String, pkce: Pkce, redirect: String): OAuthTokens =
            error("unused")
        override suspend fun refresh(tokens: OAuthTokens): OAuthTokens = refreshResult
    }

    private class FakeOAuthClient(private val refreshResult: OAuthTokens) : OAuthCodeClient {
        override fun authorizeUrl(pkce: Pkce): String = error("unused")
        override fun parseCallback(url: String): OAuthCallback? = error("unused")
        override suspend fun exchange(callback: OAuthCallback, pkce: Pkce): OAuthTokens = error("unused")
        override suspend fun refresh(tokens: OAuthTokens): OAuthTokens = refreshResult
    }
}
