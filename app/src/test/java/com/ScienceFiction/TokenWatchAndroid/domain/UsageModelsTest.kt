package com.ScienceFiction.TokenWatchAndroid.domain

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageModelsTest {
    private val now: Instant = Instant.ofEpochSecond(1_000_000)

    @Test
    fun windowKindDefaultsMatchIos() {
        assertEquals(18_000.0, WindowKind.SESSION.defaultSeconds, 0.0)
        assertEquals(604_800.0, WindowKind.WEEKLY.defaultSeconds, 0.0)
    }

    @Test
    fun elapsedFractionTracksTimeRemaining() {
        val half = window(resetsAt = now.plusSeconds(9_000), windowSeconds = 18_000.0)
        val fourFifths = window(resetsAt = now.plusSeconds(3_600), windowSeconds = 18_000.0)

        assertEquals(0.5, half.elapsedFraction(now)!!, 0.0001)
        assertEquals(0.8, fourFifths.elapsedFraction(now)!!, 0.0001)
    }

    @Test
    fun elapsedFractionClampsAndRequiresAValidDuration() {
        assertEquals(1.0, window(now.minusSeconds(100), 18_000.0).elapsedFraction(now)!!, 0.0)
        assertEquals(0.0, window(now.plusSeconds(20_000), 18_000.0).elapsedFraction(now)!!, 0.0)
        assertNull(window(now.plusSeconds(100), null).elapsedFraction(now))
        assertNull(window(now.plusSeconds(100), 0.0).elapsedFraction(now))
        assertNull(window(null, 18_000.0).elapsedFraction(now))
    }

    @Test
    fun paceDeltaReflectsOverspend() {
        val usage = window(
            resetsAt = now.plusSeconds(9_000),
            windowSeconds = 18_000.0,
            usedPercent = 80.0,
        )

        assertEquals(30.0, usage.paceDelta(now)!!, 0.0001)
    }

    @Test
    fun unusedRuleDistinguishesGaugeFromBalance() {
        val emptyGauge = window(usedPercent = 0.0)
        val usedGauge = window(usedPercent = 0.4)
        val balance = window(
            usedPercent = 0.0,
            style = UsageStyle.BALANCE,
            valueText = "6.50 USD left",
        )

        assertTrue(emptyGauge.isUnused)
        assertFalse(usedGauge.isUnused)
        assertFalse(balance.isUnused)
        assertEquals("6.50 USD left", balance.valueText)
        assertEquals(UsageStyle.GAUGE, emptyGauge.style)
        assertEquals(60.0, window(usedPercent = 40.0).remainingPercent, 0.0)
        assertEquals(0.0, window(usedPercent = 120.0).remainingPercent, 0.0)
    }

    @Test
    fun snapshotRetainsNormalizedUsage() {
        val usage = window(usedPercent = 42.0)
        val snapshot = AgentSnapshot(
            windows = listOf(usage),
            planLabel = "pro",
            fetchedAt = now,
            error = null,
        )

        assertEquals(listOf(usage), snapshot.windows)
        assertEquals("pro", snapshot.planLabel)
        assertEquals(now, snapshot.fetchedAt)
        assertNull(snapshot.error)
    }

    private fun window(
        resetsAt: Instant? = null,
        windowSeconds: Double? = null,
        usedPercent: Double = 0.0,
        style: UsageStyle = UsageStyle.GAUGE,
        valueText: String? = null,
    ) = UsageWindow(
        label = "Current session",
        usedPercent = usedPercent,
        resetsAt = resetsAt,
        kind = WindowKind.SESSION,
        windowSeconds = windowSeconds,
        style = style,
        valueText = valueText,
    )
}
