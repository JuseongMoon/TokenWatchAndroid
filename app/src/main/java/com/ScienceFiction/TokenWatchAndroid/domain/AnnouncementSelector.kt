package com.ScienceFiction.TokenWatchAndroid.domain

/**
 * Pure rules deciding what to show. Knows nothing about the network or storage.
 *
 * The popup and the inbox deliberately disagree: [pick] answers "who should this interrupt right
 * now", while [inbox] answers "who is allowed to read this afterwards". That is why the list
 * ignores [Announcement.endAt], the app-version range and the dismissed set — otherwise taking an
 * announcement down, or shipping a newer build, would erase history the user already saw.
 */
object AnnouncementSelector {

    /**
     * The single announcement to present, or null.
     *
     * @param dismissed permanently excluded ids ("don't show again"), persisted on the device.
     * @param closedThisLaunch ids closed during this process only; they return on the next launch.
     */
    fun pick(
        feed: AnnouncementFeed?,
        nowMs: Long,
        appVersion: String,
        dismissed: Set<String>,
        closedThisLaunch: Set<String>,
    ): Announcement? {
        if (feed == null || feed.schemaVersion != AnnouncementFeed.SUPPORTED_SCHEMA_VERSION) return null
        return feed.items
            .filter {
                isEligible(it, nowMs, appVersion) &&
                    it.id !in dismissed &&
                    it.id !in closedThisLaunch
            }
            .sortedWith(compareByDescending<Announcement> { it.priority }.thenByDescending { it.publishedAt })
            .firstOrNull()
    }

    /** Everything the inbox lists, newest first. Ties break on id so the order stays stable. */
    fun inbox(feed: AnnouncementFeed?, nowMs: Long): List<Announcement> {
        if (feed == null || feed.schemaVersion != AnnouncementFeed.SUPPORTED_SCHEMA_VERSION) return emptyList()
        return feed.items
            .filter { isListable(it, nowMs) }
            .sortedWith(compareByDescending<Announcement> { it.publishedAt }.thenBy { it.id })
    }

    /** Platform plus "has it gone public yet" — scheduled items stay hidden until their start. */
    fun isListable(announcement: Announcement, nowMs: Long): Boolean {
        val start = announcement.startAt
        if (start != null && nowMs < start) return false
        return announcement.platform == Announcement.PLATFORM_ANDROID ||
            announcement.platform == Announcement.PLATFORM_ALL
    }

    /** Window, platform and app-version conditions; nothing about what this user has already done. */
    fun isEligible(announcement: Announcement, nowMs: Long, appVersion: String): Boolean {
        if (!isListable(announcement, nowMs)) return false
        val end = announcement.endAt
        // Exclusive: an announcement whose end has arrived is over.
        if (end != null && nowMs >= end) return false
        return versionInRange(appVersion, announcement.minAppVersion, announcement.maxAppVersion)
    }

    /** Inclusive range check; null means unbounded. */
    fun versionInRange(version: String, min: String?, max: String?): Boolean {
        if (min != null && compareVersions(version, min) < 0) return false
        if (max != null && compareVersions(version, max) > 0) return false
        return true
    }

    /**
     * Segment-wise numeric comparison so `1.0.9 < 1.0.10`, which a plain string compare gets wrong.
     * Missing segments count as 0, and a non-numeric segment degrades to 0 rather than throwing —
     * these strings come from the server and must never crash a client.
     */
    private fun compareVersions(left: String, right: String): Int {
        val a = left.split(".")
        val b = right.split(".")
        for (index in 0 until maxOf(a.size, b.size)) {
            val segment = a.getOrNull(index)?.toIntOrNull() ?: 0
            val other = b.getOrNull(index)?.toIntOrNull() ?: 0
            if (segment != other) return segment.compareTo(other)
        }
        return 0
    }
}
