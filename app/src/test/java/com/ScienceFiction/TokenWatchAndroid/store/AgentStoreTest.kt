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
import com.ScienceFiction.TokenWatchAndroid.notifications.ResetEvent
import com.ScienceFiction.TokenWatchAndroid.notifications.WindowObservation
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
        val three = Agent(AgentProvider.OPENROUTER, UUID.fromString("00000000-0000-0000-0000-000000000003"))
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
        val agent = Agent(AgentProvider.CLAUDE, accountLabel = "")
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
        val one = Agent(AgentProvider.CLAUDE)
        val two = Agent(AgentProvider.CLAUDE)
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
        val agent = Agent(AgentProvider.CLAUDE)
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
        val agent = Agent(AgentProvider.CLAUDE)
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
    fun `manual refresh bypasses spacing and replaces a stale non-email plan label`() = runBlocking {
        val start = Instant.parse("2026-07-11T00:00:00Z")
        val clock = AtomicReference(start)
        val agent = Agent(AgentProvider.CODEX, accountLabel = "Pro")
        val regularFetches = AtomicInteger(0)
        val manualFetches = AtomicInteger(0)
        val persisted = mutableListOf<List<Agent>>()
        val fixture = fixture(
            parentScope = this,
            initialAgents = listOf(agent),
            persistAgents = { persisted += it },
            now = clock::get,
            fetchSnapshot = { _, _ ->
                regularFetches.incrementAndGet()
                snapshot(percent = 5.0, plan = "Pro", fetchedAt = clock.get())
            },
            fetchManualSnapshot = { _, _ ->
                manualFetches.incrementAndGet()
                snapshot(percent = 6.0, plan = "Free", fetchedAt = clock.get())
            },
        )
        fixture.store.awaitInitialLoad()

        fixture.store.refresh(agent)
        clock.set(start.plusSeconds(1))
        fixture.store.refresh(agent)
        assertEquals(1, regularFetches.get())

        fixture.store.refresh(agent, manual = true)
        assertEquals(1, manualFetches.get())
        assertEquals("Free", fixture.store.agents.value.single().accountLabel)
        assertEquals("Free", persisted.single().single().accountLabel)
        fixture.close()
    }

    @Test
    fun `refresh persists reset baseline and emits an early weekly reset event`() = runBlocking {
        val observedNow = Instant.parse("2026-07-11T00:00:00Z")
        val agent = Agent(AgentProvider.CODEX)
        val key = "${agent.id}|Current week"
        val previousReset = observedNow.plusSeconds(3_600)
        val nextReset = previousReset.plusSeconds(604_800)
        val persisted = mutableListOf<Map<String, WindowObservation>>()
        val fired = mutableListOf<Pair<Agent, List<ResetEvent>>>()
        val fixture = fixture(
            parentScope = this,
            initialAgents = listOf(agent),
            initialResetBaseline = mapOf(
                key to WindowObservation(previousReset, usedPercent = 72.0),
            ),
            persistResetBaseline = { persisted += it },
            fireResetEvents = { eventAgent, events -> fired += eventAgent to events },
            now = { observedNow },
            fetchSnapshot = { _, _ ->
                AgentSnapshot(
                    windows = listOf(
                        UsageWindow(
                            label = "Current week",
                            usedPercent = 0.0,
                            resetsAt = nextReset,
                            kind = WindowKind.WEEKLY,
                        ),
                    ),
                    planLabel = null,
                    fetchedAt = observedNow,
                    error = null,
                )
            },
        )
        fixture.store.awaitInitialLoad()

        fixture.store.refresh(agent)

        assertEquals(nextReset, persisted.single().getValue(key).resetsAt)
        assertEquals(agent, fired.single().first)
        assertEquals(setOf(WindowKind.WEEKLY), fired.single().second.single().kinds)
        fixture.close()
    }

    @Test
    fun `first refresh waits for asynchronous credit peaks before promotion`() = runBlocking {
        val agent = Agent(AgentProvider.CLAUDE)
        val peakUpdates = MutableSharedFlow<Map<String, Double>>()
        val fixture = fixture(
            parentScope = this,
            initialAgents = listOf(agent),
            creditPeakUpdates = peakUpdates,
            fetchSnapshot = { _, _ -> creditSnapshot(remaining = 50.0) },
        )

        val refresh = async { fixture.store.refresh(agent) }
        delay(10)
        assertFalse(refresh.isCompleted)
        peakUpdates.emit(mapOf("${agent.id}|Credits" to 100.0))
        refresh.await()

        val promoted = fixture.store.snapshots.value.getValue(agent.id).windows.single()
        assertEquals(UsageStyle.CREDIT_GAUGE, promoted.style)
        assertEquals(50.0, promoted.usedPercent, 0.0)
        assertTrue(promoted.estimatedTotal)
        fixture.close()
    }

    @Test
    fun `provider total creates an exact credit gauge without an estimated peak`() = runBlocking {
        val agent = Agent(AgentProvider.CLAUDE)
        val persistedPeaks = mutableListOf<Map<String, Double>>()
        val fixture = fixture(
            parentScope = this,
            initialAgents = listOf(agent),
            persistCreditPeaks = { persistedPeaks += it },
            fetchSnapshot = { _, _ -> creditSnapshot(remaining = 75.0, total = 100.0) },
        )
        fixture.store.awaitInitialLoad()

        fixture.store.refresh(agent)

        val promoted = fixture.store.snapshots.value.getValue(agent.id).windows.single()
        assertEquals(UsageStyle.CREDIT_GAUGE, promoted.style)
        assertEquals(25.0, promoted.usedPercent, 0.0)
        assertFalse(promoted.estimatedTotal)
        assertTrue(persistedPeaks.isEmpty())
        fixture.close()
    }

    @Test
    fun `estimated credit peak persists resets without network and is pruned on remove`() = runBlocking {
        val start = Instant.parse("2026-07-11T00:00:00Z")
        val clock = AtomicReference(start)
        val agent = Agent(AgentProvider.CLAUDE)
        val fetches = AtomicInteger(0)
        val persistedPeaks = mutableListOf<Map<String, Double>>()
        val fixture = fixture(
            parentScope = this,
            initialAgents = listOf(agent),
            now = clock::get,
            persistCreditPeaks = { persistedPeaks += it },
            fetchSnapshot = { _, _ ->
                val remaining = if (fetches.incrementAndGet() == 1) 100.0 else 60.0
                creditSnapshot(remaining = remaining)
            },
        )
        fixture.store.awaitInitialLoad()

        fixture.store.refresh(agent)
        val peakKey = "${agent.id}|Credits"
        assertEquals(mapOf(peakKey to 100.0), persistedPeaks.single())

        clock.set(start.plusSeconds(20))
        fixture.store.refresh(agent)
        val consumed = fixture.store.snapshots.value.getValue(agent.id).windows.single()
        assertEquals(40.0, consumed.usedPercent, 0.0)
        assertEquals(1, persistedPeaks.size)

        fixture.store.resetCreditPeak(agent.id, "Credits")
        val reset = fixture.store.snapshots.value.getValue(agent.id).windows.single()
        assertEquals(0.0, reset.usedPercent, 0.0)
        assertEquals(mapOf(peakKey to 60.0), persistedPeaks.last())
        assertEquals(2, fetches.get())

        fixture.store.remove(agent)
        assertEquals(emptyMap<String, Double>(), persistedPeaks.last())
        assertTrue(fixture.store.snapshots.value.isEmpty())
        fixture.close()
    }

    @Test
    fun `parallel agent promotions serialize peak persistence without lost updates`() = runBlocking {
        val one = Agent(AgentProvider.CLAUDE)
        val two = Agent(AgentProvider.CLAUDE)
        val activePersists = AtomicInteger(0)
        val maxActivePersists = AtomicInteger(0)
        val persistedPeaks = mutableListOf<Map<String, Double>>()
        val fixture = fixture(
            parentScope = this,
            initialAgents = listOf(one, two),
            persistCreditPeaks = { peaks ->
                val active = activePersists.incrementAndGet()
                maxActivePersists.updateAndGet { previous -> maxOf(previous, active) }
                delay(10)
                persistedPeaks += peaks
                activePersists.decrementAndGet()
            },
            fetchSnapshot = { _, id ->
                creditSnapshot(remaining = if (id == one.id) 10.0 else 20.0)
            },
        )
        fixture.store.awaitInitialLoad()

        fixture.store.refreshAll()

        assertEquals(1, maxActivePersists.get())
        assertEquals(
            mapOf("${one.id}|Credits" to 10.0, "${two.id}|Credits" to 20.0),
            persistedPeaks.last(),
        )
        fixture.close()
    }

    @Test
    fun `late refresh cannot recreate snapshot or peak after agent removal`() = runBlocking {
        val agent = Agent(AgentProvider.CLAUDE)
        val fetchEntered = CompletableDeferred<Unit>()
        val releaseFetch = CompletableDeferred<Unit>()
        val persistedPeaks = mutableListOf<Map<String, Double>>()
        val fixture = fixture(
            parentScope = this,
            initialAgents = listOf(agent),
            persistCreditPeaks = { persistedPeaks += it },
            fetchSnapshot = { _, _ ->
                fetchEntered.complete(Unit)
                releaseFetch.await()
                creditSnapshot(remaining = 100.0)
            },
        )
        fixture.store.awaitInitialLoad()

        val refresh = async { fixture.store.refresh(agent) }
        fetchEntered.await()
        fixture.store.remove(agent)
        releaseFetch.complete(Unit)
        refresh.await()

        assertTrue(fixture.store.snapshots.value.isEmpty())
        assertTrue(persistedPeaks.isEmpty())
        assertTrue(fixture.store.loadingIDs.value.isEmpty())
        fixture.close()
    }

    @Test
    fun `failed peak persistence retries unchanged value on next refresh`() = runBlocking {
        val start = Instant.parse("2026-07-11T00:00:00Z")
        val clock = AtomicReference(start)
        val agent = Agent(AgentProvider.CLAUDE)
        val attempts = AtomicInteger(0)
        val persisted = mutableListOf<Map<String, Double>>()
        val fixture = fixture(
            parentScope = this,
            initialAgents = listOf(agent),
            now = clock::get,
            persistCreditPeaks = { peaks ->
                if (attempts.incrementAndGet() == 1) error("disk unavailable")
                persisted += peaks
            },
            fetchSnapshot = { _, _ -> creditSnapshot(remaining = 100.0) },
        )
        fixture.store.awaitInitialLoad()

        fixture.store.refresh(agent)
        assertEquals(1, attempts.get())
        assertTrue(persisted.isEmpty())

        clock.set(start.plusSeconds(20))
        fixture.store.refresh(agent)
        assertEquals(2, attempts.get())
        assertEquals(mapOf("${agent.id}|Credits" to 100.0), persisted.single())
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
    fun `failed status keeps last-good and retries immediately without a success timestamp`() = runBlocking {
        val start = Instant.parse("2026-07-11T00:00:00Z")
        val clock = AtomicReference(start)
        val responses = ArrayDeque<ServiceHealth?>(
            listOf(ServiceHealth.OPERATIONAL, null, ServiceHealth.CAUTION),
        )
        val fetches = AtomicInteger(0)
        val fixture = fixture(
            parentScope = this,
            now = clock::get,
            fetchStatus = {
                fetches.incrementAndGet()
                responses.removeFirst()
            },
        )
        fixture.store.awaitInitialLoad()

        assertEquals(ServiceHealth.OPERATIONAL, fixture.store.refreshStatus(AgentProvider.CLAUDE))
        clock.set(start.plusSeconds(60))
        assertEquals(ServiceHealth.OPERATIONAL, fixture.store.refreshStatus(AgentProvider.CLAUDE))
        assertEquals(ServiceHealth.OPERATIONAL, fixture.store.serviceStatus.value[AgentProvider.CLAUDE])

        assertEquals(ServiceHealth.CAUTION, fixture.store.refreshStatus(AgentProvider.CLAUDE))
        assertEquals(3, fetches.get())
        assertEquals(ServiceHealth.CAUTION, fixture.store.serviceStatus.value[AgentProvider.CLAUDE])
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
        val agent = Agent(AgentProvider.CLAUDE)
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
    fun `reentering adaptive refresh resets displayed interval and ladder index to sixty seconds`() = runBlocking {
        val start = Instant.parse("2026-07-11T00:00:00Z")
        val clock = AtomicReference(start)
        val agent = Agent(AgentProvider.CLAUDE)
        val fetches = AtomicInteger(0)
        val sleeper = ControlledSleeper()
        val fixture = fixture(
            parentScope = this,
            initialAgents = listOf(agent),
            now = clock::get,
            sleeper = sleeper,
            fetchSnapshot = { _, _ ->
                val percent = when (fetches.incrementAndGet()) {
                    1 -> 10.0
                    2 -> 15.0
                    else -> 17.0
                }
                snapshot(percent = percent)
            },
        )
        fixture.store.awaitInitialLoad()

        fixture.store.startAutoRefresh(AutoRefreshPolicy.sentinel)
        val initialSleep = withTimeout(2_000) { sleeper.requests.receive() }
        assertEquals(Duration.ofSeconds(60), initialSleep.duration)
        clock.set(start.plusSeconds(60))
        initialSleep.release.complete(Unit)
        awaitCondition { fetches.get() >= 2 && fixture.store.autoIntervalSeconds.value == 30 }
        val fastSleep = withTimeout(2_000) { sleeper.requests.receive() }
        assertEquals(Duration.ofSeconds(30), fastSleep.duration)

        fixture.store.startAutoRefresh(AutoRefreshPolicy.sentinel)
        assertEquals(60, fixture.store.autoIntervalSeconds.value)
        val reentrySleep = withTimeout(2_000) { sleeper.requests.receive() }
        assertEquals(Duration.ofSeconds(60), reentrySleep.duration)
        clock.set(start.plusSeconds(120))
        reentrySleep.release.complete(Unit)

        awaitCondition { fetches.get() >= 3 && fixture.store.autoIntervalSeconds.value == 30 }
        val postDeltaSleep = withTimeout(2_000) { sleeper.requests.receive() }
        assertEquals(Duration.ofSeconds(30), postDeltaSleep.duration)
        fixture.store.stopAutoRefresh()
        fixture.close()
    }

    @Test
    fun `zero interval refreshes immediately once and reset schedules one extra refresh at reset plus slack`() = runBlocking {
        val start = Instant.parse("2026-07-11T00:00:00Z")
        val clock = AtomicReference(start)
        val agent = Agent(AgentProvider.CLAUDE)
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

        val agent = fixture.store.addAgent(AgentProvider.CLAUDE, tokens)
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
        initialCreditPeaks: Map<String, Double> = emptyMap(),
        creditPeakUpdates: Flow<Map<String, Double>> = MutableStateFlow(initialCreditPeaks),
        initialResetBaseline: Map<String, WindowObservation> = emptyMap(),
        resetBaselineUpdates: Flow<Map<String, WindowObservation>> =
            MutableStateFlow(initialResetBaseline),
        persistAgents: suspend (List<Agent>) -> Unit = {},
        persistCreditPeaks: suspend (Map<String, Double>) -> Unit = {},
        persistResetBaseline: suspend (Map<String, WindowObservation>) -> Unit = {},
        fireResetEvents: suspend (Agent, List<ResetEvent>) -> Unit = { _, _ -> },
        now: () -> Instant = { Instant.parse("2026-07-11T00:00:00Z") },
        sleeper: StoreSleeper = StoreSleeper.DEFAULT,
        fetchSnapshot: suspend (AgentProvider, UUID) -> AgentSnapshot = { _, _ -> snapshot(0.0) },
        fetchManualSnapshot: (suspend (AgentProvider, UUID) -> AgentSnapshot)? = null,
        fetchStatus: suspend () -> ServiceHealth? = { ServiceHealth.OPERATIONAL },
    ): Fixture {
        val tokens = ConcurrentHashMap<UUID, OAuthTokens>()
        val store = AgentStore(
            parentScope = parentScope,
            dependencies = AgentStoreDependencies(
                agentUpdates = agentUpdates,
                creditPeakUpdates = creditPeakUpdates,
                settingsUpdates = flowOf(AppSettings()),
                persistAgents = persistAgents,
                persistCreditPeaks = persistCreditPeaks,
                resetBaselineUpdates = resetBaselineUpdates,
                persistResetBaseline = persistResetBaseline,
                fireResetEvents = fireResetEvents,
                persistSettings = {},
                saveTokens = { id, value -> tokens[id] = value },
                loadTokens = { id -> tokens[id] },
                deleteTokens = { id -> tokens.remove(id) },
                fetchSnapshot = fetchSnapshot,
                fetchManualSnapshot = fetchManualSnapshot,
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

        fun creditSnapshot(
            remaining: Double,
            total: Double? = null,
        ) = AgentSnapshot(
            windows = listOf(
                UsageWindow(
                    label = "Credits",
                    usedPercent = 0.0,
                    resetsAt = null,
                    kind = WindowKind.WEEKLY,
                    style = UsageStyle.BALANCE,
                    valueText = "$remaining credits",
                    balanceRemaining = remaining,
                    balanceTotal = total,
                ),
            ),
            planLabel = null,
            fetchedAt = Instant.parse("2026-07-11T00:00:00Z"),
            error = null,
        )

        suspend fun awaitCondition(condition: () -> Boolean) {
            withTimeout(2_000) {
                while (!condition()) delay(5)
            }
        }
    }
}
