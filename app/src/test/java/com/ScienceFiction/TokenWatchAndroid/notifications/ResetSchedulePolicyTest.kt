package com.ScienceFiction.TokenWatchAndroid.notifications

import com.ScienceFiction.TokenWatchAndroid.domain.UsageStyle
import com.ScienceFiction.TokenWatchAndroid.domain.UsageWindow
import com.ScienceFiction.TokenWatchAndroid.domain.WindowKind
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResetSchedulePolicyTest {
    private val now = Instant.parse("2026-07-16T00:00:00Z")
    private val agentId = UUID.fromString("00000000-0000-0000-0000-000000000001")

    @Test
    fun targetsOnlyFutureEnabledSubscriptionGauges() {
        val windows = listOf(
            window("session", now.plusSeconds(300), WindowKind.SESSION),
            window("weekly", now.plusSeconds(600), WindowKind.WEEKLY),
            window("past", now.minusSeconds(1), WindowKind.WEEKLY),
            window("credit", now.plusSeconds(700), WindowKind.WEEKLY, UsageStyle.CREDIT_GAUGE),
            window("balance", now.plusSeconds(800), WindowKind.WEEKLY, UsageStyle.BALANCE),
        )

        val targets = ResetSchedulePolicy.targets(
            agentId,
            windows,
            now,
            sessionOn = false,
            weeklyOn = true,
        )

        assertEquals(listOf("weekly"), targets.single().labels)
        assertEquals(setOf(WindowKind.WEEKLY), targets.single().kinds)
    }

    @Test
    fun sameMinuteWindowsAreGroupedWithStableIdentifier() {
        val first = now.plusSeconds(301)
        val second = now.plusSeconds(330)
        val target = ResetSchedulePolicy.targets(
            agentId,
            listOf(
                window("a", first, WindowKind.SESSION),
                window("b", second, WindowKind.WEEKLY),
            ),
            now,
            sessionOn = true,
            weeklyOn = true,
        ).single()

        assertEquals(first, target.fireTime)
        assertEquals(listOf("a", "b"), target.labels)
        assertEquals(ResetSchedulePolicy.identifier(agentId, second), target.identifier)
    }

    @Test
    fun capsPreferEarliestTargets() {
        val windows = (1..8).map { index ->
            window("w$index", now.plusSeconds(index * 120L), WindowKind.SESSION)
        }
        val perAgent = ResetSchedulePolicy.targets(
            agentId,
            windows,
            now,
            sessionOn = true,
            weeklyOn = true,
        )
        assertEquals(ResetSchedulePolicy.PerAgentLimit, perAgent.size)
        assertEquals("w1", perAgent.first().labels.single())

        val global = ResetSchedulePolicy.clampGlobal(
            (1..40).map { index ->
                ResetScheduleTarget(
                    UUID(0, index.toLong()),
                    now.plusSeconds(index.toLong()),
                    setOf(WindowKind.WEEKLY),
                    listOf("$index"),
                )
            },
        )
        assertEquals(ResetSchedulePolicy.GlobalLimit, global.size)
        assertEquals("1", global.first().labels.single())
    }

    @Test
    fun reconcileTouchesOnlyOwnedIdentifiers() {
        val desired = setOf("reset|new", "reset|keep")
        val result = ResetSchedulePolicy.reconcile(
            desired,
            listOf("reset|keep", "reset|old", "some-other-notification"),
        )

        assertEquals(setOf("reset|new"), result.add)
        assertEquals(listOf("reset|old"), result.remove)
        assertFalse("some-other-notification" in result.remove)
        assertTrue(result.remove.all { it.startsWith(ResetSchedulePolicy.IdPrefix) })
    }

    private fun window(
        label: String,
        reset: Instant,
        kind: WindowKind,
        style: UsageStyle = UsageStyle.GAUGE,
    ) = UsageWindow(
        label = label,
        usedPercent = 10.0,
        resetsAt = reset,
        kind = kind,
        style = style,
    )
}
