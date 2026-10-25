package com.ScienceFiction.TokenWatchAndroid.monster

import com.ScienceFiction.TokenWatchAndroid.analytics.AnalyticsEvent
import com.ScienceFiction.TokenWatchAndroid.domain.Agent
import com.ScienceFiction.TokenWatchAndroid.domain.AgentProvider
import com.ScienceFiction.TokenWatchAndroid.domain.UsageWindow
import com.ScienceFiction.TokenWatchAndroid.monster.MonsterApiClient.CallError
import com.ScienceFiction.TokenWatchAndroid.monster.MonsterApiClient.CallResult
import com.ScienceFiction.TokenWatchAndroid.monster.MonsterApiClient.SpawnResult
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the monster store needs from the app account. */
interface MonsterAccount {
    /** The signed-in account, with or without a profile. */
    val uid: String?

    /** The loaded profile's accepted terms version; null without a profile. */
    val termsAcceptedVersion: Int?

    /** Shows a consent the server recorded in the on-screen profile. */
    fun markTermsAccepted(version: Int)
}

/** The config cache: small values kept beside the ledger. */
interface MonsterPreferences {
    suspend fun loadPreferences(): MonsterPreferenceState
    suspend fun saveConfig(payload: String)
    suspend fun saveConfigFetchedAt(fetchedAtMs: Long)
}

data class MonsterPreferenceState(
    val configPayload: String? = null,
    val configFetchedAtMs: Long? = null,
)

/** Everything the screens draw from. One immutable value, so a screen never sees half an update. */
data class MonsterState(
    val config: MonsterConfig = MonsterConfig.BUNDLED_DEFAULT,
    val ledger: MonsterLedgerState = MonsterLedgerState(),
    /** The account collecting monsters now (consented), or null for the guest. */
    val owner: String? = null,
    /** Signed in with a profile but not yet consented: the consent screen's audience. */
    val needsConsent: Boolean = false,
    val isDemo: Boolean = false,
    /** The account's stats as the server last returned them. */
    val stats: MonsterStats? = null,
    val rank: MyRank? = null,
    /** The top 10 per tab. The week list is for the week it was read in. */
    val leaderboards: Map<LeaderboardScope, List<LeaderboardEntry>> = emptyMap(),
    val leaderboardLoadedAtMs: Map<LeaderboardScope, Long> = emptyMap(),
    val leaderboardFailed: Set<LeaderboardScope> = emptySet(),
    /** Last hit per encounter key (the sword effect). */
    val lastHitAtMs: Map<String, Long> = emptyMap(),
    /** The server asked for consent (`consent_required`). */
    val consentRequested: Boolean = false,
    /** HP left on the demo's sample monsters (memory only). */
    val demoHp: Map<String, Int> = emptyMap(),
    /** Profile bestiary: the current account's defeats per species (server record + merged guest history). Null until read. */
    val bestiaryKills: Map<String, Int>? = null,
    val bestiaryFailed: Boolean = false,
) {
    /** Defeats this device recorded as a guest. */
    val guestKillCount: Int get() = ledger.guest.kills.values.sum()

    /** A tab's leaderboard (the top 10 as read). */
    fun leaderboard(scope: LeaderboardScope): List<LeaderboardEntry> = leaderboards[scope].orEmpty()
}

/**
 * The monster hunt (V2) state machine, mirroring iOS `MonsterStore`. It watches the gauge windows,
 * requests or draws spawns, handles hits and defeats, and holds the server's verdicts, the leaderboard
 * and the player's rank.
 *
 * - Owner: a signed-in account that consented (terms version 2) collects server-judged, ranked
 *   monsters; anyone else is the guest (device draws, unranked). Only the current owner's monsters show.
 * - Spawns are requested from foreground refreshes only; a background refresh only expires monsters.
 *   Switching the gauge creatures off stops both spawning and drawing.
 * - Demo mode never calls the server or saves: the sample 100% gauges get memory-only sample monsters.
 * - The server receives the contract's values only (provider, window label, period, reset bucket, agent
 *   key, platform, app version) — never a usage figure or AI service account information.
 *
 * All state changes go through one lock, so the UI's hits and the refresh hook never interleave halfway;
 * network calls happen outside it, and refresh observations are serialized among themselves.
 */
class MonsterStore(
    private val account: MonsterAccount,
    private val api: MonsterApiClient?,
    private val env: Environment,
    private val storage: MonsterLedgerStorage,
    private val sessionStorage: DeviceSessionLedgerStorage,
    private val preferences: MonsterPreferences,
    /** Reads the public `config/monster` payload; null leaves the bundled defaults in force. */
    private val fetchConfig: (suspend () -> String?)? = null,
    private val scope: CoroutineScope,
    private val log: (AnalyticsEvent) -> Unit = {},
) {
    /** Dependencies the tests swap: clock, foreground, demo and the current windows. */
    class Environment(
        val isDemo: () -> Boolean,
        val crittersEnabled: () -> Boolean,
        val isForeground: () -> Boolean,
        val nowMs: () -> Long,
        val appVersion: String,
        /** Every agent's current windows (the random spawn candidates). */
        val snapshots: () -> List<Pair<Agent, List<UsageWindow>>>,
    )

    sealed class HitResult {
        data object Ignored : HitResult()
        data class Damaged(val remaining: Int) : HitResult()
        data object Defeated : HitResult()
    }

    private val _state = MutableStateFlow(MonsterState())
    val state: StateFlow<MonsterState> = _state.asStateFlow()

    private val lock = Any()
    private val loaded = CompletableDeferred<Unit>()
    private val observeMutex = Mutex()
    private val saveMutex = Mutex()

    // Guarded by [lock].
    private val inFlight = mutableSetOf<String>()

    /** Earliest next request per key (ms). A final refusal is not asked again this run (Long.MAX_VALUE). */
    private val retryAfter = mutableMapOf<String, Long>()

    private val lastHitMs = mutableMapOf<String, Long>()

    @Volatile
    private var configFetchedAtMs: Long? = null

    @Volatile
    private var rankLoadedAtMs: Long? = null

    /** The week the week tab's list was read for. */
    @Volatile
    private var leaderboardWeekId: String? = null

    /** The account the bestiary's defeats belong to. */
    @Volatile
    private var bestiaryOwner: String? = null

    private fun edit(block: (MonsterState) -> MonsterState) = synchronized(lock) {
        _state.value = block(_state.value)
    }

    private fun editLedger(block: (MonsterLedgerState) -> MonsterLedgerState) =
        edit { it.copy(ledger = block(it.ledger)) }

    /** Reads the ledger and the preferences once. Spawning waits for this; hits before it find nothing. */
    suspend fun load() {
        if (loaded.isCompleted) return
        val ledger = (attempt { storage.load() } ?: MonsterLedgerState()).pruned()
        val prefs = attempt { preferences.loadPreferences() } ?: MonsterPreferenceState()
        configFetchedAtMs = prefs.configFetchedAtMs
        edit {
            it.copy(
                ledger = ledger,
                config = prefs.configPayload?.let(MonsterConfigDecoder::decodePayload) ?: MonsterConfig.BUNDLED_DEFAULT,
            )
        }
        syncAccount()
        loaded.complete(Unit)
    }

    // MARK: Owner and consent

    /** The account collecting monsters now (signed in and consented). Null = guest. */
    val activeOwner: String?
        get() {
            if (api == null) return null
            val uid = account.uid ?: return null
            val terms = account.termsAcceptedVersion ?: return null
            return uid.takeIf { terms >= TERMS_VERSION }
        }

    /** Signed in with a profile, not yet consented (the consent screen's audience). */
    val needsConsent: Boolean
        get() {
            if (api == null || env.isDemo()) return false
            val terms = account.termsAcceptedVersion ?: return false
            return terms < TERMS_VERSION
        }

    /** Re-reads the owner after the account or demo mode changed (the app calls it on every change). */
    fun syncAccount() {
        val owner = activeOwner
        val consent = needsConsent
        val demo = env.isDemo()
        // Another account's defeats never show, not even for the moment before the bestiary reads again.
        val forgetBestiary = bestiaryOwner != null && bestiaryOwner != account.uid
        if (forgetBestiary) bestiaryOwner = null
        edit {
            val base = it.copy(owner = owner, needsConsent = consent, isDemo = demo)
            if (forgetBestiary) base.copy(bestiaryKills = null, bestiaryFailed = false) else base
        }
    }

    /** Records the consent on the server. On success the guest history is merged once. */
    suspend fun acceptConsent(): Boolean {
        val api = api ?: return false
        if (env.isDemo() || account.uid == null) return false
        return when (val result = api.acceptTerms(TERMS_VERSION)) {
            is CallResult.Success -> {
                account.markTermsAccepted(result.value)
                synchronized(lock) { retryAfter.clear() }
                edit { it.copy(consentRequested = false) }
                syncAccount()
                mergeGuestHistoryIfNeeded()
                true
            }
            is CallResult.Failure -> false
        }
    }

    /**
     * Merges the guest defeats into the account once, as unranked history. A history the server refuses
     * is not sent again; a transport failure retries on the next observation.
     */
    suspend fun mergeGuestHistoryIfNeeded() {
        val api = api ?: return
        val owner = activeOwner ?: return
        val ledger = _state.value.ledger
        if (owner in ledger.mergedUids) return
        val settled = ledger.guest.kills.isEmpty() || when (val result = api.mergeGuestHistory(ledger.guest)) {
            // Merged, or already merged before (merged = false).
            is CallResult.Success -> true
            is CallResult.Failure -> result.error is CallError.Server
        }
        if (settled) {
            editLedger { if (owner in it.mergedUids) it else it.copy(mergedUids = it.mergedUids + owner) }
            save()
        }
    }

    // MARK: Observation (the refresh hook)

    /** Called after every agent refresh. Spawns are requested only while the app is in front. */
    suspend fun observe(agent: Agent, windows: List<UsageWindow>) {
        if (env.isDemo()) return
        loaded.await()
        observeMutex.withLock {
            val now = env.nowMs()
            expireFinished(now)
            if (!env.crittersEnabled() || !env.isForeground()) {
                save()
                return@withLock
            }
            refreshConfigIfNeeded()
            val owner = activeOwner
            if (owner != null && owner !in _state.value.ledger.mergedUids) mergeGuestHistoryIfNeeded()
            val instant = Instant.ofEpochMilli(now)
            for (window in windows) {
                if (MonsterEligibility.isCompleted(window, instant)) handleCompletion(agent, window, owner, now)
            }
            handleRandom(owner, now)
            retryPendingDefeats(owner)
            save()
        }
    }

    private suspend fun handleCompletion(agent: Agent, window: UsageWindow, owner: String?, now: Long) {
        val state = _state.value
        if (!state.config.completionEnabled) return
        val resetsAt = window.resetsAt ?: return
        val keyLabel = MonsterSessionKey.keyLabel(agent.provider, window.label)
        val bucket = MonsterSessionKey.resetBucketMs(MonsterSessionKey.keyResetsAtMs(agent.provider, resetsAt))
        val key = MonsterSessionKey.completionKey(owner ?: GUEST, agent.provider, bucket)
        if (state.ledger.encounters.any { it.key == key }) {
            anchorIfNeeded(key, agent.id, window.label)
            return
        }
        if (!canRequest(key, now)) return
        val api = api
        if (owner != null && api != null) {
            val ledgerKey = MonsterSessionKey.completionLedgerKey(agent.provider, bucket)
            val session = sessionStorage.load()
            if (session.blocks(ledgerKey, owner)) return
            val result = inFlight(key) {
                api.spawnCompletion(
                    provider = agent.provider.wireId,
                    windowLabel = keyLabel,
                    periodSec = MonsterSessionKey.periodSec(agent.provider, window.label, window.windowSeconds, window.kind),
                    resetBucketMs = bucket,
                    resetsAtMs = MonsterSessionKey.epochMs(resetsAt),
                )
            }
            if ((result as? CallResult.Success)?.value is SpawnResult.Encounter) {
                sessionStorage.save(session.recording(ledgerKey, owner, now).purged(now))
            }
            apply(result, key, owner, agent.id to window.label, now)
        } else {
            val decision = GuestSpawnPolicy.decide(
                isCompletion = true, key = key, salt = state.ledger.deviceSalt, config = state.config,
                progress = state.ledger.guest, aliveRandom = 0, nowMs = now, appVersion = env.appVersion,
            )
            if (decision !is GuestSpawnPolicy.Outcome.Spawn) {
                setRetry(key, now + 600_000L)
                return
            }
            addGuestEncounter(
                key = key, source = MonsterEncounter.Source.COMPLETION, provider = agent.provider.wireId,
                windowLabel = keyLabel, resetBucketMs = bucket, drawAtMs = null, entry = decision.entry, now = now,
                expiresAtMs = MonsterSessionKey.epochMs(resetsAt), anchor = agent.id to window.label,
            )
        }
    }

    /**
     * Random spawns: each agent (a connected AI account) draws once every 20…40 minutes. The schedule
     * sits in the ledger per agent, independent of the account (switching accounts never adds draws),
     * and a signed-in account follows the server's next time. An agent seen for the first time only
     * gets a schedule.
     */
    private suspend fun handleRandom(owner: String?, now: Long) {
        val config = _state.value.config
        if (!config.random.enabled) return
        val instant = Instant.ofEpochMilli(now)
        var drawn = 0
        for ((agent, windows) in env.snapshots()) {
            if (drawn >= config.random.maxAgentsPerUser) break
            // Candidates: this agent's target windows, one per label (at most 20). A 100% window joins once
            // its completion monster is defeated.
            val anchors = linkedMapOf<String, String>()
            val candidates = mutableListOf<Pair<String, String>>()
            for (window in windows) {
                if (!MonsterEligibility.isRandomCandidate(window, config.random.minUsedPercent, instant)) continue
                if (MonsterEligibility.isCompleted(window, instant) &&
                    visible(_state.value, agent.id, agent.provider, window, now)
                        .any { it.source == MonsterEncounter.Source.COMPLETION && it.isAlive }
                ) {
                    continue
                }
                val keyLabel = MonsterSessionKey.keyLabel(agent.provider, window.label)
                if (keyLabel in anchors || candidates.size >= MAX_CANDIDATES) continue
                anchors[keyLabel] = window.label
                candidates += agent.provider.wireId to keyLabel
            }
            if (candidates.isEmpty()) continue // no target window: the schedule stays
            drawn += 1
            drawRandom(agent, candidates, anchors, owner, now)
        }
    }

    private suspend fun drawRandom(
        agent: Agent,
        candidates: List<Pair<String, String>>,
        anchors: Map<String, String>,
        owner: String?,
        now: Long,
    ) {
        val state = _state.value
        val token = MonsterSessionKey.agentToken(agent.id)
        val retryKey = "rand|$token"
        val due = state.ledger.nextDraw(token)
        if ((due != null && due > now) || !canRequest(retryKey, now)) return
        val api = api
        if (owner != null && api != null) {
            val agentKey = MonsterSessionKey.agentKey(owner, token)
            when (val result = inFlight(retryKey) { api.spawnRandom(agentKey, candidates) }) {
                is CallResult.Success -> {
                    // A device clock ahead of the server can see the due time as already past: ask once a minute at most.
                    val next = result.value.nextDrawAtMs ?: (now + MonsterDraw.drawIntervalMs(state.config, 0.0))
                    editLedger { it.withNextDraw(maxOf(next, now + 60_000L), token, now) }
                    result.value.encounter?.let { dto ->
                        val anchor = anchors[dto.windowLabel]?.let { agent.id to it }
                        apply(CallResult.Success(SpawnResult.Encounter(dto)), dto.key, owner, anchor, now)
                    }
                }
                is CallResult.Failure -> apply(result, retryKey, owner, null, now)
            }
            return
        }
        val salt = state.ledger.deviceSalt
        if (due == null) {
            val first = MonsterDraw.drawIntervalMs(state.config, MonsterDraw.unitRoll("$salt|$token|first"))
            editLedger { it.withNextDraw(now + first, token, now) }
            return
        }
        val key = MonsterSessionKey.randomKey(GUEST, token, due)
        val interval = MonsterDraw.drawIntervalMs(state.config, MonsterDraw.unitRoll("$salt|$key|next"))
        editLedger { it.withNextDraw(now + interval, token, now) }
        if (state.ledger.encounters.any { it.key == key }) return
        val aliveRandom = state.ledger.encounters.count {
            it.ownerUid == null && it.source == MonsterEncounter.Source.RANDOM && it.isAlive
        }
        val decision = GuestSpawnPolicy.decide(
            isCompletion = false, key = key, salt = salt, config = state.config, progress = state.ledger.guest,
            aliveRandom = aliveRandom, nowMs = now, appVersion = env.appVersion,
        )
        if (decision !is GuestSpawnPolicy.Outcome.Spawn) return
        val pick = minOf(candidates.size - 1, (MonsterDraw.unitRoll("$salt|$key|cand") * candidates.size).toInt())
        val (provider, windowLabel) = candidates[pick]
        addGuestEncounter(
            key = key, source = MonsterEncounter.Source.RANDOM, provider = provider, windowLabel = windowLabel,
            resetBucketMs = null, drawAtMs = due, entry = decision.entry, now = now,
            expiresAtMs = now + state.config.random.lifetimeMinutes * 60_000L,
            anchor = anchors[windowLabel]?.let { agent.id to it },
        )
    }

    private fun addGuestEncounter(
        key: String,
        source: MonsterEncounter.Source,
        provider: String,
        windowLabel: String,
        resetBucketMs: Long?,
        drawAtMs: Long?,
        entry: MonsterCatalogEntry,
        now: Long,
        expiresAtMs: Long,
        anchor: Pair<UUID, String>?,
    ) {
        val encounter = MonsterEncounter(
            key = key, ownerUid = null, source = source, provider = provider, windowLabel = windowLabel,
            resetBucketMs = resetBucketMs, drawAtMs = drawAtMs, monsterId = entry.id, maxHp = entry.hp,
            hp = entry.hp, state = MonsterEncounter.State.ALIVE, spawnedAtMs = now, expiresAtMs = expiresAtMs,
            agentId = anchor?.first, displayLabel = anchor?.second, position = MonsterDraw.position(key),
        )
        editLedger {
            if (it.encounters.any { e -> e.key == key }) return@editLedger it
            it.copy(encounters = it.encounters + encounter, guest = GuestSpawnPolicy.recordSpawn(it.guest, now))
        }
        log(AnalyticsEvent.MonsterSpawn(entry.id, source))
    }

    /** Applies the server's spawn answer. */
    private fun apply(
        result: CallResult<SpawnResult>,
        key: String,
        owner: String,
        anchor: Pair<UUID, String>?,
        now: Long,
    ) {
        when (result) {
            is CallResult.Success -> when (val value = result.value) {
                is SpawnResult.Encounter -> {
                    val dto = value.encounter
                    val state = when (dto.state) {
                        "alive" -> MonsterEncounter.State.ALIVE
                        "defeated" -> MonsterEncounter.State.DEFEATED
                        else -> MonsterEncounter.State.EXPIRED
                    }
                    val encounter = MonsterEncounter(
                        key = dto.key, ownerUid = owner,
                        source = MonsterEncounter.Source.fromWireId(dto.source) ?: MonsterEncounter.Source.COMPLETION,
                        provider = dto.provider, windowLabel = dto.windowLabel, resetBucketMs = dto.resetBucketMs,
                        drawAtMs = dto.drawAtMs, monsterId = dto.monsterId, maxHp = dto.maxHp,
                        hp = if (state == MonsterEncounter.State.ALIVE) dto.maxHp else 0, state = state,
                        spawnedAtMs = dto.spawnedAtMs, expiresAtMs = dto.expiresAtMs, defeatedAtMs = dto.defeatedAtMs,
                        awardedScore = dto.awardedScore, agentId = anchor?.first, displayLabel = anchor?.second,
                        position = MonsterDraw.position(dto.key),
                    )
                    var added = false
                    editLedger {
                        if (it.encounters.any { e -> e.key == dto.key }) return@editLedger it
                        added = true
                        it.copy(encounters = it.encounters + encounter)
                    }
                    if (added && state == MonsterEncounter.State.ALIVE) {
                        log(AnalyticsEvent.MonsterSpawn(dto.monsterId, encounter.source))
                    }
                }
                SpawnResult.None -> setRetry(key, now + 600_000L)
            }
            is CallResult.Failure -> when (val error = result.error) {
                is CallError.Server -> when (error.code) {
                    "consent_required" -> {
                        edit { it.copy(consentRequested = true) }
                        setRetry(key, now + 600_000L)
                    }
                    "daily_cap", "agent_limit" -> setRetry(key, now + 3_600_000L)
                    "invalid_reset", "too_frequent", "invalid_argument", "suspended", "profile_required" ->
                        setRetry(key, Long.MAX_VALUE)
                    else -> setRetry(key, now + 300_000L)
                }
                else -> setRetry(key, now + 120_000L)
            }
        }
    }

    private fun canRequest(key: String, now: Long): Boolean = synchronized(lock) {
        key !in inFlight && (retryAfter[key] ?: 0L) <= now
    }

    private fun setRetry(key: String, atMs: Long) = synchronized(lock) { retryAfter[key] = atMs }

    private suspend fun <T> inFlight(key: String, block: suspend () -> T): T {
        synchronized(lock) { inFlight += key }
        return try {
            block()
        } finally {
            synchronized(lock) { inFlight -= key }
        }
    }

    /**
     * A monster received elsewhere (another device) has no window here yet: it sticks to the first one
     * seen. One already anchored stays, so a monster never wanders between windows sharing a reset.
     */
    private fun anchorIfNeeded(key: String, agentId: UUID, label: String) = editLedger { ledger ->
        ledger.copy(
            encounters = ledger.encounters.map {
                if (it.key == key && it.agentId == null) it.copy(agentId = agentId, displayLabel = label) else it
            },
        )
    }

    private fun expireFinished(now: Long) = editLedger { ledger ->
        ledger.copy(
            encounters = ledger.encounters.map {
                if (it.isAlive && it.expiresAtMs <= now) it.copy(state = MonsterEncounter.State.EXPIRED, hp = 0) else it
            },
        ).pruned()
    }

    // MARK: Hits and defeats

    /** One tap on a gauge row with a monster (HP − 1). Taps inside the debounce interval are ignored. */
    fun hit(key: String): HitResult {
        if (env.isDemo()) return hitDemo(key)
        val now = env.nowMs()
        var defeated: MonsterEncounter? = null
        var score = 0
        val result = synchronized(lock) {
            val state = _state.value
            val index = state.ledger.encounters.indexOfFirst { it.key == key }
            val encounter = state.ledger.encounters.getOrNull(index)
            if (encounter == null || !encounter.isAlive || encounter.expiresAtMs <= now) return HitResult.Ignored
            val last = lastHitMs[key]
            if (last != null && now - last < state.config.tapDebounceMs) return HitResult.Ignored
            lastHitMs[key] = now
            val remaining = encounter.hp - 1
            var ledger = state.ledger
            val updated = if (remaining > 0) {
                encounter.copy(hp = remaining)
            } else {
                val entry = state.config.entry(encounter.monsterId)
                score = entry?.score ?: 0
                defeated = encounter
                if (encounter.ownerUid == null) {
                    ledger = ledger.copy(
                        guest = GuestSpawnPolicy.recordKill(ledger.guest, entry, encounter.monsterId, now),
                    )
                    encounter.copy(hp = 0, state = MonsterEncounter.State.DEFEATED, defeatedAtMs = now, awardedScore = score)
                } else {
                    encounter.copy(hp = 0, state = MonsterEncounter.State.DEFEATED, defeatedAtMs = now, pendingDefeat = true)
                }
            }
            ledger = ledger.copy(encounters = ledger.encounters.toMutableList().also { it[index] = updated })
            _state.value = state.copy(ledger = ledger, lastHitAtMs = state.lastHitAtMs + (key to now))
            if (remaining > 0) HitResult.Damaged(remaining) else HitResult.Defeated
        }
        defeated?.let { encounter ->
            log(AnalyticsEvent.MonsterKill(encounter.monsterId, encounter.maxHp, score, encounter.source))
        }
        scope.launch {
            if (defeated?.ownerUid != null) sendDefeat(key)
            save()
        }
        return result
    }

    /** Confirms a defeat with the server. Offline, it stays pending and goes again on the next foreground refresh. */
    suspend fun sendDefeat(key: String) {
        val api = api ?: return
        val defeatedAt = synchronized(lock) {
            if (key in inFlight) return
            val encounter = _state.value.ledger.encounters.firstOrNull { it.key == key } ?: return
            if (!encounter.pendingDefeat) return
            val at = encounter.defeatedAtMs ?: return
            inFlight += key
            at
        }
        val result = try {
            api.defeat(key, defeatedAt)
        } finally {
            synchronized(lock) { inFlight -= key }
        }
        when (result) {
            is CallResult.Success -> {
                val (dto, stats) = result.value
                edit { state ->
                    state.copy(
                        // An expired defeat carries no stats: keep the current ones.
                        stats = stats ?: state.stats,
                        ledger = state.ledger.copy(
                            encounters = state.ledger.encounters.map {
                                if (it.key != key) return@map it
                                when {
                                    dto == null -> it.copy(pendingDefeat = false)
                                    dto.state == "expired" -> it.copy(
                                        pendingDefeat = false,
                                        state = MonsterEncounter.State.EXPIRED,
                                        awardedScore = 0,
                                    )
                                    else -> it.copy(pendingDefeat = false, awardedScore = dto.awardedScore)
                                }
                            },
                        ),
                    )
                }
            }
            // A defeat the server refused (expired, unknown key, …) is not sent again.
            is CallResult.Failure -> if (result.error is CallError.Server) {
                editLedger { ledger ->
                    ledger.copy(encounters = ledger.encounters.map { if (it.key == key) it.copy(pendingDefeat = false) else it })
                }
            }
        }
        save()
    }

    private suspend fun retryPendingDefeats(owner: String?) {
        owner ?: return
        _state.value.ledger.encounters.filter { it.ownerUid == owner && it.pendingDefeat }.map { it.key }
            .forEach { sendDefeat(it) }
    }

    // MARK: Drawing

    /** What a window draws: living monsters and defeated ones still wearing their halo. Other owners' monsters stay hidden. */
    fun encounters(agentId: UUID, provider: AgentProvider, window: UsageWindow): List<MonsterEncounter> {
        if (!env.crittersEnabled()) return emptyList()
        return visible(_state.value, agentId, provider, window, env.nowMs())
    }

    private fun hitDemo(key: String): HitResult {
        if (!key.startsWith(DEMO_PREFIX)) return HitResult.Ignored
        val entry = demoEntry(key)
        val now = env.nowMs()
        return synchronized(lock) {
            val state = _state.value
            val current = state.demoHp[key] ?: entry.hp
            if (current <= 0) return HitResult.Ignored
            val last = lastHitMs[key]
            if (last != null && now - last < state.config.tapDebounceMs) return HitResult.Ignored
            lastHitMs[key] = now
            _state.value = state.copy(
                demoHp = state.demoHp + (key to current - 1),
                lastHitAtMs = state.lastHitAtMs + (key to now),
            )
            if (current - 1 > 0) HitResult.Damaged(current - 1) else HitResult.Defeated
        }
    }

    // MARK: Guild

    /**
     * Reads a tab's top 10 and the player's rank, unless they were read within five minutes ([force] reads
     * now). The week tab reads again at once when the week has turned (Monday 00:00 KST).
     */
    suspend fun refreshGuild(scope: LeaderboardScope, force: Boolean = false) {
        val api = api ?: return
        if (env.isDemo()) return
        val now = env.nowMs()
        val weekId = MonsterDraw.kstWeekId(now)
        val stale = scope == LeaderboardScope.WEEK && leaderboardWeekId != weekId
        val loadedAt = _state.value.leaderboardLoadedAtMs[scope]
        if (force || stale || loadedAt == null || now - loadedAt > GUILD_CACHE_MS) {
            val list = api.leaderboardTop(scope, weekId)
            if (list != null && scope == LeaderboardScope.WEEK) leaderboardWeekId = weekId
            edit {
                if (list != null) {
                    it.copy(
                        leaderboards = it.leaderboards + (scope to list),
                        leaderboardLoadedAtMs = it.leaderboardLoadedAtMs + (scope to now),
                        leaderboardFailed = it.leaderboardFailed - scope,
                    )
                } else {
                    it.copy(leaderboardFailed = it.leaderboardFailed + scope)
                }
            }
        }
        if (activeOwner == null) return
        val rankAt = rankLoadedAtMs
        if (!force && !stale && rankAt != null && now - rankAt <= GUILD_CACHE_MS) return
        val result = api.myRank()
        if (result is CallResult.Success) {
            edit { it.copy(rank = result.value, stats = result.value.stats) }
            rankLoadedAtMs = now
        }
    }

    // MARK: Bestiary

    /** Reads the account's defeats per species as the profile bestiary opens (one document GET), showing the last values meanwhile. */
    suspend fun refreshBestiary() {
        val api = api ?: return
        if (env.isDemo()) return
        val uid = account.uid ?: return
        val switched = bestiaryOwner != uid
        bestiaryOwner = uid
        edit { if (switched) it.copy(bestiaryKills = null, bestiaryFailed = false) else it.copy(bestiaryFailed = false) }
        val kills = api.myKills(uid)
        if (account.uid != uid || bestiaryOwner != uid) return
        edit {
            when {
                kills != null -> it.copy(bestiaryKills = kills)
                it.bestiaryKills == null -> it.copy(bestiaryFailed = true)
                else -> it
            }
        }
    }

    /** The bestiary rows under [state]'s config. Null until the defeats have been read. */
    fun bestiaryRows(state: MonsterState = _state.value): List<BestiaryRow>? =
        state.bestiaryKills?.let { MonsterBestiary.rows(state.config, it, env.appVersion) }

    // MARK: Account deletion, config, saving

    /**
     * A deleted account's monster records go from this device too. The device session ledger stays: it
     * never leaves the device, and keeping it is the point.
     */
    fun forgetAccount(uid: String) {
        val forgetBestiary = bestiaryOwner == uid
        if (forgetBestiary) bestiaryOwner = null
        edit { state ->
            state.copy(
                ledger = state.ledger.copy(
                    encounters = state.ledger.encounters.filterNot { it.ownerUid == uid },
                    mergedUids = state.ledger.mergedUids - uid,
                ),
                stats = null,
                rank = null,
                bestiaryKills = if (forgetBestiary) null else state.bestiaryKills,
                bestiaryFailed = if (forgetBestiary) false else state.bestiaryFailed,
            )
        }
        rankLoadedAtMs = null
        scope.launch { save() }
    }

    /** Re-reads `config/monster` at most hourly and caches it. */
    private suspend fun refreshConfigIfNeeded() {
        val fetch = fetchConfig ?: return
        if (api == null) return
        val now = env.nowMs()
        val last = configFetchedAtMs
        if (last != null && now - last <= CONFIG_REFRESH_MS) return
        configFetchedAtMs = now
        attempt { preferences.saveConfigFetchedAt(now) }
        val payload = attempt { fetch() } ?: return
        val fresh = MonsterConfigDecoder.decodePayload(payload) ?: return
        edit { it.copy(config = fresh) }
        attempt { preferences.saveConfig(payload) }
    }

    private suspend fun save() {
        if (env.isDemo()) return
        // Read inside the lock so the last writer always writes the newest ledger.
        saveMutex.withLock { attempt { storage.save(_state.value.ledger) } }
    }

    private suspend fun <T> attempt(block: suspend () -> T): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    companion object {
        /** The terms version the monster hunt needs (consent). */
        const val TERMS_VERSION = 2

        /** Config re-read interval. */
        const val CONFIG_REFRESH_MS = 3_600_000L

        /** Leaderboard and rank cache. */
        const val GUILD_CACHE_MS = 300_000L

        /** How long a defeated random monster's ghost stays listed (crossing the bar takes far less). */
        const val RANDOM_GHOST_MS = 180_000L

        /** Random spawn candidates sent at most. */
        const val MAX_CANDIDATES = 20

        /** The uid used in guest keys (local only). */
        const val GUEST = "guest"

        private const val DEMO_PREFIX = "demo|"

        /**
         * The monsters a window draws, from one state snapshot (pure). Demo mode draws one memory-only
         * sample monster per 100% gauge instead of the ledger.
         */
        fun visible(
            state: MonsterState,
            agentId: UUID,
            provider: AgentProvider,
            window: UsageWindow,
            nowMs: Long,
        ): List<MonsterEncounter> {
            if (state.isDemo) return demoEncounters(state, agentId, window, nowMs)
            val keyLabel = MonsterSessionKey.keyLabel(provider, window.label)
            return state.ledger.encounters.filter { e ->
                if (e.ownerUid != state.owner || e.state == MonsterEncounter.State.EXPIRED || e.expiresAtMs <= nowMs) {
                    return@filter false
                }
                // A random monster's ghost walks off the right edge; old ones leave the list as well.
                val defeatedAt = e.defeatedAtMs
                if (e.source == MonsterEncounter.Source.RANDOM && e.state == MonsterEncounter.State.DEFEATED &&
                    defeatedAt != null && nowMs - defeatedAt > RANDOM_GHOST_MS
                ) {
                    return@filter false
                }
                val anchored = e.agentId
                if (anchored != null) anchored == agentId && e.displayLabel == window.label
                else e.provider == provider.wireId && e.windowLabel == keyLabel
            }
        }

        /** The sample monster for a demo key. The bundled catalog has no minimum versions, so no app version is needed. */
        private fun demoEntry(key: String): MonsterCatalogEntry =
            MonsterDraw.pick(MonsterConfig.BUNDLED_DEFAULT, appVersion = "0", commonOnly = false, roll = MonsterDraw.unitRoll(key))

        private fun demoEncounters(
            state: MonsterState,
            agentId: UUID,
            window: UsageWindow,
            nowMs: Long,
        ): List<MonsterEncounter> {
            if (!MonsterEligibility.isCompleted(window, Instant.ofEpochMilli(nowMs))) return emptyList()
            val key = "$DEMO_PREFIX$agentId|${window.label}"
            val entry = demoEntry(key)
            val hp = (state.demoHp[key] ?: entry.hp).coerceAtLeast(0)
            return listOf(
                MonsterEncounter(
                    key = key, ownerUid = "demo", source = MonsterEncounter.Source.COMPLETION, provider = "",
                    windowLabel = window.label, resetBucketMs = null, drawAtMs = null, monsterId = entry.id,
                    maxHp = entry.hp, hp = hp,
                    state = if (hp > 0) MonsterEncounter.State.ALIVE else MonsterEncounter.State.DEFEATED,
                    spawnedAtMs = 0L, expiresAtMs = Long.MAX_VALUE,
                    // The last hit is the defeat, so the sample plays the same death as a real one.
                    defeatedAtMs = if (hp > 0) null else state.lastHitAtMs[key],
                    agentId = agentId, displayLabel = window.label, position = 0.5,
                ),
            )
        }
    }
}
