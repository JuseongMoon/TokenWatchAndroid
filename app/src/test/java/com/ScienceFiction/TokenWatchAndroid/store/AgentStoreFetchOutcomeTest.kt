package com.ScienceFiction.TokenWatchAndroid.store

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.ScienceFiction.TokenWatchAndroid.analytics.AnalyticsEvent
import com.ScienceFiction.TokenWatchAndroid.analytics.FetchErrorReason
import com.ScienceFiction.TokenWatchAndroid.analytics.FetchOutcomeLedger
import com.ScienceFiction.TokenWatchAndroid.auth.OAuthTokens
import com.ScienceFiction.TokenWatchAndroid.data.AppSettings
import com.ScienceFiction.TokenWatchAndroid.domain.Agent
import com.ScienceFiction.TokenWatchAndroid.domain.AgentProvider
import com.ScienceFiction.TokenWatchAndroid.domain.AgentSnapshot
import com.ScienceFiction.TokenWatchAndroid.domain.UsageStyle
import com.ScienceFiction.TokenWatchAndroid.domain.UsageWindow
import com.ScienceFiction.TokenWatchAndroid.domain.WindowKind
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * D021 / I006: the store records a fetch outcome only after it has accepted the result, against the
 * outcome kept on disk, and analytics bookkeeping never turns a fetch into a failure.
 */
class AgentStoreFetchOutcomeTest {
    private val now = Instant.parse("2026-11-17T00:00:00Z")
    private val directory: File = Files.createTempDirectory("store-outcomes").toFile()
    private val file = File(directory, "outcomes.preferences_pb")
    private var dataStoreJob = Job()
    private var ledger = FetchOutcomeLedger(openDataStore())
    private val events = CopyOnWriteArrayList<String>()
    private val stores = mutableListOf<AgentStore>()

    private fun openDataStore(): DataStore<Preferences> {
        dataStoreJob = Job()
        return PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + dataStoreJob)) { file }
    }

    /** The process ends; a new one opens the same file. */
    private fun restart() = runBlocking {
        stores.forEach { it.close() }
        dataStoreJob.cancelAndJoin()
        ledger = FetchOutcomeLedger(openDataStore())
    }

    @After fun tearDown() = runBlocking {
        stores.forEach { it.close() }
        dataStoreJob.cancelAndJoin()
        directory.deleteRecursively()
        Unit
    }

    private fun window() = UsageWindow(
        label = "session",
        usedPercent = 10.0,
        resetsAt = null,
        kind = WindowKind.SESSION,
        style = UsageStyle.GAUGE,
    )

    private fun ok() = AgentSnapshot(listOf(window()), null, now, error = null)
    private fun failed(reason: FetchErrorReason) =
        AgentSnapshot(emptyList(), null, now, error = "message", errorReason = reason)

    private fun eventName(event: AnalyticsEvent) =
        if (event is AnalyticsEvent.UsageFetchError) "error:${event.parameters["reason"]}" else event.eventName

    private fun store(
        agents: List<Agent>,
        settings: AppSettings = AppSettings(),
        collecting: () -> Boolean = { true },
        recordOverride: (suspend (Agent, AgentSnapshot, Boolean, suspend () -> Boolean) -> Unit)? = null,
        fetchSnapshot: suspend (AgentProvider, UUID) -> AgentSnapshot,
    ): AgentStore {
        val agentFlow = MutableStateFlow(agents)
        val settingsFlow: Flow<AppSettings> = flowOf(settings)
        val ledger = ledger
        val record: suspend (Agent, AgentSnapshot, Boolean, suspend () -> Boolean) -> Unit =
            recordOverride ?: { agent, snapshot, isCollecting, isAccepted ->
                ledger.record(agent.provider, agent.id, snapshot, isCollecting, isAccepted)
                    ?.let { events += eventName(it) }
            }
        return AgentStore(
            parentScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            dependencies = AgentStoreDependencies(
                agentUpdates = agentFlow,
                creditPeakUpdates = flowOf(emptyMap()),
                settingsUpdates = settingsFlow,
                persistAgents = { agentFlow.value = it },
                persistCreditPeaks = {},
                persistSettings = {},
                saveTokens = { _, _ -> },
                loadTokens = { null },
                deleteTokens = {},
                fetchSnapshot = fetchSnapshot,
                fetchStatus = { null },
                recordFetchOutcome = record,
                isAnalyticsCollecting = collecting,
                forgetFetchOutcomes = { ids -> ledger.forget(ids) },
                pauseFetchOutcomes = { ids -> ledger.pauseAll(ids) },
            ),
            now = { now },
        ).also { stores += it }
    }

    private fun stored(id: UUID) = runBlocking { ledger.value(id) }

    @Test
    fun theSameErrorAfterARestartIsNotCountedAgain() = runBlocking {
        val agent = Agent(AgentProvider.CODEX)
        store(listOf(agent)) { _, _ -> failed(FetchErrorReason.HTTP_5XX) }.apply {
            awaitInitialLoad()
            refresh(agent, manual = true)
        }
        restart()
        store(listOf(agent)) { _, _ -> failed(FetchErrorReason.HTTP_5XX) }.apply {
            awaitInitialLoad()
            refresh(agent, manual = true)
        }
        restart()
        store(listOf(agent)) { _, _ -> ok() }.apply {
            awaitInitialLoad()
            refresh(agent, manual = true)
        }

        assertEquals(listOf("error:http_5xx", "usage_fetch_recover"), events)
    }

    /** A result fetched with a credential that a sign-in replaced meanwhile is never counted. */
    @Test
    fun aResultDiscardedByASignInMeanwhileIsNotRecorded() = runBlocking {
        val email = "synthetic@example.invalid"
        val agent = Agent(AgentProvider.CLAUDE, accountLabel = email)
        val fetches = AtomicInteger(0)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store = store(listOf(agent)) { _, _ ->
            if (fetches.incrementAndGet() == 1) {
                started.complete(Unit)
                release.await()
                failed(FetchErrorReason.AUTH)
            } else {
                ok()
            }
        }
        store.awaitInitialLoad()

        val stale = async { store.refresh(agent) }
        started.await()
        withTimeout(2_000) { store.addAgent(AgentProvider.CLAUDE, OAuthTokens("new", accountEmail = email)) }
        release.complete(Unit)
        stale.await()
        withTimeout(2_000) { while (fetches.get() < 2 || agent.id in store.loadingIDs.value) delay(10) }

        // Only the follow-up with the new credential counted: a first success, no event.
        assertEquals(emptyList<String>(), events)
        assertEquals(FetchOutcomeLedger.OK, stored(agent.id))
    }

    @Test
    fun aResultForAnAccountRemovedMeanwhileIsNotRecorded() = runBlocking {
        val agent = Agent(AgentProvider.GROK)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store = store(listOf(agent)) { _, _ ->
            started.complete(Unit)
            release.await()
            failed(FetchErrorReason.NETWORK)
        }
        store.awaitInitialLoad()

        val late = async { store.refresh(agent) }
        started.await()
        store.remove(agent)
        release.complete(Unit)
        late.await()

        assertEquals(emptyList<String>(), events)
        assertNull(stored(agent.id))
    }

    @Test
    fun removingAnAccountForgetsItsOutcome() = runBlocking {
        val agent = Agent(AgentProvider.POE)
        val store = store(listOf(agent)) { _, _ -> failed(FetchErrorReason.AUTH) }
        store.awaitInitialLoad()
        store.refresh(agent, manual = true)
        assertEquals("auth", stored(agent.id))

        store.remove(agent)

        assertNull(stored(agent.id))
    }

    /** Analytics must never turn a fetch into a failure, whether the recorder or its disk fails. */
    @Test
    fun aRecorderFailureLeavesTheFetchResultAsItIs() = runBlocking {
        val agent = Agent(AgentProvider.CLAUDE)
        val store = store(
            listOf(agent),
            recordOverride = { _, _, _, _ -> throw IOException("disk full") },
        ) { _, _ -> ok() }
        store.awaitInitialLoad()

        store.refresh(agent, manual = true)

        val snapshot = store.snapshots.value.getValue(agent.id)
        assertNull(snapshot.error)
        assertEquals(listOf(window()), snapshot.windows)
    }

    @Test
    fun aLedgerWhoseDiskFailsLeavesTheFetchResultAsItIs() = runBlocking {
        val agent = Agent(AgentProvider.CLAUDE)
        val brokenDisk = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flowOf(androidx.datastore.preferences.core.emptyPreferences())
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
                throw IOException("disk full")
        }
        val broken = FetchOutcomeLedger(brokenDisk)
        val store = store(
            listOf(agent),
            recordOverride = { a, snapshot, collecting, accepted ->
                broken.record(a.provider, a.id, snapshot, collecting, accepted)
            },
        ) { _, _ -> ok() }
        store.awaitInitialLoad()

        store.refresh(agent, manual = true)

        assertNull(store.snapshots.value.getValue(agent.id).error)
    }

    @Test
    fun demoModeRecordsNothingAndLeavesEveryAccountPaused() = runBlocking {
        val failing = Agent(AgentProvider.CODEX)
        val fresh = Agent(AgentProvider.KIMI)
        val recorded = AtomicInteger(0)
        val store = store(listOf(failing, fresh)) { _, _ ->
            recorded.incrementAndGet()
            failed(FetchErrorReason.NETWORK)
        }
        store.awaitInitialLoad()
        store.refresh(failing, manual = true)
        assertEquals(listOf("error:network"), events)

        store.enterDemo()
        store.refreshAll()
        assertEquals(1, recorded.get())
        assertEquals(FetchOutcomeLedger.PAUSED, stored(failing.id))
        assertEquals(FetchOutcomeLedger.PAUSED, stored(fresh.id))

        store.exitDemo()
        store.refresh(failing, manual = true)
        store.refresh(fresh, manual = true)

        // The same error is not sent again, and a first result after demo mode is only a baseline.
        assertEquals(listOf("error:network"), events)
        assertEquals("network", stored(failing.id))
        assertEquals("network", stored(fresh.id))
    }

    @Test
    fun withAnalyticsOffAnAccountIsOnlyPausedAndBackOnItsFirstResultIsABaseline() = runBlocking {
        val agent = Agent(AgentProvider.DEEPSEEK)
        var result = failed(FetchErrorReason.AUTH)
        store(listOf(agent), settings = AppSettings(analyticsEnabled = false)) { _, _ -> result }.apply {
            awaitInitialLoad()
            refresh(agent, manual = true)
        }
        assertEquals(FetchOutcomeLedger.PAUSED, stored(agent.id))

        // The analytics gate (debug build, not configured…) counts as off too.
        result = ok()
        store(listOf(agent), collecting = { false }) { _, _ -> result }.apply {
            awaitInitialLoad()
            refresh(agent, manual = true)
        }
        assertEquals(FetchOutcomeLedger.PAUSED, stored(agent.id))

        val on = store(listOf(agent)) { _, _ -> result }
        on.awaitInitialLoad()
        on.refresh(agent, manual = true)
        assertEquals(emptyList<String>(), events)
        assertEquals(FetchOutcomeLedger.OK, stored(agent.id))

        result = failed(FetchErrorReason.AUTH)
        on.refresh(agent, manual = true)
        assertEquals(listOf("error:auth"), events)
    }
}
