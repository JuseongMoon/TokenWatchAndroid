package com.ScienceFiction.TokenWatchAndroid.store

import com.ScienceFiction.TokenWatchAndroid.auth.CredentialUnavailableException
import com.ScienceFiction.TokenWatchAndroid.auth.CredentialVault
import com.ScienceFiction.TokenWatchAndroid.auth.OAuthTokens
import com.ScienceFiction.TokenWatchAndroid.auth.TokenRefresher
import com.ScienceFiction.TokenWatchAndroid.auth.TokenStore
import com.ScienceFiction.TokenWatchAndroid.auth.oauth.OAuthException
import com.ScienceFiction.TokenWatchAndroid.data.AppSettings
import com.ScienceFiction.TokenWatchAndroid.domain.Agent
import com.ScienceFiction.TokenWatchAndroid.domain.AgentProvider
import com.ScienceFiction.TokenWatchAndroid.domain.AgentSnapshot
import com.ScienceFiction.TokenWatchAndroid.domain.UsageStyle
import com.ScienceFiction.TokenWatchAndroid.domain.UsageWindow
import com.ScienceFiction.TokenWatchAndroid.domain.WindowKind
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A1/A4: a sign-in that replaces an account's credential while an older refresh is in flight, and
 * A3: Copilot re-login identification. The real [TokenStore] is wired in; only the vault and the
 * provider responses are fakes.
 */
class AgentStoreReloginTest {
    private val now = Instant.parse("2026-07-11T00:00:00Z")

    // region A1 / A4

    @Test
    fun `late refresh success during re-login keeps the new credential and publishes only its result`() =
        reloginDuringRefresh(oldRefreshRevoked = false)

    @Test
    fun `late refresh revocation during re-login keeps the new refresh token and publishes only its result`() =
        reloginDuringRefresh(oldRefreshRevoked = true)

    private fun reloginDuringRefresh(oldRefreshRevoked: Boolean) = runBlocking {
        val email = "synthetic@example.invalid"
        val agent = Agent(AgentProvider.CLAUDE, accountLabel = email)
        val refreshStarted = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        val vault = MemoryVault()
        val tokenStore = TokenStore(vault, TokenRefresher { _, _ ->
            refreshStarted.complete(Unit)
            releaseRefresh.await()
            if (oldRefreshRevoked) throw OAuthException.RefreshRevoked()
            OAuthTokens("old-rotated", "old-rotated-refresh", now.plusSeconds(3_600), accountEmail = email)
        }, now = { now })
        tokenStore.save(agent.id, OAuthTokens("old", "old-refresh", Instant.EPOCH, accountEmail = email))

        val fetches = AtomicInteger(0)
        val followUpStarted = CompletableDeferred<Unit>()
        val releaseFollowUp = CompletableDeferred<Unit>()
        val store = store(listOf(agent), tokenStore) { provider, id ->
            if (fetches.incrementAndGet() == 2) {
                followUpStarted.complete(Unit)
                releaseFollowUp.await()
            }
            usingStoredCredential(tokenStore, provider, id)
        }
        store.awaitInitialLoad()

        val prior = async { store.refresh(agent) }
        refreshStarted.await()
        val newLogin = OAuthTokens("new-login", "new-refresh", now.plusSeconds(3_600), accountEmail = email)
        val result = withTimeout(2_000) { store.addAgent(AgentProvider.CLAUDE, newLogin) }
        assertTrue(result.replacedExisting)
        assertEquals(agent.id, result.agent.id)

        releaseRefresh.complete(Unit)
        prior.await()
        withTimeout(2_000) { followUpStarted.await() }

        // The request that began with the replaced credential published nothing.
        assertNull(store.snapshots.value[agent.id])
        assertEquals(newLogin, tokenStore.tokens(agent.id))

        releaseFollowUp.complete(Unit)
        withTimeout(2_000) {
            while (store.snapshots.value[agent.id]?.planLabel != "new-login") delay(10)
        }
        assertNull(store.snapshots.value.getValue(agent.id).error)
        assertEquals(newLogin, tokenStore.tokens(agent.id))
        withTimeout(2_000) { while (agent.id in store.loadingIDs.value) delay(10) }
        assertEquals(2, fetches.get())
        store.close()
    }

    @Test
    fun `stale error from the replaced credential never overwrites the new sign-in`() = runBlocking {
        val email = "synthetic@example.invalid"
        val agent = Agent(AgentProvider.CODEX, accountLabel = email)
        val staleEntered = CompletableDeferred<Unit>()
        val releaseStale = CompletableDeferred<Unit>()
        val fetches = AtomicInteger(0)
        val store = store(listOf(agent)) { _, _ ->
            if (fetches.incrementAndGet() == 1) {
                staleEntered.complete(Unit)
                releaseStale.await()
                errorSnapshot("Authentication expired")
            } else {
                windowSnapshot(plan = "fresh")
            }
        }
        store.awaitInitialLoad()

        val prior = async { store.refresh(agent) }
        staleEntered.await()
        store.addAgent(AgentProvider.CODEX, OAuthTokens("new", "new-refresh", accountEmail = email))
        releaseStale.complete(Unit)
        prior.await()

        withTimeout(2_000) { while (store.snapshots.value[agent.id]?.planLabel != "fresh") delay(10) }
        assertNull(store.snapshots.value.getValue(agent.id).error)
        assertEquals(2, fetches.get())
        store.close()
    }

    @Test
    fun `removing the account before the stale refresh ends schedules no follow-up`() = runBlocking {
        val email = "synthetic@example.invalid"
        val agent = Agent(AgentProvider.CLAUDE, accountLabel = email)
        val staleEntered = CompletableDeferred<Unit>()
        val releaseStale = CompletableDeferred<Unit>()
        val fetches = AtomicInteger(0)
        val store = store(listOf(agent)) { _, _ ->
            fetches.incrementAndGet()
            staleEntered.complete(Unit)
            releaseStale.await()
            windowSnapshot(plan = "stale")
        }
        store.awaitInitialLoad()

        val prior = async { store.refresh(agent) }
        staleEntered.await()
        store.addAgent(AgentProvider.CLAUDE, OAuthTokens("new", accountEmail = email))
        store.remove(agent)
        releaseStale.complete(Unit)
        prior.await()
        delay(100)

        assertEquals(1, fetches.get())
        assertNull(store.snapshots.value[agent.id])
        assertTrue(store.agents.value.isEmpty())
        store.close()
    }

    @Test
    fun `refresh without a re-login is applied once and not repeated`() = runBlocking {
        val agent = Agent(AgentProvider.CLAUDE, accountLabel = "synthetic@example.invalid")
        val fetches = AtomicInteger(0)
        val store = store(listOf(agent)) { _, _ ->
            fetches.incrementAndGet()
            windowSnapshot(plan = "only")
        }
        store.awaitInitialLoad()

        store.refresh(agent)
        delay(100)

        assertEquals("only", store.snapshots.value.getValue(agent.id).planLabel)
        assertEquals(1, fetches.get())
        store.close()
    }

    // endregion

    // region A3 Copilot

    @Test
    fun `copilot re-login with the same GitHub id replaces the existing card`() = runBlocking {
        val existing = Agent(AgentProvider.COPILOT, accountLabel = "custom")
        val vault = MemoryVault()
        val tokenStore = TokenStore(vault, TokenRefresher { _, _ -> error("unused") })
        tokenStore.save(existing.id, OAuthTokens.session("old-token", accountId = "1001"))
        val store = store(listOf(existing), tokenStore)
        store.awaitInitialLoad()

        val result = store.addAgent(AgentProvider.COPILOT, OAuthTokens.session("new-token", accountId = "1001"))

        assertTrue(result.replacedExisting)
        assertEquals(existing.id, result.agent.id)
        assertEquals(listOf(existing), store.agents.value)
        assertEquals("new-token", tokenStore.tokens(existing.id)?.accessToken)
        store.close()
    }

    @Test
    fun `copilot sign-in with a different GitHub id becomes a separate card`() = runBlocking {
        val existing = Agent(AgentProvider.COPILOT)
        val tokenStore = TokenStore(MemoryVault(), TokenRefresher { _, _ -> error("unused") })
        tokenStore.save(existing.id, OAuthTokens.session("first-token", accountId = "1001"))
        val store = store(listOf(existing), tokenStore)
        store.awaitInitialLoad()

        val result = store.addAgent(AgentProvider.COPILOT, OAuthTokens.session("second-token", accountId = "2002"))

        assertFalse(result.replacedExisting)
        assertEquals(2, store.agents.value.size)
        assertEquals("first-token", tokenStore.tokens(existing.id)?.accessToken)
        store.close()
    }

    @Test
    fun `legacy copilot card is merged only when its own token proves the same id`() = runBlocking {
        val legacy = Agent(AgentProvider.COPILOT)
        val tokenStore = TokenStore(MemoryVault(), TokenRefresher { _, _ -> error("unused") })
        tokenStore.save(legacy.id, OAuthTokens.session("legacy-token"))
        val resolved = mutableListOf<String>()
        val store = store(listOf(legacy), tokenStore, resolveAccountId = { provider, tokens ->
            assertEquals(AgentProvider.COPILOT, provider)
            resolved += tokens.accessToken
            "1001"
        })
        store.awaitInitialLoad()

        val result = store.addAgent(AgentProvider.COPILOT, OAuthTokens.session("new-token", accountId = "1001"))

        assertTrue(result.replacedExisting)
        assertEquals(legacy.id, result.agent.id)
        assertEquals(listOf("legacy-token"), resolved)
        // The id is recorded now, so the next re-login matches without a lookup.
        assertEquals("1001", tokenStore.tokens(legacy.id)?.accountId)
        store.close()
    }

    @Test
    fun `legacy copilot card that cannot be identified is never merged or touched`() = runBlocking {
        val legacy = Agent(AgentProvider.COPILOT, accountLabel = "legacy")
        val tokenStore = TokenStore(MemoryVault(), TokenRefresher { _, _ -> error("unused") })
        tokenStore.save(legacy.id, OAuthTokens.session("revoked-legacy-token"))
        for (resolver in listOf<suspend (AgentProvider, OAuthTokens) -> String?>(
            { _, _ -> null },
            { _, _ -> throw java.io.IOException("offline") },
            { _, _ -> "9999" },
        )) {
            val store = store(listOf(legacy), tokenStore, resolveAccountId = resolver)
            store.awaitInitialLoad()

            val result = store.addAgent(AgentProvider.COPILOT, OAuthTokens.session("new-token", accountId = "1001"))

            assertFalse(result.replacedExisting)
            assertEquals(2, store.agents.value.size)
            assertEquals(legacy, store.agents.value.first())
            assertEquals("revoked-legacy-token", tokenStore.tokens(legacy.id)?.accessToken)
            store.close()
        }
    }

    @Test
    fun `copilot sign-in whose id lookup failed is not merged by guess`() = runBlocking {
        val existing = Agent(AgentProvider.COPILOT)
        val tokenStore = TokenStore(MemoryVault(), TokenRefresher { _, _ -> error("unused") })
        tokenStore.save(existing.id, OAuthTokens.session("token", accountId = "1001"))
        var lookups = 0
        val store = store(listOf(existing), tokenStore, resolveAccountId = { _, _ ->
            lookups += 1
            "1001"
        })
        store.awaitInitialLoad()

        val result = store.addAgent(AgentProvider.COPILOT, OAuthTokens.session("new-token"))

        assertFalse(result.replacedExisting)
        assertEquals(0, lookups)
        assertEquals("token", tokenStore.tokens(existing.id)?.accessToken)
        store.close()
    }

    /**
     * iOS `existingAgent(for:tokens:)`: a Copilot card whose credential cannot be read cannot prove
     * which account it is, so it is skipped. It never blocks the sign-in and is never touched.
     */
    @Test
    fun `copilot sign-in with another account adds a new card while an existing card cannot be read`() =
        runBlocking {
            for (reason in UNREADABLE_REASONS) {
                val existing = Agent(AgentProvider.COPILOT, accountLabel = "github")
                val vault = MemoryVault()
                val tokenStore = TokenStore(vault, TokenRefresher { _, _ -> error("unused") })
                tokenStore.save(existing.id, OAuthTokens.session("old-token", accountId = "1001"))
                vault.unreadable += existing.id
                vault.unreadableReason = reason
                val store = store(listOf(existing), tokenStore)
                store.awaitInitialLoad()

                val result = store.addAgent(AgentProvider.COPILOT, OAuthTokens.session("new-token", accountId = "2002"))

                assertFalse("$reason", result.replacedExisting)
                assertEquals("$reason", listOf(existing, result.agent), store.agents.value)
                assertEquals("$reason", "new-token", tokenStore.tokens(result.agent.id)?.accessToken)
                vault.unreadable.clear()
                assertEquals("$reason", "old-token", tokenStore.tokens(existing.id)?.accessToken)
                store.close()
            }
        }

    /**
     * Even the unreadable card's own account is not merged on a guess (iOS skips it too): the
     * sign-in becomes a new card and the old card stays until the user removes it.
     */
    @Test
    fun `copilot sign-in matching only an unreadable card becomes a new card and keeps the old one`() =
        runBlocking {
            for (reason in UNREADABLE_REASONS) {
                val existing = Agent(AgentProvider.COPILOT, accountLabel = "github")
                val vault = MemoryVault()
                val tokenStore = TokenStore(vault, TokenRefresher { _, _ -> error("unused") })
                tokenStore.save(existing.id, OAuthTokens.session("old-token", accountId = "1001"))
                vault.unreadable += existing.id
                vault.unreadableReason = reason
                val store = store(listOf(existing), tokenStore)
                store.awaitInitialLoad()

                val result = store.addAgent(AgentProvider.COPILOT, OAuthTokens.session("new-token", accountId = "1001"))

                assertFalse("$reason", result.replacedExisting)
                assertEquals("$reason", 2, store.agents.value.size)
                assertTrue("$reason", existing in store.agents.value)
                vault.unreadable.clear()
                assertEquals("$reason", "old-token", tokenStore.tokens(existing.id)?.accessToken)
                store.close()
            }
        }

    @Test
    fun `a readable copilot card that matches is replaced even when another cannot be read`() = runBlocking {
        for (reason in UNREADABLE_REASONS) {
            val unreadable = Agent(AgentProvider.COPILOT, accountLabel = "first")
            val matching = Agent(AgentProvider.COPILOT, accountLabel = "second")
            val vault = MemoryVault()
            val tokenStore = TokenStore(vault, TokenRefresher { _, _ -> error("unused") })
            tokenStore.save(unreadable.id, OAuthTokens.session("first-token", accountId = "2002"))
            tokenStore.save(matching.id, OAuthTokens.session("second-token", accountId = "1001"))
            vault.unreadable += unreadable.id
            vault.unreadableReason = reason
            val store = store(listOf(unreadable, matching), tokenStore)
            store.awaitInitialLoad()

            val result = store.addAgent(AgentProvider.COPILOT, OAuthTokens.session("new-token", accountId = "1001"))

            assertTrue("$reason", result.replacedExisting)
            assertEquals("$reason", matching.id, result.agent.id)
            assertEquals("$reason", listOf(unreadable, matching), store.agents.value)
            assertEquals("$reason", "new-token", tokenStore.tokens(matching.id)?.accessToken)
            vault.unreadable.clear()
            assertEquals("$reason", "first-token", tokenStore.tokens(unreadable.id)?.accessToken)
            store.close()
        }
    }

    @Test
    fun `legacy copilot card is still identified when another card cannot be read`() = runBlocking {
        for (reason in UNREADABLE_REASONS) {
            val unreadable = Agent(AgentProvider.COPILOT, accountLabel = "first")
            val legacy = Agent(AgentProvider.COPILOT, accountLabel = "legacy")
            val vault = MemoryVault()
            val tokenStore = TokenStore(vault, TokenRefresher { _, _ -> error("unused") })
            tokenStore.save(unreadable.id, OAuthTokens.session("first-token", accountId = "2002"))
            tokenStore.save(legacy.id, OAuthTokens.session("legacy-token"))
            vault.unreadable += unreadable.id
            vault.unreadableReason = reason
            val resolved = mutableListOf<String>()
            val store = store(listOf(unreadable, legacy), tokenStore, resolveAccountId = { _, tokens ->
                resolved += tokens.accessToken
                "1001"
            })
            store.awaitInitialLoad()

            val result = store.addAgent(AgentProvider.COPILOT, OAuthTokens.session("new-token", accountId = "1001"))

            assertTrue("$reason", result.replacedExisting)
            assertEquals("$reason", legacy.id, result.agent.id)
            assertEquals("$reason", listOf(unreadable, legacy), store.agents.value)
            // Only the readable legacy card's own token is looked up; the unreadable card is skipped.
            assertEquals("$reason", listOf("legacy-token"), resolved)
            assertEquals("$reason", "1001", tokenStore.tokens(legacy.id)?.accountId)
            store.close()
        }
    }

    /** The detail screen asks for account details; an unreadable credential shows as unknown. */
    @Test
    fun `account details of an unreadable credential are unknown rather than a crash`() = runBlocking {
        val agent = Agent(AgentProvider.CLAUDE, accountLabel = "me@example.com")
        val vault = MemoryVault()
        val tokenStore = TokenStore(vault, TokenRefresher { _, _ -> error("unused") })
        tokenStore.save(agent.id, OAuthTokens("access", accountEmail = "me@example.com", plan = "Org"))
        vault.unreadable += agent.id
        val store = store(listOf(agent), tokenStore)
        store.awaitInitialLoad()

        assertNull(store.accountInfo(agent))
        vault.unreadable.clear()
        assertEquals("Org", store.accountInfo(agent)?.plan)
        store.close()
    }

    // endregion

    private fun store(
        agents: List<Agent>,
        tokenStore: TokenStore = TokenStore(MemoryVault(), TokenRefresher { _, _ -> error("unused") }),
        resolveAccountId: suspend (AgentProvider, OAuthTokens) -> String? = { _, _ -> null },
        fetchSnapshot: suspend (AgentProvider, UUID) -> AgentSnapshot = { _, _ -> windowSnapshot(plan = null) },
    ): AgentStore {
        val agentFlow = MutableStateFlow(agents)
        // Independent of the test's runBlocking scope, so a failed assertion cannot leave the
        // test waiting on the store's long-lived collectors.
        return AgentStore(
            parentScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            dependencies = AgentStoreDependencies(
                agentUpdates = agentFlow,
                creditPeakUpdates = flowOf(emptyMap()),
                settingsUpdates = flowOf(AppSettings()),
                persistAgents = { agentFlow.value = it },
                persistCreditPeaks = {},
                persistSettings = {},
                saveTokens = tokenStore::save,
                loadTokens = tokenStore::tokens,
                deleteTokens = tokenStore::delete,
                fetchSnapshot = fetchSnapshot,
                fetchStatus = { null },
                resolveAccountId = resolveAccountId,
            ),
            now = { now },
        )
    }

    /** Mirrors the gateway: a usable credential yields usage tagged with the token it used. */
    private suspend fun usingStoredCredential(
        tokenStore: TokenStore,
        provider: AgentProvider,
        id: UUID,
    ): AgentSnapshot = try {
        windowSnapshot(plan = tokenStore.validTokens(id, provider).accessToken)
    } catch (error: OAuthException) {
        errorSnapshot(error::class.java.simpleName)
    }

    private fun windowSnapshot(plan: String?) = AgentSnapshot(
        windows = listOf(
            UsageWindow(
                label = "session",
                usedPercent = 10.0,
                resetsAt = null,
                kind = WindowKind.SESSION,
                style = UsageStyle.GAUGE,
            ),
        ),
        planLabel = plan,
        fetchedAt = now,
        error = null,
    )

    private fun errorSnapshot(message: String) = AgentSnapshot(
        windows = emptyList(),
        planLabel = null,
        fetchedAt = now,
        error = message,
    )

    private companion object {
        /** A transient keystore failure and a key that is gone for good (I003). */
        val UNREADABLE_REASONS = listOf(
            CredentialUnavailableException.Reason.KEYSTORE_UNAVAILABLE,
            CredentialUnavailableException.Reason.KEY_MISSING,
        )
    }

    private class MemoryVault : CredentialVault {
        private val values = mutableMapOf<UUID, OAuthTokens>()

        /** Accounts whose stored credential cannot be read right now. */
        val unreadable: MutableSet<UUID> = java.util.Collections.synchronizedSet(mutableSetOf())

        /** Why [unreadable] accounts cannot be read. */
        @Volatile var unreadableReason = CredentialUnavailableException.Reason.KEYSTORE_UNAVAILABLE

        @Synchronized override fun save(agentId: UUID, tokens: OAuthTokens) {
            values[agentId] = tokens
        }

        @Synchronized override fun load(agentId: UUID): OAuthTokens? {
            if (agentId in unreadable) {
                throw CredentialUnavailableException(unreadableReason)
            }
            return values[agentId]
        }

        @Synchronized override fun delete(agentId: UUID) {
            values.remove(agentId)
        }
    }
}
