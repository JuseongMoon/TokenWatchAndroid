package com.ScienceFiction.TokenWatchAndroid.network.orchestration

import com.ScienceFiction.TokenWatchAndroid.auth.CredentialVault
import com.ScienceFiction.TokenWatchAndroid.auth.OAuthTokens
import com.ScienceFiction.TokenWatchAndroid.auth.TokenRefresher
import com.ScienceFiction.TokenWatchAndroid.auth.TokenStore
import com.ScienceFiction.TokenWatchAndroid.domain.AgentProvider
import com.ScienceFiction.TokenWatchAndroid.domain.UsageWindow
import com.ScienceFiction.TokenWatchAndroid.domain.WindowKind
import com.ScienceFiction.TokenWatchAndroid.localization.L10n
import com.ScienceFiction.TokenWatchAndroid.localization.Lang
import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkTransport
import com.ScienceFiction.TokenWatchAndroid.network.core.ProviderUsageClient
import com.ScienceFiction.TokenWatchAndroid.network.core.UsageException
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageGatewayTest {
    private val now = Instant.parse("2026-07-11T00:00:00Z")
    private val l10n = L10n(Lang.EN)

    @Test
    fun unauthorizedRefreshesExactlyOnceAndRetriesWithRotatedToken() = runBlocking {
        val id = UUID.randomUUID()
        val original = OAuthTokens("old", refreshToken = "refresh", plan = "old-plan")
        val rotated = OAuthTokens("new", refreshToken = "refresh-2", plan = "pro")
        val vault = MemoryVault(id to original)
        var refreshCount = 0
        val store = TokenStore(
            vault,
            TokenRefresher { provider, tokens ->
                refreshCount += 1
                assertEquals(AgentProvider.CLAUDE, provider)
                assertSame(original, tokens)
                rotated
            },
            now = { now },
        )
        val receivedTokens = mutableListOf<String>()
        val client = ProviderUsageClient { tokens ->
            receivedTokens += tokens.accessToken
            if (receivedTokens.size == 1) throw UsageException.Unauthorized()
            listOf(window())
        }
        val gateway = gateway(client, store, RateLimitGate(now = { now }))

        val snapshot = gateway.fetchSnapshot(AgentProvider.CLAUDE, id)

        assertEquals(listOf("old", "new"), receivedTokens)
        assertEquals(1, refreshCount)
        assertEquals("pro", snapshot.planLabel)
        assertEquals(listOf(window()), snapshot.windows)
        assertNull(snapshot.error)
        assertSame(rotated, vault.load(id))
    }

    @Test
    fun aSecondUnauthorizedIsNotRetriedAndUsesLocalizedEmptySnapshot() = runBlocking {
        val id = UUID.randomUUID()
        val vault = MemoryVault(id to OAuthTokens("old", refreshToken = "refresh"))
        var refreshCount = 0
        val store = TokenStore(
            vault,
            TokenRefresher { _, _ ->
                refreshCount += 1
                OAuthTokens("new", refreshToken = "refresh-2")
            },
            now = { now },
        )
        var fetchCount = 0
        val client = ProviderUsageClient {
            fetchCount += 1
            throw UsageException.Unauthorized()
        }

        val snapshot = gateway(client, store, RateLimitGate(now = { now }))
            .fetchSnapshot(AgentProvider.CLAUDE, id)

        assertEquals(2, fetchCount)
        assertEquals(1, refreshCount)
        assertEquals(emptyList<UsageWindow>(), snapshot.windows)
        assertEquals(l10n.errAuthExpired, snapshot.error)
    }

    @Test
    fun rateLimitKeepsLastGoodGraphWithStaleWarningAndBlocksFurtherNetworkCalls() = runBlocking {
        val id = UUID.randomUUID()
        val store = storeWithApiKey(id)
        var fetchCount = 0
        val client = ProviderUsageClient {
            fetchCount += 1
            if (fetchCount == 1) listOf(window()) else throw UsageException.RateLimited(null)
        }
        val gateway = gateway(client, store, RateLimitGate(now = { now }))

        val successful = gateway.fetchSnapshot(AgentProvider.OPENROUTER, id)
        val rateLimited = gateway.fetchSnapshot(AgentProvider.OPENROUTER, id)
        val stillBlocked = gateway.fetchSnapshot(AgentProvider.OPENROUTER, id)

        listOf(rateLimited, stillBlocked).forEach { stale ->
            assertEquals(successful.windows, stale.windows)
            assertEquals(successful.planLabel, stale.planLabel)
            assertEquals(successful.fetchedAt, stale.fetchedAt)
            assertEquals(l10n.errRateLimitedRetryStale(5), stale.error)
        }
        assertEquals(2, fetchCount)
    }

    @Test
    fun uncachedRateLimitUsesFiveMinuteFallbackAndLocalizedErrors() = runBlocking {
        val id = UUID.randomUUID()
        val store = storeWithApiKey(id)
        var fetchCount = 0
        val client = ProviderUsageClient {
            fetchCount += 1
            throw UsageException.RateLimited(null)
        }
        val gate = RateLimitGate(now = { now })
        val gateway = gateway(client, store, gate)

        val first = gateway.fetchSnapshot(AgentProvider.OPENROUTER, id)
        assertEquals(l10n.errRateLimitedRetry(5), first.error)
        assertTrue(first.windows.isEmpty())
        assertEquals(now.plusSeconds(300), gate.blocked(id))

        val blocked = gateway.fetchSnapshot(AgentProvider.OPENROUTER, id)
        assertEquals(l10n.errRateLimitedRetry(5), blocked.error)
        assertTrue(blocked.windows.isEmpty())
        assertEquals(1, fetchCount)
    }

    @Test
    fun explicitRetryAfterIsShownOnTheFirstRateLimitedSnapshot() = runBlocking {
        val id = UUID.randomUUID()
        val retryAfter = now.plusSeconds(121)
        val client = ProviderUsageClient {
            throw UsageException.RateLimited(retryAfter)
        }

        val snapshot = gateway(client, storeWithApiKey(id), RateLimitGate(now = { now }))
            .fetchSnapshot(AgentProvider.OPENROUTER, id)

        assertEquals(l10n.errRateLimitedRetry(3), snapshot.error)
        assertTrue(snapshot.windows.isEmpty())
    }

    @Test
    fun defaultRegistryMapsEveryProviderToItsConcreteClient() {
        val transport = NetworkTransport { error("No network expected") }
        val registry = ProviderUsageRegistry.create(transport)
        val expectedClassNames = mapOf(
            AgentProvider.CLAUDE to "ClaudeUsageClient",
            AgentProvider.CODEX to "CodexUsageClient",
            AgentProvider.ELEVENLABS to "ElevenLabsUsageClient",
            AgentProvider.COPILOT to "CopilotUsageClient",
            AgentProvider.CURSOR to "CursorUsageClient",
            AgentProvider.OPENROUTER to "OpenRouterUsageClient",
            AgentProvider.DEEPSEEK to "DeepSeekUsageClient",
            AgentProvider.POE to "PoeUsageClient",
            AgentProvider.FAL to "FalUsageClient",
            AgentProvider.STABILITY to "StabilityUsageClient",
            AgentProvider.RECRAFT to "RecraftUsageClient",
            AgentProvider.LUMA to "LumaUsageClient",
            AgentProvider.RUNWAY to "RunwayUsageClient",
            AgentProvider.DID to "DIDUsageClient",
            AgentProvider.HEYGEN to "HeyGenUsageClient",
            AgentProvider.LEONARDO to "LeonardoUsageClient",
            AgentProvider.GROK to "GrokUsageClient",
            AgentProvider.WINDSURF to "WindsurfUsageClient",
        )

        assertEquals(18, AgentProvider.entries.size)
        assertEquals(AgentProvider.entries.toSet(), registry.providers)
        expectedClassNames.forEach { (provider, expectedName) ->
            assertEquals(expectedName, registry.clientFor(provider)::class.java.simpleName)
        }
    }

    private fun gateway(
        client: ProviderUsageClient,
        store: TokenStore,
        gate: RateLimitGate,
    ) = UsageGateway(
        registry = ProviderUsageRegistry(AgentProvider.entries.associateWith { client }),
        tokenStore = store,
        rateLimitGate = gate,
        localization = { l10n },
        now = { now },
    )

    private fun storeWithApiKey(id: UUID): TokenStore = TokenStore(
        vault = MemoryVault(id to OAuthTokens.apiKey("secret")),
        refresher = TokenRefresher { _, _ -> error("API keys cannot refresh") },
        now = { now },
    )

    private fun window() = UsageWindow(
        label = "Current session",
        usedPercent = 25.0,
        resetsAt = now.plusSeconds(3_600),
        kind = WindowKind.SESSION,
    )

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
}
