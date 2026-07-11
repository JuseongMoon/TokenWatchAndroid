package com.ScienceFiction.TokenWatchAndroid.store

import com.ScienceFiction.TokenWatchAndroid.auth.OAuthTokens
import com.ScienceFiction.TokenWatchAndroid.data.AppSettings
import com.ScienceFiction.TokenWatchAndroid.domain.Agent
import com.ScienceFiction.TokenWatchAndroid.domain.AgentProvider
import com.ScienceFiction.TokenWatchAndroid.domain.AgentSnapshot
import com.ScienceFiction.TokenWatchAndroid.domain.ServiceHealth
import com.ScienceFiction.TokenWatchAndroid.domain.UsageStyle
import com.ScienceFiction.TokenWatchAndroid.domain.UsageWindow
import com.ScienceFiction.TokenWatchAndroid.domain.WindowKind
import com.ScienceFiction.TokenWatchAndroid.domain.refresh.AutoRefreshPolicy
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentStoreTest {

    @Test
    fun `reorder waits for asynchronous initial agents and persists only valid moves`() = runBlocking {
        val updates = MutableSharedFlow<List<Agent>>()
        val one = Agent(AgentProvider.CLAUDE, UUID.fromString("00000000-0000-0000-0000-000000000001"))
        val two = Agent(AgentProvider.CODEX, UUID.fromString("00000000-0000-0000-0000-000000000002"))
        val three = Agent(AgentProvider.GROK, UUID.fromString("00000000-0000-0000-0000-000000000003"))
        val persisted = mutableListOf<List<Agent>>()
        val fixture = fixture(
            parentScope = this,
            agentUpdates = updates,
            persistAgents = { persisted += it },
        )

        val pendingMove = async { fixture.store.moveDown(one) }
        delay(10)
        assertFalse(pendingMove.isCompleted)
        updates.emit(listOf(one, two, three))

        assertTrue(pendingMove.await())
        assertEquals(listOf(two.id, one.id, three.id), fixture.store.agents.value.map(Agent::id))
        assertEquals(fixture.store.agents.value, persisted.single())
        assertFalse(fixture.store.moveUp(two))
        assertEquals(1, persisted.size)
        fixture.close()
    }

    @Test
    fun `refresh preserves last-good windows and enriches an empty account label from plan`() = runBlocking {
        val fetchedAt = Instant.parse("2026-07-11T00:00:00Z")
        val clock = AtomicReference(fetchedAt)
        val agent = Agent(AgentProvider.GROK, accountLabel = "")
        val responses = ArrayDeque(
            listOf(
                snapshot(percent = 24.0, plan = "Pro", fetchedAt = fetchedAt),
                AgentSnapshot(
                    windows = emptyList(),
                    planLabel = null,
                    fetchedAt = fetchedAt.plusSeconds(60),
                    error = "network down",
                ),
            ),
        )
        val persisted = mutableListOf<List<Agent>>()
        val fixture = fixture(
            parentScope = this,
            initialAgents = listOf(agent),
            persistAgents = { persisted += it },
            now = clock::get,
            fetchSnapshot = { _, _ -> responses.removeFirst() },
        )
        fixture.store.awaitInitialLoad()

        fixture.store.refresh(agent)
        assertEquals("Pro", fixture.store.agents.value.single().accountLabel)
        assertEquals("Pro", persisted.single().single().accountLabel)

        clock.set(fetchedAt.plusSeconds(20))
        fixture.store.refresh(agent)
        val retained = fixture.store.snapshots.value.getValue(agent.id)
        assertEquals(24.0, retained.windows.single().usedPercent, 0.0)
        assertEquals("Pro", retained.planLabel)
        assertEquals(fetchedAt, retained.fetchedAt)
        assertEquals("network down", retained.error)
        fixture.close()
    }

    @Test
    fun `refreshAll starts provider fetches in parallel and reports all loading IDs`() = runBlocking {
        val one = Agent(AgentProvider.GROK)
        val two = Agent(AgentProvider.GROK)
        val started = AtomicInteger(0)
        val bothStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fixture = fixture(
            parentScope = this,
            initialAgents = listOf(one, two),
            fetchSnapshot = { _, _ ->
                if (started.incrementAndGet() == 2) bothStarted.complete(Unit)
                bothStarted.await()
                release.await()
                snapshot(percent = 1.0)
            },
        )
        fixture.store.awaitInitialLoad()

        val refresh = async { fixture.store.refreshAll() }
        withTimeout(2_000) { bothStarted.await() }
        assertEquals(setOf(one.id, two.id), fixture.store.loadingIDs.value)
        release.complete(Unit)
        refresh.await()
        assertTrue(fixture.store.loadingIDs.value.isEmpty())
        assertEquals(2, fixture.store.snapshots.value.size)
        fixture.close()
    }

    @Test
    fun `concurrent refreshes for the same agent share one in-flight fetch`() = runBlocking {
        val agent = Agent(AgentProvider.GROK)
        val fetches = AtomicInteger(0)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fixture = fixture(
            parentScope = this,
            initialAgents = listOf(agent),
            fetchSnapshot = { _, _ ->
                fetches.incrementAndGet()
                entered.complete(Unit)
                release.await()
                snapshot(percent = 7.0)
            },
        )
        fixture.store.awaitInitialLoad()

        val first = async { fixture.store.refresh(agent) }
        withTimeout(2_000) { entered.await() }
        val duplicate = async { fixture.store.refresh(agent) }
        withTimeout(2_000) { duplicate.await() }

        assertEquals(1, fetches.get())
        assertEquals(setOf(agent.id), fixture.store.loadingIDs.value)
        release.complete(Unit)
        first.await()
        assertTrue(fixture.store.loadingIDs.value.isEmpty())
        assertEquals(7.0, fixture.store.snapshots.value.getValue(agent.id).windows.single().usedPercent, 0.0)
        fixture.close()
    }

    @Test
    fun `failed fetch start blocks at 19 seconds and exact 20 seconds is allowed`() = runBlocking {
        val start = Instant.parse("2026-07-11T00:00:00Z")
        val clock = AtomicReference(start)
        val agent = Agent(AgentProvider.GROK)
        val fetches = AtomicInteger(0)
        val fixture = fixture(
            parentScope = this,
            initialAgents = listOf(agent),
            now = clock::get,
            fetchSnapshot = { _, _ ->
                val count = fetches.incrementAndGet()
                if (count == 1) error("expected failure")
                snapshot(percent = count.toDouble())
            },
        )
        fixture.store.awaitInitialLoad()

        assertTrue(runCatching { fixture.store.refresh(agent) }.isFailure)
        assertTrue(fixture.store.loadingIDs.value.isEmpty())

        clock.set(start.plusMillis(19_999))
        fixture.store.refresh(agent)
        assertEquals(1, fetches.get())

        clock.set(start.plusSeconds(20))
        fixture.store.refresh(agent)
        assertEquals(2, fetches.get())
        assertEquals(2.0, fixture.store.snapshots.value.getValue(agent.id).windows.single().usedPercent, 0.0)
        fixture.close()
    }

    @Test
    fun `service status joins an in-flight request and enforces provider 60 second throttle`() = runBlocking {
        val clock = AtomicReference(Instant.parse("2026-07-11T00:00:00Z"))
        val fetches = AtomicInteger(0)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fixture = fixture(
            parentScope = this,
            now = clock::get,
            fetchStatus = {
                fetches.incrementAndGet()
                entered.complete(Unit)
                release.await()
                ServiceHealth.OPERATIONAL
            },
        )
        fixture.store.awaitInitialLoad()

        val first = async { fixture.store.refreshStatus(AgentProvider.CLAUDE) }
        withTimeout(2_000) { entered.await() }
        val second = async { fixture.store.refreshStatus(AgentProvider.CLAUDE) }
        delay(10)
        assertEquals(1, fetches.get())
        release.complete(Unit)
        assertEquals(ServiceHealth.OPERATIONAL, first.await())
        assertEquals(ServiceHealth.OPERATIONAL, second.await())

        clock.set(clock.get().plusSeconds(59))
        assertEquals(ServiceHealth.OPERATIONAL, fixture.store.refreshStatus(AgentProvider.CLAUDE))
        assertEquals(1, fetches.get())
        clock.set(clock.get().plusSeconds(1))
        fixture.store.refreshStatus(AgentProvider.CLAUDE)
        assertEquals(2, fetches.get())
        fixture.close()
    }

    @Test
    fun `cancelling the first status waiter does not cancel shared store-owned fetch`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fetches = AtomicInteger(0)
        val fixture = fixture(
            parentScope = this,
            fetchStatus = {
                fetches.incrementAndGet()
                entered.complete(Unit)
                release.await()
                ServiceHealth.OPERATIONAL
            },
        )
        fixture.store.awaitInitialLoad()

        val departingDetail = async { fixture.store.refreshStatus(AgentProvider.CLAUDE) }
        entered.await()
        val autoRefreshWaiter = async { fixture.store.refreshStatus(AgentProvider.CLAUDE) }
        departingDetail.cancel()
        release.complete(Unit)

        assertEquals(ServiceHealth.OPERATIONAL, autoRefreshWaiter.await())
        assertEquals(1, fetches.get())
        fixture.close()
    }

    @Test
    fun `agent credential and metadata commit finishes after caller cancellation`() = runBlocking {
        val persistEntered = CompletableDeferred<Unit>()
        val releasePersist = CompletableDeferred<Unit>()
        val persisted = AtomicReference<List<Agent>>(emptyList())
        val fixture = fixture(
            parentScope = this,
            persistAgents = { agents ->
                persistEntered.complete(Unit)
                releasePersist.await()
                persisted.set(agents)
            },
        )
        fixture.store.awaitInitialLoad()

        val mutation = launch {
            fixture.store.addAgent(AgentProvider.OPENROUTER, OAuthTokens.apiKey("secret"))
        }
        persistEntered.await()
        mutation.cancel()
        releasePersist.complete(Unit)
        mutation.join()

        val committed = fixture.store.agents.value.single()
        assertEquals(listOf(committed), persisted.get())
        assertEquals("secret", fixture.tokens[committed.id]?.accessToken)
        fixture.close()
    }

    @Test
    fun `adaptive auto refresh feeds usage delta into clean policy ladder`() = runBlocking {
        val start = Instant.parse("2026-07-11T00:00:00Z")
        val clock = AtomicReference(start)
        val agent = Agent(AgentProvider.GROK)
        val fetches = AtomicInteger(0)
        val sleeper = ControlledSleeper()
        val fixture = fixture(
            parentScope = this,
            initialAgents = listOf(agent),
            now = clock::get,
            sleeper = sleeper,
            fetchSnapshot = { _, _ ->
                val percent = if (fetches.incrementAndGet() == 1) 10.0 else 15.0
                snapshot(percent = percent)
            },
        )
        fixture.store.awaitInitialLoad()

        fixture.store.startAutoRefresh(AutoRefreshPolicy.sentinel)
        val firstSleep = withTimeout(2_000) { sleeper.requests.receive() }
        assertEquals(Duration.ofSeconds(60), firstSleep.duration)
        clock.set(start.plusSeconds(60))
        firstSleep.release.complete(Unit)
        awaitCondition { fetches.get() >= 2 && fixture.store.autoIntervalSeconds.value == 30 }
        val secondSleep = withTimeout(2_000) { sleeper.requests.receive() }
        assertEquals(Duration.ofSeconds(30), secondSleep.duration)
        fixture.store.stopAutoRefresh()
        fixture.close()
    }

    @Test
    fun `zero interval refreshes immediately once and reset schedules one extra refresh at reset plus slack`() = runBlocking {
        val start = Instant.parse("2026-07-11T00:00:00Z")
        val clock = AtomicReference(start)
        val agent = Agent(AgentProvider.GROK)
        val fetches = AtomicInteger(0)
        val sleeper = ControlledSleeper()
        val fixture = fixture(
            parentScope = this,
            initialAgents = listOf(agent),
            now = clock::get,
            sleeper = sleeper,
            fetchSnapshot = { _, _ ->
                val count = fetches.incrementAndGet()
                snapshot(
                    percent = count.toDouble(),
                    resetsAt = if (count == 1) start.plusSeconds(20) else null,
                )
            },
        )
        fixture.store.awaitInitialLoad()

        fixture.store.startAutoRefresh(0)
        val resetSleep = withTimeout(2_000) { sleeper.requests.receive() }
        assertEquals(Duration.ofSeconds(21), resetSleep.duration)
        assertEquals(1, fetches.get())
        clock.set(start.plusSeconds(21))
        resetSleep.release.complete(Unit)
        awaitCondition { fetches.get() == 2 }
        delay(20)
        assertEquals(2, fetches.get())
        fixture.store.stopAutoRefresh()
        fixture.close()
    }

    @Test
    fun `add accountInfo and remove keep credentials outside agent state`() = runBlocking {
        val fixture = fixture(parentScope = this)
        fixture.store.awaitInitialLoad()
        val expiry = Instant.parse("2026-07-12T00:00:00Z")
        val tokens = OAuthTokens(
            accessToken = "secret",
            refreshToken = "refresh",
            expiresAt = expiry,
            scopes = listOf("read", "profile"),
            accountEmail = "dev@example.com",
            plan = "Max",
            accountId = "acct_1",
        )

        val agent = fixture.store.addAgent(AgentProvider.GROK, tokens)
        assertEquals("dev@example.com", fixture.store.agents.value.single().accountLabel)
        assertEquals(
            AccountInfo("dev@example.com", "Max", listOf("read", "profile"), expiry, true, "acct_1"),
            fixture.store.accountInfo(agent),
        )
        fixture.store.remove(agent)
        assertTrue(fixture.store.agents.value.isEmpty())
        assertTrue(fixture.store.snapshots.value.isEmpty())
        assertNull(fixture.store.accountInfo(agent))
        fixture.close()
    }

    private class ControlledSleeper : StoreSleeper {
        data class Request(
            val duration: Duration,
            val release: CompletableDeferred<Unit> = CompletableDeferred(),
        )

        val requests = Channel<Request>(Channel.UNLIMITED)

        override suspend fun sleep(duration: Duration) {
            val request = Request(duration)
            requests.send(request)
            request.release.await()
        }
    }

    private data class Fixture(
        val store: AgentStore,
        val tokens: ConcurrentHashMap<UUID, OAuthTokens>,
    ) {
        fun close() = store.close()
    }

    private fun fixture(
        parentScope: CoroutineScope,
        initialAgents: List<Agent> = emptyList(),
        agentUpdates: Flow<List<Agent>> = MutableStateFlow(initialAgents),
        persistAgents: suspend (List<Agent>) -> Unit = {},
        now: () -> Instant = { Instant.parse("2026-07-11T00:00:00Z") },
        sleeper: StoreSleeper = StoreSleeper.DEFAULT,
        fetchSnapshot: suspend (AgentProvider, UUID) -> AgentSnapshot = { _, _ -> snapshot(0.0) },
        fetchStatus: suspend () -> ServiceHealth = { ServiceHealth.OPERATIONAL },
    ): Fixture {
        val tokens = ConcurrentHashMap<UUID, OAuthTokens>()
        val store = AgentStore(
            parentScope = parentScope,
            dependencies = AgentStoreDependencies(
                agentUpdates = agentUpdates,
                settingsUpdates = flowOf(AppSettings()),
                persistAgents = persistAgents,
                persistSettings = {},
                saveTokens = { id, value -> tokens[id] = value },
                loadTokens = { id -> tokens[id] },
                deleteTokens = { id -> tokens.remove(id) },
                fetchSnapshot = fetchSnapshot,
                fetchStatus = { fetchStatus() },
            ),
            now = now,
            sleeper = sleeper,
        )
        return Fixture(store, tokens)
    }

    private companion object {
        fun snapshot(
            percent: Double,
            plan: String? = null,
            fetchedAt: Instant = Instant.parse("2026-07-11T00:00:00Z"),
            resetsAt: Instant? = null,
        ) = AgentSnapshot(
            windows = listOf(
                UsageWindow(
                    label = "session",
                    usedPercent = percent,
                    resetsAt = resetsAt,
                    kind = WindowKind.SESSION,
                    style = UsageStyle.GAUGE,
                ),
            ),
            planLabel = plan,
            fetchedAt = fetchedAt,
            error = null,
        )

        suspend fun awaitCondition(condition: () -> Boolean) {
            withTimeout(2_000) {
                while (!condition()) delay(5)
            }
        }
    }
}
