package com.ScienceFiction.TokenWatchAndroid.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewPromptPolicyTest {
    private val now = 1_800_000_000_000L
    private val threeDaysAgo = now - 3 * 86_400_000L

    private fun shouldPrompt(
        activated: Boolean = true,
        firstLaunchAt: Long? = threeDaysAgo,
        launchCount: Int = 5,
        alreadyPrompted: Boolean = false,
        healthy: Boolean = true,
    ) = ReviewPromptPolicy.shouldPrompt(
        activated = activated,
        firstLaunchAt = firstLaunchAt,
        launchCount = launchCount,
        alreadyPrompted = alreadyPrompted,
        allSnapshotsHealthy = healthy,
        now = now,
    )

    @Test
    fun promptsExactlyOnTheBoundary() {
        assertTrue(shouldPrompt())
    }

    @Test
    fun eachMissingConditionBlocks() {
        assertFalse(shouldPrompt(activated = false))
        assertFalse(shouldPrompt(firstLaunchAt = threeDaysAgo + 1))
        assertFalse(shouldPrompt(launchCount = 4))
        assertFalse(shouldPrompt(alreadyPrompted = true))
        assertFalse(shouldPrompt(healthy = false))
    }

    @Test
    fun neverPromptsWithoutAFirstLaunchTimestamp() {
        assertFalse(shouldPrompt(firstLaunchAt = null, launchCount = 99))
    }

    @Test
    fun recordingLaunchKeepsTheFirstTimestampAndCounts() {
        val fresh = ReviewPromptState()
        assertNull(fresh.firstLaunchAt)

        val first = fresh.recordingLaunch(threeDaysAgo)
        val second = first.recordingLaunch(now)

        assertEquals(threeDaysAgo, second.firstLaunchAt)
        assertEquals(2, second.launchCount)
        assertFalse(second.prompted)
    }
}
