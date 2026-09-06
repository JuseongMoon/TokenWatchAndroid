package com.ScienceFiction.TokenWatchAndroid.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Selection rules ported from the iOS `AnnouncementTests` suite. */
class AnnouncementSelectorTest {
    private val nowMs = 1_788_652_800_000L
    private val appVersion = "1.1.0"

    private fun announcement(
        id: String = "a",
        kind: Announcement.Kind = Announcement.Kind.NOTICE,
        priority: Int = 0,
        publishedAt: Long = nowMs,
        startAt: Long? = null,
        endAt: Long? = null,
        platform: String = Announcement.PLATFORM_ANDROID,
        minAppVersion: String? = null,
        maxAppVersion: String? = null,
    ) = Announcement(
        id = id, kind = kind, priority = priority, publishedAt = publishedAt,
        startAt = startAt, endAt = endAt, platform = platform,
        minAppVersion = minAppVersion, maxAppVersion = maxAppVersion,
        title = LocalizedText("제목", "Title"), body = LocalizedText("본문", "Body"),
    )

    private fun feed(vararg items: Announcement, schemaVersion: Int = 1) =
        AnnouncementFeed(schemaVersion = schemaVersion, generatedAt = nowMs, items = items.toList())

    private fun pick(feed: AnnouncementFeed?, dismissed: Set<String> = emptySet(), closed: Set<String> = emptySet()) =
        AnnouncementSelector.pick(feed, nowMs, appVersion, dismissed, closed)

    @Test fun unknownSchemaVersionDiscardsTheWholeFeed() {
        assertNull(pick(feed(announcement(), schemaVersion = 2)))
        assertTrue(AnnouncementSelector.inbox(feed(announcement(), schemaVersion = 2), nowMs).isEmpty())
        assertEquals("a", pick(feed(announcement()))?.id)
    }

    @Test fun startIsInclusiveAndEndIsExclusive() {
        assertEquals("a", pick(feed(announcement(startAt = nowMs)))?.id)
        assertNull(pick(feed(announcement(startAt = nowMs + 1))))
        // The moment the end arrives the announcement is over.
        assertNull(pick(feed(announcement(endAt = nowMs))))
        assertEquals("a", pick(feed(announcement(endAt = nowMs + 1)))?.id)
    }

    @Test fun onlyAndroidAndAllArePresented() {
        assertEquals("a", pick(feed(announcement(platform = Announcement.PLATFORM_ANDROID)))?.id)
        assertEquals("a", pick(feed(announcement(platform = Announcement.PLATFORM_ALL)))?.id)
        assertNull(pick(feed(announcement(platform = "ios"))))
        assertNull(pick(feed(announcement(platform = "web"))))
    }

    @Test fun versionRangeComparesNumericallyNotLexically() {
        assertTrue(AnnouncementSelector.versionInRange("1.0.10", "1.0.9", null))
        assertFalse(AnnouncementSelector.versionInRange("1.0.9", "1.0.10", null))
        assertTrue(AnnouncementSelector.versionInRange("1.0.1", "1.0.1", "1.0.1"))
        assertTrue(AnnouncementSelector.versionInRange("1.1", "1.0.9", null))
        assertFalse(AnnouncementSelector.versionInRange("1.1.0", null, "1.0.999"))
        // Missing segments pad with zero; a non-numeric segment degrades instead of throwing.
        assertTrue(AnnouncementSelector.versionInRange("1.1", "1.1.0", "1.1.0"))
        assertTrue(AnnouncementSelector.versionInRange("1.1.0-beta", "1.1.0", "1.1.0"))
    }

    @Test fun dismissedAndClosedAreExcluded() {
        val single = feed(announcement(id = "a"))
        assertNull(pick(single, dismissed = setOf("a")))
        assertNull(pick(single, closed = setOf("a")))
        assertNull(pick(single, dismissed = setOf("a"), closed = setOf("a")))
        assertEquals("a", pick(single, dismissed = setOf("other"))?.id)
    }

    @Test fun higherPriorityWinsThenRecency() {
        val picked = pick(
            feed(
                announcement(id = "old", priority = 5, publishedAt = nowMs - 10_000),
                announcement(id = "top", priority = 9, publishedAt = nowMs - 50_000),
                announcement(id = "new", priority = 5, publishedAt = nowMs - 1_000),
            ),
        )
        assertEquals("top", picked?.id)
        val tie = pick(
            feed(
                announcement(id = "older", priority = 1, publishedAt = nowMs - 10_000),
                announcement(id = "newer", priority = 1, publishedAt = nowMs - 1_000),
            ),
        )
        assertEquals("newer", tie?.id)
    }

    @Test fun inboxKeepsExpiredAndOutOfVersionRangeButHidesScheduled() {
        val listed = AnnouncementSelector.inbox(
            feed(
                announcement(id = "expired", endAt = nowMs - 1),
                announcement(id = "outOfRange", maxAppVersion = "1.0.0"),
                announcement(id = "scheduled", startAt = nowMs + 1),
                announcement(id = "ios", platform = "ios"),
            ),
            nowMs,
        ).map { it.id }
        // Taken down and out-of-range items stay readable; scheduled and other platforms do not.
        assertEquals(listOf("expired", "outOfRange"), listed)
    }

    @Test fun inboxSortsNewestFirstAndBreaksTiesById() {
        val listed = AnnouncementSelector.inbox(
            feed(
                announcement(id = "mid", publishedAt = nowMs - 5_000),
                announcement(id = "older", publishedAt = nowMs - 9_000),
                announcement(id = "newest", publishedAt = nowMs - 1_000),
            ),
            nowMs,
        ).map { it.id }
        assertEquals(listOf("newest", "mid", "older"), listed)

        val tie = AnnouncementSelector.inbox(
            feed(
                announcement(id = "b", publishedAt = nowMs),
                announcement(id = "a", publishedAt = nowMs),
            ),
            nowMs,
        ).map { it.id }
        assertEquals(listOf("a", "b"), tie)
    }

    @Test fun inboxIgnoresDismissalWhilePickHonoursIt() {
        val single = feed(announcement(id = "a"))
        assertNull(pick(single, dismissed = setOf("a")))
        assertEquals(1, AnnouncementSelector.inbox(single, nowMs).size)
    }

    @Test fun localizedTextFallsBackToTheOtherLanguage() {
        assertEquals("한국어", LocalizedText(ko = "한국어", en = "English").resolved(korean = true))
        assertEquals("English", LocalizedText(ko = "한국어", en = "English").resolved(korean = false))
        assertEquals("English", LocalizedText(ko = "", en = "English").resolved(korean = true))
        assertEquals("한국어", LocalizedText(ko = "한국어", en = null).resolved(korean = false))
        assertEquals("", LocalizedText().resolved(korean = true))
    }
}
