package com.ScienceFiction.TokenWatchAndroid.domain

/**
 * When to ask for a store review, mirroring the iOS `ReviewPromptPolicy`.
 *
 * Play quietly caps how often its own sheet appears, so the one request an install gets is spent
 * on someone who has already seen the app work: activated, a few days in, opened repeatedly, and
 * looking at a screen where every card loaded cleanly.
 */
object ReviewPromptPolicy {
    const val MIN_DAYS_SINCE_FIRST_LAUNCH = 3
    const val MIN_LAUNCHES = 5

    private const val DAY_MILLIS = 86_400_000L

    fun shouldPrompt(
        activated: Boolean,
        firstLaunchAt: Long?,
        launchCount: Int,
        alreadyPrompted: Boolean,
        allSnapshotsHealthy: Boolean,
        now: Long,
    ): Boolean {
        if (!activated || alreadyPrompted || !allSnapshotsHealthy) return false
        if (launchCount < MIN_LAUNCHES) return false
        val first = firstLaunchAt ?: return false
        return now - first >= MIN_DAYS_SINCE_FIRST_LAUNCH * DAY_MILLIS
    }
}

/** The counters [ReviewPromptPolicy] reads. Persisted outside AppSettings — it is not a setting. */
data class ReviewPromptState(
    val firstLaunchAt: Long? = null,
    val launchCount: Int = 0,
    val prompted: Boolean = false,
) {
    /**
     * One cold start. The first launch timestamp is written once; users upgrading from an earlier
     * version start counting here, which is the intent — a few days of the new build first.
     */
    fun recordingLaunch(now: Long): ReviewPromptState = copy(
        firstLaunchAt = firstLaunchAt ?: now,
        launchCount = launchCount + 1,
    )
}
