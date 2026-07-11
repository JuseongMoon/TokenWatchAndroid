package com.ScienceFiction.TokenWatchAndroid.store

import com.ScienceFiction.TokenWatchAndroid.auth.OAuthTokens
import com.ScienceFiction.TokenWatchAndroid.auth.TokenStore
import com.ScienceFiction.TokenWatchAndroid.data.AgentRepository
import com.ScienceFiction.TokenWatchAndroid.data.AppSettings
import com.ScienceFiction.TokenWatchAndroid.data.AppSettingsCodec
import com.ScienceFiction.TokenWatchAndroid.data.SettingsRepository
import com.ScienceFiction.TokenWatchAndroid.domain.Agent
import com.ScienceFiction.TokenWatchAndroid.domain.AgentProvider
import com.ScienceFiction.TokenWatchAndroid.domain.AgentSnapshot
import com.ScienceFiction.TokenWatchAndroid.domain.ServiceHealth
import com.ScienceFiction.TokenWatchAndroid.domain.ServiceStatusSource
import com.ScienceFiction.TokenWatchAndroid.domain.UsageStyle
import com.ScienceFiction.TokenWatchAndroid.domain.refresh.AutoRefreshPolicy
import com.ScienceFiction.TokenWatchAndroid.network.orchestration.UsageGateway
import com.ScienceFiction.TokenWatchAndroid.network.status.ServiceStatusClient
import java.io.Closeable
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Account metadata exposed by the detail screen without exposing the credential itself. */
data class AccountInfo(
    val email: String?,
    val plan: String?,
    val scopes: List<String>,
    val expiresAt: Instant?,
    val canRefresh: Boolean,
    val accountId: String?,
)

/** Injectable clock-independent suspension boundary used by auto/reset refresh tests. */
fun interface StoreSleeper {
    suspend fun sleep(duration: Duration)

    companion object {
        val DEFAULT = StoreSleeper { duration ->
            delay(duration.toMillis().coerceAtLeast(0L))
        }
    }
}

/**
 * Small dependency boundary that keeps [AgentStore] usable in plain JVM tests.
 *
 * The production constructor below adapts the Android repositories and network clients to this
 * boundary; no Android class is needed by the store's state machine itself.
 */
data class AgentStoreDependencies(
    val agentUpdates: Flow<List<Agent>>,
    val settingsUpdates: Flow<AppSettings>,
    val persistAgents: suspend (List<Agent>) -> Unit,
    val persistSettings: suspend (AppSettings) -> Unit,
    val saveTokens: suspend (UUID, OAuthTokens) -> Unit,
    val loadTokens: suspend (UUID) -> OAuthTokens?,
    val deleteTokens: suspend (UUID) -> Unit,
    val fetchSnapshot: suspend (AgentProvider, UUID) -> AgentSnapshot,
    val fetchStatus: suspend (ServiceStatusSource) -> ServiceHealth,
)

/**
 * Foreground application state and refresh controller, ported through iOS commit `e7d1715`.
 *
 * It deliberately has no ViewModel or Android lifecycle dependency. The owner supplies a scope,
 * calls [startAutoRefresh]/[stopAutoRefresh] with foreground transitions, and closes the store
 * when its application container is disposed.
 */
class AgentStore(
    parentScope: CoroutineScope,
    private val dependencies: AgentStoreDependencies,
    private val now: () -> Instant = Instant::now,
    private val sleeper: StoreSleeper = StoreSleeper.DEFAULT,
) : Closeable {

    constructor(
        scope: CoroutineScope,
        agentRepository: AgentRepository,
        settingsRepository: SettingsRepository,
        tokenStore: TokenStore,
        usageGateway: UsageGateway,
        serviceStatusClient: ServiceStatusClient,
        now: () -> Instant = Instant::now,
        sleeper: StoreSleeper = StoreSleeper.DEFAULT,
    ) : this(
        parentScope = scope,
        dependencies = AgentStoreDependencies(
            agentUpdates = agentRepository.agents,
            settingsUpdates = settingsRepository.settings,
            persistAgents = agentRepository::saveAgents,
            persistSettings = settingsRepository::saveSettings,
            saveTokens = tokenStore::save,
            loadTokens = tokenStore::tokens,
            deleteTokens = tokenStore::delete,
            fetchSnapshot = usageGateway::fetchSnapshot,
            fetchStatus = serviceStatusClient::fetch,
        ),
        now = now,
        sleeper = sleeper,
    )

    private val storeJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + storeJob)
    private val closed = AtomicBoolean(false)

    private val agentMutex = Mutex()
    private val settingsMutex = Mutex()
    private val loadingMutex = Mutex()
    private val statusMutex = Mutex()

    private val agentsLoaded = CompletableDeferred<Unit>()
    private val settingsLoaded = CompletableDeferred<Unit>()

    private val _agents = MutableStateFlow<List<Agent>>(emptyList())
    val agents: StateFlow<List<Agent>> = _agents.asStateFlow()

    private val _snapshots = MutableStateFlow<Map<UUID, AgentSnapshot>>(emptyMap())
    val snapshots: StateFlow<Map<UUID, AgentSnapshot>> = _snapshots.asStateFlow()

    private val _loadingIDs = MutableStateFlow<Set<UUID>>(emptySet())
    val loadingIDs: StateFlow<Set<UUID>> = _loadingIDs.asStateFlow()
    private val inFlightRefreshIDs = mutableSetOf<UUID>()
    private val lastFetchAt = mutableMapOf<UUID, Instant>()

    private val _serviceStatus = MutableStateFlow<Map<AgentProvider, ServiceHealth>>(emptyMap())
    val serviceStatus: StateFlow<Map<AgentProvider, ServiceHealth>> = _serviceStatus.asStateFlow()

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _autoIntervalSeconds = MutableStateFlow(
        AutoRefreshPolicy.ladder[AutoRefreshPolicy.baseIndex],
    )
    val autoIntervalSeconds: StateFlow<Int> = _autoIntervalSeconds.asStateFlow()

    private val statusFetchedAt = mutableMapOf<AgentProvider, Instant>()
    private val statusInFlight = mutableMapOf<AgentProvider, CompletableDeferred<ServiceHealth>>()

    private val lifecycleLock = Any()
    private var autoRefreshJob: Job? = null
    private var resetRefreshJob: Job? = null
    private var scheduledResetRefreshAt: Instant? = null
    private var foregroundRefreshStarted = false
    private var autoGeneration = 0L
    private var autoLadderIndex = AutoRefreshPolicy.baseIndex
    private var autoBaseline = emptyMap<String, Double>()

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                dependencies.agentUpdates.collect { value ->
                    agentMutex.withLock { _agents.value = value }
                    agentsLoaded.complete(Unit)
                }
            } finally {
                agentsLoaded.complete(Unit)
            }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                dependencies.settingsUpdates.collect { value ->
                    settingsMutex.withLock { _settings.value = AppSettingsCodec.normalize(value) }
                    settingsLoaded.complete(Unit)
                }
            } finally {
                settingsLoaded.complete(Unit)
            }
        }
    }

    /** Waits until both asynchronous repository flows have supplied their initial value. */
    suspend fun awaitInitialLoad() {
        agentsLoaded.await()
        settingsLoaded.await()
    }

    /** Saves credentials, appends the account, persists it, and performs its first refresh. */
    suspend fun addAgent(provider: AgentProvider, tokens: OAuthTokens): Agent {
        awaitAgentsReady()
        val agent = Agent(
            provider = provider,
            accountLabel = tokens.accountEmail ?: tokens.plan,
        )
        withContext(NonCancellable) {
            dependencies.saveTokens(agent.id, tokens)
            try {
                agentMutex.withLock {
                    val previous = _agents.value
                    val next = previous + agent
                    _agents.value = next
                    try {
                        dependencies.persistAgents(next)
                    } catch (error: Throwable) {
                        _agents.value = previous
                        throw error
                    }
                }
            } catch (error: Throwable) {
                runCatching { dependencies.deleteTokens(agent.id) }
                throw error
            }
        }
        currentCoroutineContext().ensureActive()
        refresh(agent)
        return agent
    }

    suspend fun remove(agent: Agent) {
        awaitAgentsReady()
        withContext(NonCancellable) {
            agentMutex.withLock {
                val previous = _agents.value
                val next = previous.filterNot { it.id == agent.id }
                _agents.value = next
                try {
                    dependencies.persistAgents(next)
                } catch (error: Throwable) {
                    _agents.value = previous
                    throw error
                }
            }
            _snapshots.update { it - agent.id }
            dependencies.deleteTokens(agent.id)
        }
    }

    suspend fun moveUp(agent: Agent): Boolean = reorder(agent, offset = -1)

    suspend fun moveDown(agent: Agent): Boolean = reorder(agent, offset = 1)

    private suspend fun reorder(agent: Agent, offset: Int): Boolean {
        awaitAgentsReady()
        return withContext(NonCancellable) {
            agentMutex.withLock {
                val current = _agents.value
                val from = current.indexOfFirst { it.id == agent.id }
                val to = from + offset
                if (from < 0 || to !in current.indices) return@withLock false
                val next = current.toMutableList().apply {
                    this[from] = this[to].also { this[to] = this[from] }
                }
                _agents.value = next
                try {
                    dependencies.persistAgents(next)
                } catch (error: Throwable) {
                    _agents.value = current
                    throw error
                }
                true
            }
        }
    }

    suspend fun saveSettings(value: AppSettings) {
        awaitSettingsReady()
        withContext(NonCancellable) {
            settingsMutex.withLock {
                val previous = _settings.value
                val normalized = AppSettingsCodec.normalize(value)
                _settings.value = normalized
                try {
                    dependencies.persistSettings(normalized)
                } catch (error: Throwable) {
                    _settings.value = previous
                    throw error
                }
            }
        }
    }

    suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
        awaitSettingsReady()
        withContext(NonCancellable) {
            settingsMutex.withLock {
                val previous = _settings.value
                val next = AppSettingsCodec.normalize(transform(previous))
                _settings.value = next
                try {
                    dependencies.persistSettings(next)
                } catch (error: Throwable) {
                    _settings.value = previous
                    throw error
                }
            }
        }
    }

    suspend fun accountInfo(agent: Agent): AccountInfo? = dependencies.loadTokens(agent.id)?.let {
        AccountInfo(
            email = it.accountEmail,
            plan = it.plan,
            scopes = it.scopes,
            expiresAt = it.expiresAt,
            canRefresh = it.refreshToken != null,
            accountId = it.accountId,
        )
    }

    /** Refreshes a stable copy of the current list concurrently. */
    suspend fun refreshAll() {
        awaitAgentsReady()
        val current = _agents.value
        coroutineScope {
            current.map { agent -> async { refresh(agent) } }.awaitAll()
        }
    }

    suspend fun refresh(agent: Agent) {
        awaitAgentsReady()
        if (!beginRefresh(agent.id)) return
        try {
            val incoming = dependencies.fetchSnapshot(agent.provider, agent.id)
            _snapshots.update { current ->
                val previous = current[agent.id]
                val applied = if (
                    incoming.error != null && incoming.windows.isEmpty() &&
                    previous != null && previous.windows.isNotEmpty()
                ) {
                    AgentSnapshot(
                        windows = previous.windows,
                        planLabel = previous.planLabel,
                        fetchedAt = previous.fetchedAt,
                        error = incoming.error,
                    )
                } else {
                    incoming
                }
                current + (agent.id to applied)
            }

            if (incoming.planLabel != null) {
                enrichAccountLabel(agent.id, incoming.planLabel)
            }

            scheduleResetRefresh()
            refreshStatus(agent.provider)
        } finally {
            withContext(NonCancellable) { finishRefresh(agent.id) }
        }
    }

    private suspend fun enrichAccountLabel(agentId: UUID, plan: String) {
        withContext(NonCancellable) {
            agentMutex.withLock {
                val current = _agents.value
                val index = current.indexOfFirst { it.id == agentId }
                if (index < 0 || !current[index].accountLabel.isNullOrEmpty()) return@withLock
                val next = current.toMutableList()
                next[index] = next[index].copy(accountLabel = plan)
                _agents.value = next
                try {
                    dependencies.persistAgents(next)
                } catch (error: Throwable) {
                    _agents.value = current
                    throw error
                }
            }
        }
    }

    /**
     * Claims an agent's usage refresh and records its start time as one atomic operation.
     *
     * Matching iOS `e7d1715`, a request is skipped while the same agent is already loading or
     * when fewer than 20 seconds have elapsed since its previous fetch began. The timestamp is
     * deliberately retained after both success and failure, and an exact 20-second boundary is
     * allowed.
     */
    private suspend fun beginRefresh(agentId: UUID): Boolean = loadingMutex.withLock {
        if (agentId in inFlightRefreshIDs) return@withLock false

        val startedAt = now()
        val previousStart = lastFetchAt[agentId]
        if (
            previousStart != null &&
            Duration.between(previousStart, startedAt) < USAGE_MIN_FETCH_SPACING
        ) {
            return@withLock false
        }

        lastFetchAt[agentId] = startedAt
        inFlightRefreshIDs += agentId
        _loadingIDs.value = inFlightRefreshIDs.toSet()
        true
    }

    private suspend fun finishRefresh(agentId: UUID) = loadingMutex.withLock {
        inFlightRefreshIDs -= agentId
        _loadingIDs.value = inFlightRefreshIDs.toSet()
    }

    /**
     * Refreshes public service status no more than once per provider per minute. A timestamp and
     * in-flight gate are installed before suspension, so concurrent accounts never duplicate it.
     */
    suspend fun refreshStatus(
        provider: AgentProvider,
        force: Boolean = false,
    ): ServiceHealth? {
        ensureOpen()
        val source = provider.statusSource ?: return null
        val observedNow = now()
        var created = false
        val request = statusMutex.withLock {
            statusInFlight[provider]?.let { return@withLock it }
            val last = statusFetchedAt[provider]
            if (!force && last != null && Duration.between(last, observedNow) < STATUS_MIN_INTERVAL) {
                return@withLock null
            }
            statusFetchedAt[provider] = observedNow
            created = true
            CompletableDeferred<ServiceHealth>().also { statusInFlight[provider] = it }
        } ?: return _serviceStatus.value[provider]

        if (created) {
            // The request belongs to the store, not the first UI/auto-refresh caller. A detail
            // screen leaving must not cancel every concurrent waiter for the same provider.
            scope.launch {
                try {
                    val health = dependencies.fetchStatus(source)
                    _serviceStatus.update { it + (provider to health) }
                    request.complete(health)
                } catch (cancelled: CancellationException) {
                    request.cancel(cancelled)
                    throw cancelled
                } catch (_: Throwable) {
                    _serviceStatus.update { it + (provider to ServiceHealth.UNKNOWN) }
                    request.complete(ServiceHealth.UNKNOWN)
                } finally {
                    statusMutex.withLock {
                        if (statusInFlight[provider] === request) statusInFlight.remove(provider)
                    }
                }
            }
        }
        return request.await()
    }

    /** Starts foreground refresh. Zero means one immediate pass; -1 selects adaptive mode. */
    fun startAutoRefresh(interval: Int) {
        ensureOpen()
        val generation: Long
        val job: Job
        synchronized(lifecycleLock) {
            autoRefreshJob?.cancel()
            resetRefreshJob?.cancel()
            resetRefreshJob = null
            scheduledResetRefreshAt = null
            foregroundRefreshStarted = true
            generation = ++autoGeneration
            job = scope.launch(start = CoroutineStart.LAZY) {
                runAutoRefresh(interval, generation)
            }
            autoRefreshJob = job
        }
        job.start()
    }

    fun stopAutoRefresh() {
        synchronized(lifecycleLock) {
            foregroundRefreshStarted = false
            autoGeneration += 1
            autoRefreshJob?.cancel()
            autoRefreshJob = null
            resetRefreshJob?.cancel()
            resetRefreshJob = null
            scheduledResetRefreshAt = null
        }
    }

    private suspend fun runAutoRefresh(interval: Int, generation: Long) {
        val adaptive = interval == AutoRefreshPolicy.sentinel
        refreshAll()
        if (adaptive && generationIsCurrent(generation)) {
            synchronized(lifecycleLock) { autoBaseline = gaugePercents() }
        }
        if (!adaptive && interval <= 0) return

        while (generationIsCurrent(generation)) {
            val seconds = if (adaptive) _autoIntervalSeconds.value else interval
            sleeper.sleep(Duration.ofSeconds(seconds.toLong()))
            currentCoroutineContext().ensureActive()
            if (!generationIsCurrent(generation)) return
            refreshAll()
            if (adaptive) adaptAutoInterval(generation)
        }
    }

    private fun adaptAutoInterval(generation: Long) {
        synchronized(lifecycleLock) {
            if (generation != autoGeneration || !foregroundRefreshStarted) return
            val current = gaugePercents()
            val delta = AutoRefreshPolicy.maxUsageDelta(autoBaseline, current)
            autoBaseline = autoBaseline + current
            autoLadderIndex = AutoRefreshPolicy.nextLadderIndex(autoLadderIndex, delta)
            _autoIntervalSeconds.value = AutoRefreshPolicy.ladder[autoLadderIndex]
        }
    }

    private fun gaugePercents(): Map<String, Double> = buildMap {
        for ((agentId, snapshot) in _snapshots.value) {
            if (snapshot.error != null) continue
            for (window in snapshot.windows) {
                if (window.style == UsageStyle.GAUGE) {
                    put("$agentId|${window.label}", window.usedPercent)
                }
            }
        }
    }

    private fun scheduleResetRefresh() {
        val job: Job
        synchronized(lifecycleLock) {
            if (!foregroundRefreshStarted || closed.get()) return
            val observedNow = now()
            val reset = AutoRefreshPolicy.nextResetDate(
                _snapshots.value.values.flatMap { snapshot ->
                    snapshot.windows.map { window -> window.resetsAt }
                },
                observedNow,
            )
            val target = reset?.plus(AutoRefreshPolicy.resetSlack)
            if (target == scheduledResetRefreshAt) return

            resetRefreshJob?.cancel()
            resetRefreshJob = null
            scheduledResetRefreshAt = target
            if (target == null) return

            val generation = autoGeneration
            job = scope.launch(start = CoroutineStart.LAZY) {
                val remaining = Duration.between(now(), target)
                if (!remaining.isNegative && !remaining.isZero) sleeper.sleep(remaining)
                currentCoroutineContext().ensureActive()
                val shouldRun = synchronized(lifecycleLock) {
                    if (
                        foregroundRefreshStarted && generation == autoGeneration &&
                        scheduledResetRefreshAt == target
                    ) {
                        resetRefreshJob = null
                        scheduledResetRefreshAt = null
                        true
                    } else {
                        false
                    }
                }
                if (shouldRun) refreshAll()
            }
            resetRefreshJob = job
        }
        job.start()
    }

    private fun generationIsCurrent(generation: Long): Boolean = synchronized(lifecycleLock) {
        foregroundRefreshStarted && generation == autoGeneration
    }

    private fun ensureOpen() {
        check(!closed.get()) { "AgentStore is closed" }
    }

    private suspend fun awaitAgentsReady() {
        ensureOpen()
        agentsLoaded.await()
        ensureOpen()
    }

    private suspend fun awaitSettingsReady() {
        ensureOpen()
        settingsLoaded.await()
        ensureOpen()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        stopAutoRefresh()
        agentsLoaded.complete(Unit)
        settingsLoaded.complete(Unit)
        scope.cancel()
    }

    private companion object {
        val STATUS_MIN_INTERVAL: Duration = Duration.ofSeconds(60)
        val USAGE_MIN_FETCH_SPACING: Duration = Duration.ofSeconds(20)
    }
}
