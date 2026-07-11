package com.ScienceFiction.TokenWatchAndroid.ui.screens

import com.ScienceFiction.TokenWatchAndroid.data.AppSettings
import com.ScienceFiction.TokenWatchAndroid.domain.Agent
import com.ScienceFiction.TokenWatchAndroid.domain.AgentProvider
import com.ScienceFiction.TokenWatchAndroid.domain.AgentSnapshot
import com.ScienceFiction.TokenWatchAndroid.domain.UsageStyle
import com.ScienceFiction.TokenWatchAndroid.domain.UsageWindow
import com.ScienceFiction.TokenWatchAndroid.domain.WindowKind
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenLogicTest {
    private val now = Instant.parse("2026-07-11T00:00:00Z")
    private val agentId = UUID.fromString("00000000-0000-0000-0000-000000000001")

    @Test
    fun `card title appends plan but never email`() {
        val planAgent = Agent(
            provider = AgentProvider.CLAUDE,
            id = agentId,
            accountLabel = "pro",
        )
        val emailAgent = planAgent.copy(accountLabel = "dev@example.com")

        assertEquals("[C] CLAUDE · pro", agentCardTitle(planAgent))
        assertEquals("[C] CLAUDE", agentCardTitle(emailAgent))
    }

    @Test
    fun `hide unused removes only zero percent gauges`() {
        val unusedGauge = window("session", 0.0)
        val usedGauge = window("week", 12.0)
        val balance = window("credits", 0.0, UsageStyle.BALANCE)

        assertEquals(
            listOf(usedGauge, balance),
            visibleUsageWindows(listOf(unusedGauge, usedGauge, balance), hideUnusedWindows = true),
        )
    }

    @Test
    fun `tracked usage averages selected gauge windows`() {
        val agent = Agent(AgentProvider.CODEX, agentId)
        val snapshot = snapshot(window("session", 40.0), window("week", 60.0))
        val targets = setOf("$agentId|session", "$agentId|week", "$agentId|missing")

        assertEquals(
            50.0,
            trackedUsedPercent(listOf(agent), mapOf(agentId to snapshot), targets)!!,
            0.0001,
        )
        assertNull(trackedUsedPercent(listOf(agent), mapOf(agentId to snapshot), emptySet()))
    }

    @Test
    fun `trackable graph options exclude balances and keep stable ids`() {
        val agent = Agent(AgentProvider.OPENROUTER, agentId)
        val graph = window("limit", 20.0)
        val balance = window("credits", 0.0, UsageStyle.BALANCE)

        val options = trackableGraphOptions(
            agents = listOf(agent),
            snapshots = mapOf(agentId to snapshot(graph, balance)),
        )

        assertEquals(1, options.size)
        assertEquals("$agentId|limit", options.single().id)
    }

    @Test
    fun `usage heartbeat selects first graph only when current targets are invalid`() {
        val agent = Agent(AgentProvider.CLAUDE, agentId)
        val option = GraphOption(agent, window("session", 25.0))
        val invalid = AppSettings(heartbeatTargets = setOf("missing"))
        val valid = AppSettings(heartbeatTargets = setOf(option.id, "old"))

        val selectedDefault = selectUsageTracking(invalid, listOf(option))
        val preserved = selectUsageTracking(valid, listOf(option))

        assertTrue(selectedDefault.heartbeatTracking)
        assertEquals(setOf(option.id), selectedDefault.heartbeatTargets)
        assertEquals(valid.heartbeatTargets, preserved.heartbeatTargets)
    }

    @Test
    fun `target toggle adds and removes id`() {
        assertEquals(setOf("a", "b"), toggleTarget(setOf("a"), "b"))
        assertEquals(emptySet<String>(), toggleTarget(setOf("a"), "a"))
    }

    @Test
    fun `pace compares usage with elapsed window time`() {
        val ahead = timedWindow(usedPercent = 60.0)
        val under = timedWindow(usedPercent = 40.0)
        val even = timedWindow(usedPercent = 51.0)

        assertEquals(UsagePace(UsagePaceKind.AHEAD, 10), usagePace(ahead, now))
        assertEquals(UsagePace(UsagePaceKind.UNDER, 10), usagePace(under, now))
        assertEquals(UsagePace(UsagePaceKind.EVEN, 1), usagePace(even, now))
    }

    @Test
    fun `depletion eta appears only when quota runs out before reset`() {
        val fast = timedWindow(usedPercent = 60.0)
        val sustainable = timedWindow(usedPercent = 40.0)

        val eta = depletionEta(fast, now)

        assertEquals(0, eta?.days)
        assertEquals(0, eta?.hours)
        assertEquals(55, eta?.minutes)
        assertNull(depletionEta(sustainable, now))
    }

    @Test
    fun `toggle result does not mutate original target set`() {
        val original = linkedSetOf("a")
        val changed = toggleTarget(original, "b")

        assertEquals(setOf("a"), original)
        assertTrue("b" in changed)
        assertFalse(original === changed)
    }

    private fun timedWindow(usedPercent: Double) = UsageWindow(
        label = "session",
        usedPercent = usedPercent,
        resetsAt = now.plusSeconds(5_000),
        kind = WindowKind.SESSION,
        windowSeconds = 10_000.0,
    )

    private fun window(
        label: String,
        usedPercent: Double,
        style: UsageStyle = UsageStyle.GAUGE,
    ) = UsageWindow(
        label = label,
        usedPercent = usedPercent,
        resetsAt = null,
        kind = WindowKind.WEEKLY,
        style = style,
        valueText = if (style == UsageStyle.BALANCE) "12.50 USD left" else null,
    )

    private fun snapshot(vararg windows: UsageWindow) = AgentSnapshot(
        windows = windows.toList(),
        planLabel = null,
        fetchedAt = now,
        error = null,
    )
}
