package com.ScienceFiction.TokenWatchAndroid.notifications

import com.ScienceFiction.TokenWatchAndroid.domain.WindowKind
import java.time.Duration
import java.time.Instant

data class WindowObservation(
    val resetsAt: Instant?,
    val usedPercent: Double,
)

data class ResetEvent(
    val fireTime: Instant,
    val kinds: Set<WindowKind>,
    val labels: List<String>,
)

/** Pure surprise-reset detector. Scheduled, on-time resets are intentionally suppressed. */
object ResetDetector {
    const val UsedDropThreshold = 20.0
    const val UsedLandingCeiling = 5.0
    val ClockSkew: Duration = Duration.ofSeconds(120)

    data class Result(
        val events: List<ResetEvent>,
        val baseline: Map<String, WindowObservation>,
    )

    fun detect(
        previous: Map<String, WindowObservation>,
        current: Map<String, WindowObservation>,
        now: Instant,
        kindOf: (String) -> WindowKind,
    ): Result {
        val kinds = linkedSetOf<WindowKind>()
        val labels = mutableListOf<String>()
        var earliest: Instant? = null

        for ((key, currentObservation) in current) {
            val previousObservation = previous[key] ?: continue
            val boundary = resetBoundary(previousObservation, currentObservation, now) ?: continue
            val expected = previousObservation.resetsAt
            if (expected != null && !now.isBefore(expected.minus(ClockSkew))) continue

            kinds += kindOf(key)
            labels += windowLabel(key)
            if (earliest == null || boundary.isBefore(earliest)) earliest = boundary
        }

        val events = if (labels.isEmpty()) {
            emptyList()
        } else {
            listOf(
                ResetEvent(
                    fireTime = earliest ?: now,
                    kinds = kinds,
                    labels = labels.sorted(),
                ),
            )
        }
        return Result(events = events, baseline = current)
    }

    fun resetBoundary(
        previous: WindowObservation,
        current: WindowObservation,
        now: Instant,
    ): Instant? {
        val previousReset = previous.resetsAt
        val currentReset = current.resetsAt
        if (previousReset != null && currentReset != null && currentReset.isAfter(previousReset)) {
            return previousReset
        }
        if (
            previous.usedPercent - current.usedPercent >= UsedDropThreshold &&
            current.usedPercent <= UsedLandingCeiling
        ) {
            return previousReset ?: now
        }
        return null
    }

    fun windowLabel(key: String): String = key.substringAfter('|', key)
}
