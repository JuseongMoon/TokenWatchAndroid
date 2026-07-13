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
        val fullCreditGauge = window(
            usedPercent = 0.0,
            style = UsageStyle.CREDIT_GAUGE,
            valueText = "500 credits",
        )

        assertTrue(emptyGauge.isUnused)
        assertFalse(usedGauge.isUnused)
        assertFalse(balance.isUnused)
        assertFalse(fullCreditGauge.isUnused)
        assertTrue(emptyGauge.isGaugeLike)
        assertTrue(fullCreditGauge.isGaugeLike)
        assertFalse(balance.isGaugeLike)
        assertEquals("6.50 USD left", balance.valueText)
        assertEquals(UsageStyle.GAUGE, emptyGauge.style)
        assertEquals(60.0, window(usedPercent = 40.0).remainingPercent, 0.0)
        assertEquals(0.0, window(usedPercent = 120.0).remainingPercent, 0.0)
    }

    @Test
    fun creditGaugePolicyConvertsRemainingBalanceAndClamps() {
        assertEquals(0.0, CreditGaugePolicy.usedPercent(500.0, 500.0)!!, 0.0)
        assertEquals(50.0, CreditGaugePolicy.usedPercent(250.0, 500.0)!!, 0.0)
        assertEquals(100.0, CreditGaugePolicy.usedPercent(0.0, 500.0)!!, 0.0)
        assertEquals(100.0, CreditGaugePolicy.usedPercent(-10.0, 500.0)!!, 0.0)
        assertEquals(0.0, CreditGaugePolicy.usedPercent(600.0, 500.0)!!, 0.0)
        assertNull(CreditGaugePolicy.usedPercent(100.0, 0.0))
        assertNull(CreditGaugePolicy.usedPercent(100.0, -5.0))
    }

    @Test
    fun creditGaugePeakIsMonotonicAndGrowsOnTopUp() {
        assertEquals(42.0, CreditGaugePolicy.newPeak(null, 42.0), 0.0)
        assertEquals(500.0, CreditGaugePolicy.newPeak(500.0, 300.0), 0.0)
        assertEquals(900.0, CreditGaugePolicy.newPeak(500.0, 900.0), 0.0)
    }

    @Test
    fun promotionPreservesRawBalanceAndMarksEstimatedScale() {
        val balance = UsageWindow(
            label = "Credits",
            usedPercent = 0.0,
            resetsAt = now,
            kind = WindowKind.WEEKLY,
            style = UsageStyle.BALANCE,
            valueText = "487.50 credits left",
            balanceRemaining = 487.5,
            balanceTotal = 500.0,
        )

        val promoted = balance.promotedToCreditGauge(
            usedPercent = 2.5,
            estimatedTotal = true,
        )

        assertEquals(UsageStyle.CREDIT_GAUGE, promoted.style)
        assertEquals(2.5, promoted.usedPercent, 0.0)
        assertTrue(promoted.estimatedTotal)
        assertEquals("487.50 credits left", promoted.valueText)
        assertEquals(487.5, promoted.balanceRemaining!!, 0.0)
        assertEquals(500.0, promoted.balanceTotal!!, 0.0)
        assertEquals(now, promoted.resetsAt)
        assertEquals(97.5, promoted.remainingPercent, 0.0)
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
