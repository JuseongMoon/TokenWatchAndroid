package com.ScienceFiction.TokenWatchAndroid.domain

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoDataTest {
    private val now = Instant.parse("2027-01-15T00:00:00Z")

    @Test fun everyDemoAgentHasAHealthySnapshot() {
        val snapshots = DemoData.snapshots(now)
        DemoData.agents().forEach { agent ->
            assertTrue(snapshots.getValue(agent.id).windows.isNotEmpty())
            assertEquals(null, snapshots.getValue(agent.id).error)
        }
        assertTrue(DemoData.agents().any { it.provider.usageCategory == UsageCategory.API_CREDIT })
    }

    @Test fun tickAdvancesAndRollsWindowsForward() {
        val before = DemoData.snapshots(now)
        val later = now.plusSeconds(6 * 3600L)
        val after = DemoData.advanced(before, later)
        assertTrue(after.values.flatMap { it.windows }.filter { it.resetsAt != null }.all { it.resetsAt!!.isAfter(later) })
        assertTrue(after.values.flatMap { it.windows }.any { it.usedPercent == 0.0 })
    }

    @Test fun exhaustedAndWarningSamplesRemainVisuallyDistinct() {
        val before = DemoData.snapshots(now)
        val windows = before.values.flatMap { it.windows }
        assertTrue(windows.any { it.usedPercent == 100.0 })
        assertTrue(windows.any { it.usedPercent == 94.0 })

        val after = DemoData.advanced(before, now.plusSeconds(1))
        val advancedWindows = after.values.flatMap { it.windows }
        assertTrue(advancedWindows.any { it.usedPercent == 100.0 })
        assertTrue(advancedWindows.filter { it.style == UsageStyle.GAUGE }.all { it.usedPercent <= 100.0 })
    }
}
