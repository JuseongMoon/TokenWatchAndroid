package com.ScienceFiction.TokenWatchAndroid.store

import com.ScienceFiction.TokenWatchAndroid.domain.Announcement
import com.ScienceFiction.TokenWatchAndroid.domain.AnnouncementFeed
import com.ScienceFiction.TokenWatchAndroid.domain.LocalizedText
import com.ScienceFiction.TokenWatchAndroid.network.announcements.AnnouncementFeedClient.FetchResult
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnouncementStoreTest {
    private val startMs = 1_788_652_800_000L

    private fun announcement(
        id: String,
        publishedAt: Long = startMs,
        priority: Int = 0,
        endAt: Long? = null,
        maxAppVersion: String? = null,
    ) = Announcement(
        id = id, priority = priority, publishedAt = publishedAt, endAt = endAt,
        platform = Announcement.PLATFORM_ANDROID, maxAppVersion = maxAppVersion,
        title = LocalizedText("제목", "Title"), body = LocalizedText("본문", "Body"),
    )

    private fun feedOf(vararg items: Announcement) =
        AnnouncementFeed(schemaVersion = 1, generatedAt = startMs, items = items.toList())

    /** In-memory stand-in for the repository, shared across "relaunches" of the store. */
    private class Backend(var result: FetchResult = FetchResult.Failed) {
        var dismissed: List<String> = emptyList()
        var seen: List<String> = emptyList()
        var cachedFeed: AnnouncementFeed? = null
        var lastSuccessAt: Long? = null
        var fetchCount = 0
        var gate: CompletableDeferred<Unit>? = null

        fun dependencies() = AnnouncementStoreDependencies(
            loadDismissed = { dismissed },
            persistDismissed = { dismissed = it },
            loadSeen = { seen },
            persistSeen = { seen = it },
            loadCachedFeed = { cachedFeed },
            persistCachedFeed = { cachedFeed = it },
            loadLastSuccessAt = { lastSuccessAt },
            persistLastSuccessAt = { lastSuccessAt = it },
            fetchFeed = {
                fetchCount += 1
                gate?.await()
                result
            },
        )
    }

    private fun store(scope: CoroutineScope, backend: Backend, clock: () -> Long) = AnnouncementStore(
        parentScope = scope,
        dependencies = backend.dependencies(),
        now = { Instant.ofEpochMilli(clock()) },
        appVersion = "1.1.0",
    )

    @Test fun cachedFeedPresentsAndListsBeforeAnyFetch() = runBlocking {
        val backend = Backend().apply { cachedFeed = feedOf(announcement("a")) }
        val store = store(this, backend) { startMs }

        store.awaitInitialLoad()
        // The inbox is available from the cache alone — no check() required.
        assertEquals(listOf("a"), store.inbox().map { it.id })

        backend.result = FetchResult.Failed
        store.checkNow()
        assertEquals("a", store.presented.value?.id)
        // A failure with a cache in hand is not "couldn't load".
        assertFalse(store.lastFetchFailed())
        store.close()
    }

    @Test fun successThrottlesForAnHourAndFailureBacksOffForFiveMinutes() = runBlocking {
        val backend = Backend(FetchResult.Loaded(feedOf(announcement("a"))))
        var nowMs = startMs
        val store = store(this, backend) { nowMs }
        store.awaitInitialLoad()

        store.checkNow()
        assertEquals(1, backend.fetchCount)

        nowMs += 59 * 60 * 1000
        store.checkNow()
        assertEquals(1, backend.fetchCount)

        nowMs += 2 * 60 * 1000
        store.checkNow()
        assertEquals(2, backend.fetchCount)

        backend.result = FetchResult.Failed
        nowMs += 61 * 60 * 1000
        store.checkNow()
        assertEquals(3, backend.fetchCount)

        nowMs += 4 * 60 * 1000
        store.checkNow()
        assertEquals(3, backend.fetchCount)

        nowMs += 2 * 60 * 1000
        store.checkNow()
        assertEquals(4, backend.fetchCount)
        store.close()
    }

    @Test fun theFirstCheckOfAProcessAlwaysFetches() = runBlocking {
        val backend = Backend(FetchResult.Loaded(feedOf(announcement("a"))))
        backend.lastSuccessAt = startMs - 1000
        val store = store(this, backend) { startMs }
        store.awaitInitialLoad()

        // A cold start ignores the throttle: a stale success from a previous run is not enough.
        store.checkNow()
        assertEquals(1, backend.fetchCount)
        store.close()
    }

    @Test fun concurrentChecksShareOneFetch() = runBlocking {
        val backend = Backend(FetchResult.Loaded(feedOf(announcement("a"))))
        backend.gate = CompletableDeferred()
        val store = store(this, backend) { startMs }
        store.awaitInitialLoad()

        val first = launch { store.checkNow() }
        val second = launch { store.checkNow() }
        second.join()
        assertEquals(1, backend.fetchCount)
        backend.gate?.complete(Unit)
        first.join()
        store.close()
    }

    @Test fun networkResultNeverReplacesTheCardBeingRead() = runBlocking {
        val backend = Backend().apply { cachedFeed = feedOf(announcement("cached", priority = 1)) }
        backend.result = FetchResult.Loaded(feedOf(announcement("fresh", priority = 9)))
        val store = store(this, backend) { startMs }
        store.awaitInitialLoad()

        store.checkNow()
        assertEquals("cached", store.presented.value?.id)
        assertEquals(listOf("fresh"), store.inbox().map { it.id })
        store.close()
    }

    @Test fun closeHidesForThisLaunchOnlyWhileDismissIsPermanent() = runBlocking {
        val backend = Backend(FetchResult.Loaded(feedOf(announcement("a"))))
        val first = store(this, backend) { startMs }
        first.awaitInitialLoad()
        first.checkNow()
        first.closePresented()
        assertNull(first.presented.value)
        first.checkNow()
        assertNull(first.presented.value)
        assertTrue(backend.dismissed.isEmpty())
        first.close()

        val relaunched = store(this, backend) { startMs }
        relaunched.awaitInitialLoad()
        relaunched.checkNow()
        assertEquals("a", relaunched.presented.value?.id)

        relaunched.dismissPresentedForever()
        assertEquals(listOf("a"), backend.dismissed)
        relaunched.close()

        val afterDismiss = store(this, backend) { startMs }
        afterDismiss.awaitInitialLoad()
        afterDismiss.checkNow()
        assertNull(afterDismiss.presented.value)
        // Dismissing the popup does not erase history.
        assertEquals(listOf("a"), afterDismiss.inbox().map { it.id })
        afterDismiss.close()
    }

    @Test fun markSeenIsIndependentOfThePopup() = runBlocking {
        val backend = Backend(FetchResult.Loaded(feedOf(announcement("a"), announcement("b", publishedAt = startMs - 1))))
        val store = store(this, backend) { startMs }
        store.awaitInitialLoad()
        store.checkNow()
        assertEquals(2, store.unreadCount())

        store.markSeen("a")
        assertEquals(1, store.unreadCount())
        assertEquals(listOf("a"), backend.seen)
        // Reading in the inbox must not switch the popup off.
        assertTrue(backend.dismissed.isEmpty())
        assertEquals("a", store.presented.value?.id)
        store.close()

        val relaunched = store(this, backend) { startMs }
        relaunched.awaitInitialLoad()
        assertEquals(listOf("a"), relaunched.seen.value)
        relaunched.close()
    }

    @Test fun unreadCountsOnlyLiveAnnouncements() = runBlocking {
        val backend = Backend(
            FetchResult.Loaded(
                feedOf(
                    announcement("live"),
                    announcement("expired", publishedAt = startMs - 2, endAt = startMs - 1),
                    announcement("outOfRange", publishedAt = startMs - 3, maxAppVersion = "1.0.0"),
                ),
            ),
        )
        val store = store(this, backend) { startMs }
        store.awaitInitialLoad()
        store.checkNow()

        assertEquals(3, store.inbox().size)
        // Otherwise the first launch after an update lights the badge with the back catalogue.
        assertEquals(1, store.unreadCount())
        store.close()
    }

    @Test fun emptyResponseClearsTheCacheAndCountsAsSuccess() = runBlocking {
        val backend = Backend(FetchResult.Empty).apply { cachedFeed = feedOf(announcement("gone")) }
        val store = store(this, backend) { startMs }
        store.awaitInitialLoad()
        store.checkNow()

        assertNull(backend.cachedFeed)
        assertTrue(store.inbox().isEmpty())
        assertEquals(startMs, backend.lastSuccessAt)
        assertFalse(store.lastFetchFailed())
        store.close()
    }

    @Test fun failureWithoutAnyFeedIsReportedToTheInbox() = runBlocking {
        val backend = Backend(FetchResult.Failed)
        val store = store(this, backend) { startMs }
        store.awaitInitialLoad()
        assertFalse(store.lastFetchFailed())

        store.checkNow()
        assertTrue(store.lastFetchFailed())
        store.close()
    }

    @Test fun idListsAreCappedOldestFirst() = runBlocking {
        val backend = Backend()
        val store = store(this, backend) { startMs }
        store.awaitInitialLoad()

        repeat(AnnouncementStore.ID_CAP + 5) { store.markSeen("id$it") }

        assertEquals(AnnouncementStore.ID_CAP, backend.seen.size)
        assertEquals("id5", backend.seen.first())
        assertEquals("id204", backend.seen.last())
        store.close()
    }
}
