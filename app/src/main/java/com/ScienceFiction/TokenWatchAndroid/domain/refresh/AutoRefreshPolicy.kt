package com.ScienceFiction.TokenWatchAndroid.domain.refresh

import java.time.Duration
import java.time.Instant
import kotlin.math.max

/**
 * Pure policy for the adaptive usage refresh interval.
 *
 * This mirrors TokenWatch iOS commit 6df2689. Scheduling, persistence, and
 * network work deliberately live outside this object.
 */
object AutoRefreshPolicy {
    /** Stored refresh-interval value that selects adaptive mode. */
    const val sentinel = -1

    /** Allowed adaptive intervals, in seconds, from fastest to slowest. */
    val ladder = listOf(30, 60, 120, 300, 600)

    /** Adaptive mode starts from the 60-second interval. */
    const val baseIndex = 1

    /** Grace period after a server-provided reset time. */
    val resetSlack: Duration = Duration.ofSeconds(1)

    /**
     * Selects the next interval index from the largest observed usage change.
     *
     * A surge of at least 4 percentage points shortens by two steps, a change
     * of at least 2 points shortens by one, and an idle change of at most 1
     * point lengthens by one. The open interval (1, 2) is the hysteresis band.
     * A null signal preserves the supplied index exactly, matching iOS.
     */
    fun nextLadderIndex(from: Int, maxDelta: Double?): Int {
        if (maxDelta == null) return from

        val next = when {
            maxDelta >= 4.0 -> from - 2
            maxDelta >= 2.0 -> from - 1
            maxDelta <= 1.0 -> from + 1
            else -> from
        }
        return next.coerceIn(0, ladder.lastIndex)
    }

    /**
     * Returns the largest non-negative increase among window IDs shared by
     * [from] and [to]. A reset/decrease contributes zero. If the maps have no
     * window IDs in common, there is no signal and this returns null.
     */
    fun maxUsageDelta(from: Map<String, Double>, to: Map<String, Double>): Double? {
        var best: Double? = null
        for ((key, value) in to) {
            val previous = from[key] ?: continue
            best = max(best ?: 0.0, value - previous)
        }
        return best?.coerceAtLeast(0.0)
    }

    /**
     * Picks the earliest reset strictly after [after]. Null, past, and exactly
     * equal timestamps are ignored so a fired reset cannot immediately loop.
     * Callers pass reset timestamps flattened from all snapshots and windows.
     */
    fun nextResetDate(resetDates: Iterable<Instant?>, after: Instant): Instant? =
        resetDates
            .asSequence()
            .filterNotNull()
            .filter { it.isAfter(after) }
            .minOrNull()
}
