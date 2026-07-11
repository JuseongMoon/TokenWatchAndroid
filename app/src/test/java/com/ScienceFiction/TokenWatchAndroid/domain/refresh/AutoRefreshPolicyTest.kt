package com.ScienceFiction.TokenWatchAndroid.domain.refresh

import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoRefreshPolicyTest {
    @Test
    fun ladderIsSortedWithExpectedBounds() {
        assertEquals(-1, AutoRefreshPolicy.sentinel)
        assertEquals(30, AutoRefreshPolicy.ladder.first())
        assertEquals(600, AutoRefreshPolicy.ladder.last())
        assertEquals(AutoRefreshPolicy.ladder.sorted(), AutoRefreshPolicy.ladder)
        assertEquals(60, AutoRefreshPolicy.ladder[AutoRefreshPolicy.baseIndex])
        assertEquals(Duration.ofSeconds(1), AutoRefreshPolicy.resetSlack)
    }

    @Test
    fun increaseShrinksOneStep() {
        assertEquals(0, AutoRefreshPolicy.nextLadderIndex(from = 1, maxDelta = 2.0))
        assertEquals(2, AutoRefreshPolicy.nextLadderIndex(from = 3, maxDelta = 3.9))
    }

    @Test
    fun surgeShrinksTwoSteps() {
        assertEquals(1, AutoRefreshPolicy.nextLadderIndex(from = 3, maxDelta = 4.0))
        assertEquals(2, AutoRefreshPolicy.nextLadderIndex(from = 4, maxDelta = 25.0))
    }

    @Test
    fun idleGrowsOneStep() {
        assertEquals(2, AutoRefreshPolicy.nextLadderIndex(from = 1, maxDelta = 0.0))
        assertEquals(2, AutoRefreshPolicy.nextLadderIndex(from = 1, maxDelta = 1.0))
    }

    @Test
    fun hysteresisBandHolds() {
        assertEquals(2, AutoRefreshPolicy.nextLadderIndex(from = 2, maxDelta = 1.01))
        assertEquals(2, AutoRefreshPolicy.nextLadderIndex(from = 2, maxDelta = 1.99))
    }

    @Test
    fun noSignalHolds() {
        assertEquals(0, AutoRefreshPolicy.nextLadderIndex(from = 0, maxDelta = null))
        assertEquals(4, AutoRefreshPolicy.nextLadderIndex(from = 4, maxDelta = null))
    }

    @Test
    fun clampsAtFastestAndSlowest() {
        assertEquals(0, AutoRefreshPolicy.nextLadderIndex(from = 0, maxDelta = 9.9))
        assertEquals(0, AutoRefreshPolicy.nextLadderIndex(from = 1, maxDelta = 5.0))
        val last = AutoRefreshPolicy.ladder.lastIndex
        assertEquals(last, AutoRefreshPolicy.nextLadderIndex(from = last, maxDelta = 0.0))
    }

    @Test
    fun picksLargestIncreaseAcrossWindows() {
        val old = mapOf("a|session" to 10.0, "a|week" to 50.0)
        val new = mapOf("a|session" to 13.5, "a|week" to 50.2)
        assertEquals(3.5, AutoRefreshPolicy.maxUsageDelta(from = old, to = new)!!, 0.0)
    }

    @Test
    fun resetDropCountsAsZero() {
        val delta = AutoRefreshPolicy.maxUsageDelta(
            from = mapOf("a|s" to 90.0),
            to = mapOf("a|s" to 2.0),
        )
        assertEquals(0.0, delta!!, 0.0)
    }

    @Test
    fun mixedResetAndIncreaseTakesIncrease() {
        val old = mapOf("a|session" to 95.0, "a|week" to 40.0)
        val new = mapOf("a|session" to 1.0, "a|week" to 41.6)
        val delta = AutoRefreshPolicy.maxUsageDelta(from = old, to = new)
        assertTrue(delta != null && abs(delta - 1.6) < 0.0001)
    }

    @Test
    fun disjointKeysGiveNoSignal() {
        assertNull(
            AutoRefreshPolicy.maxUsageDelta(
                from = mapOf("a|s" to 10.0),
                to = mapOf("b|s" to 20.0),
            ),
        )
        assertNull(AutoRefreshPolicy.maxUsageDelta(from = emptyMap(), to = emptyMap()))
    }

    @Test
    fun earliestFutureResetWins() {
        val now = Instant.ofEpochSecond(1_000_000)
        val resetDates = listOf(
            now.plusSeconds(3_600),
            now.plusSeconds(120),
            now.plusSeconds(900),
        )
        assertEquals(now.plusSeconds(120), AutoRefreshPolicy.nextResetDate(resetDates, after = now))
    }

    @Test
    fun pastAndNullResetsAreIgnored() {
        val now = Instant.ofEpochSecond(1_000_000)
        val resetDates = listOf(now.minusSeconds(5), null)
        assertNull(AutoRefreshPolicy.nextResetDate(resetDates, after = now))
    }

    @Test
    fun resetExactlyAtNowIsNotFuture() {
        val now = Instant.ofEpochSecond(1_000_000)
        assertNull(AutoRefreshPolicy.nextResetDate(listOf(now), after = now))
    }
}
