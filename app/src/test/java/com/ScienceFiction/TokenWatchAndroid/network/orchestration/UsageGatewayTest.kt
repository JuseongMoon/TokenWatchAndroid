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
import com.ScienceFiction.TokenWatchAndroid.network.core.PlanReportingUsageClient
import com.ScienceFiction.TokenWatchAndroid.network.core.ProviderUsage
import com.ScienceFiction.TokenWatchAndroid.network.core.ProviderUsageClient
import com.ScienceFiction.TokenWatchAndroid.network.core.UsageException
import com.ScienceFiction.TokenWatchAndroid.network.providers.subscription.CodexAccountClient
import com.ScienceFiction.TokenWatchAndroid.network.providers.subscription.RecordingTransport
import com.ScienceFiction.TokenWatchAndroid.network.providers.subscription.jsonResponse
import okhttp3.HttpUrl.Companion.toHttpUrl
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

    // region A6: OpenRouter key kind and status meaning

    private fun openRouter(status: Int, body: String) = com.ScienceFiction.TokenWatchAndroid.network.providers.apikey
        .OpenRouterUsageClient(
            NetworkTransport {
                com.ScienceFiction.TokenWatchAndroid.network.core.NetworkResponse(status, emptyMap(), body.toByteArray())
            },
        )

    private fun openRouterSnapshot(
        status: Int,
        body: String,
        provider: AgentProvider = AgentProvider.OPENROUTER,
        outcomes: MutableList<Throwable?> = mutableListOf(),
    ) = runBlocking {
        val id = UUID.randomUUID()
        gateway(
            openRouter(status, body),
            storeWithApiKey(id),
            RateLimitGate(now = { now }),
            onFetchOutcome = { _, _, error -> outcomes += error },
        ).fetchSnapshot(provider, id)
    }

    @Test
    fun openRouterAsksForAManagementKeyWhereTheKeyIsEntered() {
        for (lang in Lang.entries) {
            assertTrue(L10n(lang).apiKeyHint(AgentProvider.OPENROUTER)!!.contains("Management Key"))
        }
        assertEquals("https://openrouter.ai/settings/management-keys", AgentProvider.OPENROUTER.apiKeyUrl)
    }

    @Test
    fun openRouterManagementKeyCreditsAreTheAccountBalance() {
        val snapshot = openRouterSnapshot(200, """{"data":{"total_credits":10.5,"total_usage":3.0}}""")

        assertNull(snapshot.error)
        assertEquals(7.5, snapshot.windows.single().balanceRemaining!!, 0.0)
        assertEquals(10.5, snapshot.windows.single().balanceTotal!!, 0.0)
    }

    @Test
    fun openRouter403ExplainsTheManagementKeyInsteadOfShowingABalance() {
        val outcomes = mutableListOf<Throwable?>()
        val snapshot = openRouterSnapshot(
            403,
            """{"error":{"code":403,"message":"Only management keys can perform this operation"}}""",
            outcomes = outcomes,
        )

        assertEquals(l10n.errOpenRouterKeyPermission, snapshot.error)
        assertTrue(snapshot.windows.isEmpty())
        // The analytics input is unchanged: still an HTTP 403, so its bucket stays HTTP_4XX.
        assertEquals(403, (outcomes.single() as UsageException.Http).statusCode)
    }

    @Test
    fun openRouter401SaysTheKeyWasRejected() {
        val snapshot = openRouterSnapshot(401, """{"error":{"code":401,"message":"API key expired"}}""")

        assertEquals(l10n.errOpenRouterKeyRejected, snapshot.error)
        assertTrue(snapshot.windows.isEmpty())
    }

    @Test
    fun openRouterServerFailureAndRateLimitKeepTheirGenericMeaning() {
        assertEquals(l10n.errHttp(500), openRouterSnapshot(500, "upstream").error)
        assertEquals(l10n.errHttp(503), openRouterSnapshot(503, "").error)
        assertEquals(l10n.errRateLimitedRetry(5), openRouterSnapshot(429, "{}").error)
    }

    @Test
    fun aBodyWithoutCreditsIsNotShownAsZeroCredits() {
        val snapshot = openRouterSnapshot(200, "{}")

        assertTrue(snapshot.error!!.isNotEmpty())
        assertTrue(snapshot.windows.isEmpty())
    }

    @Test
    fun the403WordingIsOpenRouterSpecific() {
        // Same response for another API-key provider keeps the generic HTTP message.
        assertEquals(l10n.errHttp(403), openRouterSnapshot(403, "{}", provider = AgentProvider.DEEPSEEK).error)
    }

    // endregion

    @Test
    fun copilotAccountWithoutQuotaShowsNoUsageInsteadOfAnEmptyGauge() = runBlocking {
        val id = UUID.randomUUID()
        val client = com.ScienceFiction.TokenWatchAndroid.network.providers.subscription.CopilotUsageClient(
            NetworkTransport {
                com.ScienceFiction.TokenWatchAndroid.network.core.NetworkResponse(
                    200,
                    emptyMap(),
                    """{"copilot_plan":"individual"}""".toByteArray(),
                )
            },
        )
        val snapshot = gateway(client, storeWithApiKey(id), RateLimitGate(now = { now }))
            .fetchSnapshot(AgentProvider.COPILOT, id)

        assertEquals(l10n.errNoWindows, snapshot.error)
        assertTrue(snapshot.windows.isEmpty())
    }

    @Test
    fun aNetworkFailureIsShownAsANetworkMessageNotItsRawText() = runBlocking {
        val id = UUID.randomUUID()
        val client = ProviderUsageClient { throw java.net.UnknownHostException("Unable to resolve host \"api.github.com\"") }
        val snapshot = gateway(client, storeWithApiKey(id), RateLimitGate(now = { now }))
            .fetchSnapshot(AgentProvider.COPILOT, id)

        assertEquals(l10n.errNetwork, snapshot.error)
    }

    @Test
    fun defaultRegistryMapsEveryProviderToItsConcreteClient() {
        val transport = NetworkTransport { error("No network expected") }
        val registry = ProviderUsageRegistry.create(transport)
        val expectedClassNames = mapOf(
            AgentProvider.CLAUDE to "ClaudeUsageClient",
            AgentProvider.CODEX to "CodexUsageClient",
            AgentProvider.COPILOT to "CopilotUsageClient",
            AgentProvider.GROK to "GrokUsageClient",
            AgentProvider.CURSOR to "CursorUsageClient",
            AgentProvider.KIMI to "KimiUsageClient",
            AgentProvider.OPENROUTER to "OpenRouterUsageClient",
            AgentProvider.DEEPSEEK to "DeepSeekUsageClient",
            AgentProvider.POE to "PoeUsageClient",
            AgentProvider.ELEVENLABS to "ElevenLabsUsageClient",
        )

        assertEquals(10, AgentProvider.entries.size)
        assertEquals(AgentProvider.entries.toSet(), registry.providers)
        expectedClassNames.forEach { (provider, expectedName) ->
            assertEquals(expectedName, registry.clientFor(provider)::class.java.simpleName)
        }
    }

    /** Grok/Cursor carry the plan in the usage response; it is stored on every fetch, manual or not. */
    @Test
    fun planCarriedByTheUsageResponseIsPersistedOnEveryFetch() = runBlocking {
        val id = UUID.randomUUID()
        val vault = MemoryVault(id to OAuthTokens("token", plan = "Old"))
        val store = TokenStore(vault, TokenRefresher { _, _ -> error("unused") }, now = { now })
        val client = object : PlanReportingUsageClient {
            override suspend fun fetchUsage(tokens: OAuthTokens) = ProviderUsage(listOf(window()), "SuperGrok")
        }

        val snapshot = gateway(client, store, RateLimitGate(now = { now })).fetchSnapshot(AgentProvider.GROK, id)

        assertEquals("SuperGrok", snapshot.planLabel)
        assertEquals("SuperGrok", vault.load(id)?.plan)
    }

    @Test
    fun manualCodexRefreshReadsAndPersistsLivePlanWhileAutomaticRefreshDoesNot() = runBlocking {
        val id = UUID.randomUUID()
        val vault = MemoryVault(id to OAuthTokens("token", plan = "Pro", accountId = "acct_1"))
        val store = TokenStore(
            vault = vault,
            refresher = TokenRefresher { _, _ -> error("refresh not expected") },
            now = { now },
        )
        val planTransport = RecordingTransport(
            jsonResponse(
                """{"accounts":{"acct_1":{"account":{"plan_type":"free"}}}}""",
            ),
        )
        val codexAccountClient = CodexAccountClient(
            planTransport,
            "https://example.com/accounts/check".toHttpUrl(),
        )
        val gateway = gateway(
            client = ProviderUsageClient { listOf(window()) },
            store = store,
            gate = RateLimitGate(now = { now }),
            codexAccountClient = codexAccountClient,
        )

        assertEquals("Pro", gateway.fetchSnapshot(AgentProvider.CODEX, id).planLabel)
        assertTrue(planTransport.requests.isEmpty())

        assertEquals(
            "Free",
            gateway.fetchSnapshot(AgentProvider.CODEX, id, manual = true).planLabel,
        )
        assertEquals("Free", vault.load(id)?.plan)
        assertEquals(1, planTransport.requests.size)
    }

    @Test
    fun fetchOutcomeIsReportedOncePerFetchWithTheRawFailure() = runBlocking {
        val id = UUID.randomUUID()
        val outcomes = mutableListOf<Throwable?>()
        var fail = true
        val client = ProviderUsageClient {
            if (fail) throw UsageException.Http(503, "upstream detail") else listOf(window())
        }
        val gateway = gateway(
            client = client,
            store = storeWithApiKey(id),
            gate = RateLimitGate(now = { now }),
        ) { _, _, error -> outcomes += error }

        gateway.fetchSnapshot(AgentProvider.OPENROUTER, id)
        fail = false
        gateway.fetchSnapshot(AgentProvider.OPENROUTER, id)

        // One report per fetch, carrying the exception itself — classification belongs to the caller.
        assertEquals(2, outcomes.size)
        assertTrue(outcomes[0] is UsageException.Http)
        assertNull(outcomes[1])
    }

    private fun gateway(
        client: ProviderUsageClient,
        store: TokenStore,
        gate: RateLimitGate,
        codexAccountClient: CodexAccountClient = CodexAccountClient(
            NetworkTransport { error("Codex plan lookup not expected") },
        ),
        onFetchOutcome: (AgentProvider, UUID, Throwable?) -> Unit = { _, _, _ -> },
    ) = UsageGateway(
        registry = ProviderUsageRegistry(AgentProvider.entries.associateWith { client }),
        tokenStore = store,
        rateLimitGate = gate,
        codexAccountClient = codexAccountClient,
        localization = { l10n },
        now = { now },
        onFetchOutcome = onFetchOutcome,
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
