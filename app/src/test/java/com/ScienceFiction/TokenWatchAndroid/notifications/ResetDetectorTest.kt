package com.ScienceFiction.TokenWatchAndroid.notifications

import com.ScienceFiction.TokenWatchAndroid.domain.WindowKind
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResetDetectorTest {
    private val now = Instant.parse("2026-07-16T00:00:00Z")
    private val sessionKey = "agent|Current session"

    @Test
    fun firstObservationOnlyEstablishesBaseline() {
        val current = mapOf(sessionKey to observation(now.plusSeconds(3_600), 1.0))
        val result = ResetDetector.detect(emptyMap(), current, now) { WindowKind.SESSION }

        assertTrue(result.events.isEmpty())
        assertEquals(current, result.baseline)
    }

    @Test
    fun earlyResetTimeAdvanceProducesOneGroupedEvent() {
        val weeklyKey = "agent|Current week"
        val previousReset = now.plusSeconds(3_600)
        val previous = mapOf(
            sessionKey to observation(previousReset, 80.0),
            weeklyKey to observation(previousReset, 50.0),
        )
        val current = mapOf(
            sessionKey to observation(previousReset.plusSeconds(18_000), 0.0),
            weeklyKey to observation(previousReset.plusSeconds(604_800), 0.0),
        )
        val result = ResetDetector.detect(previous, current, now) { key ->
            if (key == sessionKey) WindowKind.SESSION else WindowKind.WEEKLY
        }

        assertEquals(1, result.events.size)
        assertEquals(setOf(WindowKind.SESSION, WindowKind.WEEKLY), result.events.single().kinds)
        assertEquals(listOf("Current session", "Current week"), result.events.single().labels)
        assertEquals(previousReset, result.events.single().fireTime)
    }

    @Test
    fun onTimeResetIsSuppressedBecauseScheduledAlarmOwnsIt() {
        val reset = now.minusSeconds(30)
        val result = ResetDetector.detect(
            previous = mapOf(sessionKey to observation(reset, 95.0)),
            current = mapOf(sessionKey to observation(reset.plusSeconds(18_000), 0.0)),
            now = now,
        ) { WindowKind.SESSION }

        assertTrue(result.events.isEmpty())
    }

    @Test
    fun largeDropWithoutResetTimeIsAResetButSmallDropsAreNot() {
        val reset = ResetDetector.detect(
            previous = mapOf(sessionKey to observation(null, 40.0)),
            current = mapOf(sessionKey to observation(null, 4.0)),
            now = now,
        ) { WindowKind.SESSION }
        assertEquals(now, reset.events.single().fireTime)

        val ordinaryUsageChange = ResetDetector.detect(
            previous = mapOf(sessionKey to observation(null, 40.0)),
            current = mapOf(sessionKey to observation(null, 25.0)),
            now = now,
        ) { WindowKind.SESSION }
        assertTrue(ordinaryUsageChange.events.isEmpty())
    }

    @Test
    fun disappearedWindowsAreRemovedFromNewBaseline() {
        val result = ResetDetector.detect(
            previous = mapOf(sessionKey to observation(now.plusSeconds(100), 20.0)),
            current = emptyMap(),
            now = now,
        ) { WindowKind.SESSION }

        assertTrue(result.events.isEmpty())
        assertTrue(result.baseline.isEmpty())
    }

    @Test fun slidingUnusedResetTimeDoesNotFireRepeatedly() {
        val week = 604_800.0
        val reset = now.plusSeconds(604_800)
        listOf(1L, 60L, 300L, 604_800L).forEach { slide ->
            assertEquals(null, ResetDetector.resetBoundary(
                WindowObservation(reset, 0.0, week),
                WindowObservation(reset.plusSeconds(slide), 0.0, week), now,
            ))
        }
    }

    @Test fun realEarlyResetStillFires() {
        val reset = now.plusSeconds(3600)
        assertEquals(reset, ResetDetector.resetBoundary(
            WindowObservation(reset, 12.0, 604_800.0),
            WindowObservation(reset.plusSeconds(604_800), 0.0, 604_800.0), now,
        ))
    }

    private fun observation(reset: Instant?, used: Double) =
        WindowObservation(resetsAt = reset, usedPercent = used)
}
