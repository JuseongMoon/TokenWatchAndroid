package com.ScienceFiction.TokenWatchAndroid.domain

import java.time.Duration
import java.time.Instant
import kotlin.math.max

enum class WindowKind(val defaultSeconds: Double) {
    SESSION(5.0 * 60.0 * 60.0),
    WEEKLY(7.0 * 24.0 * 60.0 * 60.0),
}

/** The clean iOS baseline supports only usage gauges and absolute balance text. */
enum class UsageStyle {
    GAUGE,
    BALANCE,
}

data class UsageWindow(
    val label: String,
    val usedPercent: Double,
    val resetsAt: Instant?,
    val kind: WindowKind,
    val windowSeconds: Double? = null,
    val style: UsageStyle = UsageStyle.GAUGE,
    val valueText: String? = null,
) : Identifiable<String> {
    override val id: String
        get() = label

    val remainingPercent: Double
        get() = max(0.0, 100.0 - usedPercent)

    /** A zero-percent balance is still meaningful and must remain visible. */
    val isUnused: Boolean
        get() = style == UsageStyle.GAUGE && usedPercent <= 0.0

    /** Fraction of the window elapsed at [at], clamped to 0..1. */
    fun elapsedFraction(at: Instant = Instant.now()): Double? {
        val reset = resetsAt ?: return null
        val seconds = windowSeconds?.takeIf { it > 0.0 } ?: return null
        val remaining = Duration.between(at, reset)
        val remainingSeconds = remaining.seconds.toDouble() + remaining.nano / 1_000_000_000.0
        return ((seconds - remainingSeconds) / seconds).coerceIn(0.0, 1.0)
    }

    /** Percentage-point difference between usage and elapsed time. */
    fun paceDelta(at: Instant = Instant.now()): Double? =
        elapsedFraction(at)?.let { elapsed -> usedPercent - elapsed * 100.0 }
}

data class AgentSnapshot(
    val windows: List<UsageWindow>,
    val planLabel: String?,
    val fetchedAt: Instant,
    val error: String?,
)
