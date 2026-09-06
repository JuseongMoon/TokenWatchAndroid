package com.ScienceFiction.TokenWatchAndroid.store

import com.ScienceFiction.TokenWatchAndroid.BuildConfig
import com.ScienceFiction.TokenWatchAndroid.data.AnnouncementRepository
import com.ScienceFiction.TokenWatchAndroid.domain.Announcement
import com.ScienceFiction.TokenWatchAndroid.domain.AnnouncementFeed
import com.ScienceFiction.TokenWatchAndroid.domain.AnnouncementSelector
import com.ScienceFiction.TokenWatchAndroid.network.announcements.AnnouncementFeedClient
import java.io.Closeable
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Storage and network boundary, mirroring [AgentStoreDependencies]: suspend lambdas only, so the
 * state machine below can be exercised from a plain JVM test.
 */
data class AnnouncementStoreDependencies(
    val loadDismissed: suspend () -> List<String>,
    val persistDismissed: suspend (List<String>) -> Unit,
    val loadSeen: suspend () -> List<String>,
    val persistSeen: suspend (List<String>) -> Unit,
    val loadCachedFeed: suspend () -> AnnouncementFeed?,
    val persistCachedFeed: suspend (AnnouncementFeed?) -> Unit,
    val loadLastSuccessAt: suspend () -> Long?,
    val persistLastSuccessAt: suspend (Long) -> Unit,
    val fetchFeed: suspend () -> AnnouncementFeedClient.FetchResult,
)

/**
 * Owns the announcement popup and inbox state: when to fetch (throttling), what to show (the
 * selection rules) and where the user's close/dismiss choices live.
 *
 * Kept entirely separate from [AgentStore] so fetching announcements can never disturb the usage
 * refresh path. The single entry point is [check], called on every foreground transition:
 *  - the first call of a process always fetches; later ones are throttled to one hour after a
 *    success, and held off for five minutes after a failure;
 *  - the cached feed decides what to present immediately, so a popup appears offline too;
 *  - a network result is only allowed to replace the presented card when nothing is on screen —
 *    swapping the card a user is reading would be hostile;
 *  - failures are silent, never blocking startup.
 *
 * [closePresented] hides an announcement for this process only; [dismissPresentedForever] is
 * permanent. [markSeen] is independent of both: reading an item in the inbox marks it read but
 * leaves the popup alone, because those are separate decisions.
 */
class AnnouncementStore(
    parentScope: CoroutineScope,
    private val dependencies: AnnouncementStoreDependencies,
    private val now: () -> Instant = Instant::now,
    private val appVersion: String = BuildConfig.VERSION_NAME,
) : Closeable {

    constructor(
        scope: CoroutineScope,
        repository: AnnouncementRepository,
        client: AnnouncementFeedClient,
        now: () -> Instant = Instant::now,
    ) : this(
        parentScope = scope,
        dependencies = AnnouncementStoreDependencies(
            loadDismissed = repository::loadDismissed,
            persistDismissed = repository::saveDismissed,
            loadSeen = repository::loadSeen,
            persistSeen = repository::saveSeen,
            loadCachedFeed = repository::loadCachedFeed,
            persistCachedFeed = repository::saveCachedFeed,
            loadLastSuccessAt = repository::loadLastSuccessAt,
            persistLastSuccessAt = repository::saveLastSuccessAt,
            fetchFeed = client::fetch,
        ),
        now = now,
    )

    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob())
    private val mutex = Mutex()
    private val initialLoad = CompletableDeferred<Unit>()

    private val _feed = MutableStateFlow<AnnouncementFeed?>(null)
    private val _seen = MutableStateFlow<List<String>>(emptyList())
    private val _presented = MutableStateFlow<Announcement?>(null)

    /**
     * Observed because the inbox distinguishes "nothing published" from "couldn't load"; the other
     * bookkeeping below is not observed because no UI reads it.
     */
    private val _lastAttemptFailedAt = MutableStateFlow<Long?>(null)

    /** Last feed obtained, from cache or network. The inbox list is derived from it. */
    val feed: StateFlow<AnnouncementFeed?> = _feed.asStateFlow()
    val seen: StateFlow<List<String>> = _seen.asStateFlow()

    /** The announcement to overlay, or null. */
    val presented: StateFlow<Announcement?> = _presented.asStateFlow()
    val lastAttemptFailedAt: StateFlow<Long?> = _lastAttemptFailedAt.asStateFlow()

    private var dismissed: List<String> = emptyList()
    private var closedThisLaunch: Set<String> = emptySet()
    private var lastSuccessAt: Long? = null
    private var hasCheckedThisLaunch = false
    private var inFlight = false

    private val loadJob: Job = scope.launch {
        dismissed = dependencies.loadDismissed()
        _seen.value = dependencies.loadSeen()
        // Decoded once here; every later decision reads this feed.
        _feed.value = dependencies.loadCachedFeed()
        lastSuccessAt = dependencies.loadLastSuccessAt()
        initialLoad.complete(Unit)
    }

    suspend fun awaitInitialLoad() = initialLoad.await()

    /** Fire-and-forget entry point for lifecycle callbacks. */
    fun check() {
        scope.launch { checkNow() }
    }

    suspend fun checkNow() {
        awaitInitialLoad()
        val shouldFetch = mutex.withLock {
            // Decide from the cache first: no network wait, and it works offline.
            if (_presented.value == null) _presented.value = pick(_feed.value)
            val nowMs = now().toEpochMilli()
            val throttled = hasCheckedThisLaunch && (
                lastSuccessAt?.let { nowMs - it < MIN_REFETCH_INTERVAL_MS } == true ||
                    _lastAttemptFailedAt.value?.let { nowMs - it < FAILURE_BACKOFF_MS } == true
                )
            when {
                throttled || inFlight -> false
                else -> {
                    hasCheckedThisLaunch = true
                    inFlight = true
                    true
                }
            }
        }
        if (!shouldFetch) return
        val result = runCatching { dependencies.fetchFeed() }
            .getOrDefault(AnnouncementFeedClient.FetchResult.Failed)
        apply(result)
    }

    private suspend fun apply(result: AnnouncementFeedClient.FetchResult) {
        val nowMs = now().toEpochMilli()
        mutex.withLock { inFlight = false }
        when (result) {
            is AnnouncementFeedClient.FetchResult.Loaded -> {
                _feed.value = result.feed
                dependencies.persistCachedFeed(result.feed)
                lastSuccessAt = nowMs
                dependencies.persistLastSuccessAt(nowMs)
                _lastAttemptFailedAt.value = null
                if (_presented.value == null) _presented.value = pick(result.feed)
            }

            AnnouncementFeedClient.FetchResult.Empty -> {
                _feed.value = null
                dependencies.persistCachedFeed(null)
                lastSuccessAt = nowMs
                dependencies.persistLastSuccessAt(nowMs)
                _lastAttemptFailedAt.value = null
            }

            AnnouncementFeedClient.FetchResult.Failed -> _lastAttemptFailedAt.value = nowMs
        }
    }

    /** The inbox list. Not cached: a scheduled announcement then appears the moment it goes live. */
    fun inbox(): List<Announcement> = AnnouncementSelector.inbox(_feed.value, now().toEpochMilli())

    /**
     * Unread badge count. Only counts announcements that are currently live, otherwise the first
     * launch after an update would light up the badge with the entire back catalogue.
     */
    fun unreadCount(): Int = inbox().count(::isUnread)

    fun isUnread(announcement: Announcement): Boolean {
        if (announcement.id in _seen.value) return false
        return AnnouncementSelector.isEligible(announcement, now().toEpochMilli(), appVersion)
    }

    /** True only when no feed was ever obtained; a cached one still lists fine while offline. */
    fun lastFetchFailed(): Boolean = _feed.value == null && _lastAttemptFailedAt.value != null

    /** [ 닫기 ] — hidden for this process; it returns on the next launch. */
    fun closePresented() {
        val current = _presented.value ?: return
        closedThisLaunch = closedThisLaunch + current.id
        _presented.value = null
    }

    /** [ 다시 열지 않기 ] — excluded permanently on this device. */
    suspend fun dismissPresentedForever() {
        val current = _presented.value ?: return
        closedThisLaunch = closedThisLaunch + current.id
        _presented.value = null
        if (current.id in dismissed) return
        dismissed = appendCapped(current.id, dismissed)
        dependencies.persistDismissed(dismissed)
    }

    /** Read marker only: never touches dismissed, closedThisLaunch or the presented card. */
    suspend fun markSeen(id: String) {
        if (id in _seen.value) return
        val next = appendCapped(id, _seen.value)
        _seen.value = next
        dependencies.persistSeen(next)
    }

    /** Exposed for tests and debugging. */
    fun dismissedIds(): List<String> = dismissed

    override fun close() {
        loadJob.cancel()
        scope.cancel()
    }

    private fun pick(feed: AnnouncementFeed?): Announcement? = AnnouncementSelector.pick(
        feed = feed,
        nowMs = now().toEpochMilli(),
        appVersion = appVersion,
        dismissed = dismissed.toSet(),
        closedThisLaunch = closedThisLaunch,
    )

    private fun appendCapped(id: String, list: List<String>): List<String> {
        val next = list + id
        return if (next.size > ID_CAP) next.takeLast(ID_CAP) else next
    }

    companion object {
        /** Shared cap for dismissed and seen; announcements are rare enough never to reach it. */
        const val ID_CAP = 200
        const val MIN_REFETCH_INTERVAL_MS = 60L * 60 * 1000
        const val FAILURE_BACKOFF_MS = 5L * 60 * 1000
    }
}
