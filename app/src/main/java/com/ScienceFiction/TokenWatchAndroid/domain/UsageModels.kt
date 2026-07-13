package com.ScienceFiction.TokenWatchAndroid.domain

import java.time.Duration
import java.time.Instant
import kotlin.math.max

enum class WindowKind(val defaultSeconds: Double) {
    SESSION(5.0 * 60.0 * 60.0),
    WEEKLY(7.0 * 24.0 * 60.0 * 60.0),
}

/** How a normalized usage window is rendered. */
enum class UsageStyle {
    GAUGE,
    CREDIT_GAUGE,
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
    /** Raw prepaid balance supplied by a provider client. */
    val balanceRemaining: Double? = null,
    /** Provider-supplied total balance; null when the store must estimate from an observed peak. */
    val balanceTotal: Double? = null,
    /** Whether a credit gauge's denominator came from the highest observed balance. */
    val estimatedTotal: Boolean = false,
) : Identifiable<String> {
    override val id: String
        get() = label

    val remainingPercent: Double
        get() = max(0.0, 100.0 - usedPercent)

    /** A zero-percent balance is still meaningful and must remain visible. */
    val isUnused: Boolean
        get() = style == UsageStyle.GAUGE && usedPercent <= 0.0

    /** Subscription and prepaid-credit gauges both participate in usage tracking. */
    val isGaugeLike: Boolean
        get() = style == UsageStyle.GAUGE || style == UsageStyle.CREDIT_GAUGE

    /** Preserves the provider's raw balance fields while converting a balance to a credit gauge. */
    fun promotedToCreditGauge(
        usedPercent: Double,
        estimatedTotal: Boolean,
    ): UsageWindow = copy(
        usedPercent = usedPercent,
        style = UsageStyle.CREDIT_GAUGE,
        estimatedTotal = estimatedTotal,
    )

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

/** Pure prepaid-credit scale policy shared by the store and JVM tests. */
object CreditGaugePolicy {
    /** Converts a remaining balance into consumed percent, clamped to 0..100. */
    fun usedPercent(remaining: Double, total: Double): Double? {
        if (total <= 0.0) return null
        return ((1.0 - remaining / total) * 100.0).coerceIn(0.0, 100.0)
    }

    /** Keeps the highest balance observed across refreshes and top-ups. */
    fun newPeak(stored: Double?, observed: Double): Double = max(stored ?: 0.0, observed)
}

data class AgentSnapshot(
    val windows: List<UsageWindow>,
    val planLabel: String?,
    val fetchedAt: Instant,
    val error: String?,
)
