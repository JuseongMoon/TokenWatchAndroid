package com.ScienceFiction.TokenWatchAndroid.domain

import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlin.random.Random

/** In-memory sample values used to explore the app without authenticating. */
object DemoData {
    const val AccountEmail = "demo@tokenwatch.app"
    private fun id(n: Int): UUID = UUID.fromString("de110000-0000-4000-8000-00000000000$n")

    fun agents(): List<Agent> = listOf(
        Agent(AgentProvider.CLAUDE, id(1), AccountEmail),
        Agent(AgentProvider.CODEX, id(2), AccountEmail),
        Agent(AgentProvider.COPILOT, id(3), "demo-dev"),
        Agent(AgentProvider.OPENROUTER, id(4), "sk-or-…demo"),
    )

    fun snapshots(now: Instant = Instant.now()): Map<UUID, AgentSnapshot> {
        fun snapshot(providerNumber: Int, plan: String?, vararg windows: UsageWindow) =
            id(providerNumber) to AgentSnapshot(windows.toList(), plan, now, null)
        val hour = 3600L
        val day = 24L * hour
        val creditTotal = 50.0
        val creditLeft = 37.1
        return mapOf(
            snapshot(1, "Max 20x",
                UsageWindow("Current session", 68.0, now.plusSeconds(1.8.times(hour).toLong()), WindowKind.SESSION, WindowKind.SESSION.defaultSeconds),
                UsageWindow("Current week (all models)", 43.0, now.plusSeconds((3.2 * day).toLong()), WindowKind.WEEKLY, WindowKind.WEEKLY.defaultSeconds),
                UsageWindow("Current week (Opus)", 81.0, now.plusSeconds((3.2 * day).toLong()), WindowKind.WEEKLY, WindowKind.WEEKLY.defaultSeconds),
            ),
            snapshot(2, "Plus",
                UsageWindow("Current session", 22.0, now.plusSeconds((4.1 * hour).toLong()), WindowKind.SESSION, WindowKind.SESSION.defaultSeconds),
                UsageWindow("Current week", 57.0, now.plusSeconds((4.6 * day).toLong()), WindowKind.WEEKLY, WindowKind.WEEKLY.defaultSeconds),
            ),
            snapshot(3, "Individual",
                UsageWindow("Premium requests", 34.0, now.plusSeconds(11 * day), WindowKind.WEEKLY, 30.0 * day),
            ),
            snapshot(4, null,
                UsageWindow(
                    "Credits", (1.0 - creditLeft / creditTotal) * 100.0, null, WindowKind.WEEKLY,
                    style = UsageStyle.CREDIT_GAUGE, valueText = creditText(creditLeft),
                    balanceRemaining = creditLeft, balanceTotal = creditTotal,
                ),
            ),
        )
    }

    fun serviceStatus(): Map<AgentProvider, ServiceHealth> = mapOf(
        AgentProvider.CLAUDE to ServiceHealth.OPERATIONAL,
        AgentProvider.CODEX to ServiceHealth.OPERATIONAL,
        AgentProvider.COPILOT to ServiceHealth.CAUTION,
    )

    fun accountInfo(agent: Agent, now: Instant = Instant.now()) = when (agent.provider.authKind) {
        AuthKind.API_KEY -> com.ScienceFiction.TokenWatchAndroid.store.AccountInfo(null, null, emptyList(), null, false, null)
        else -> com.ScienceFiction.TokenWatchAndroid.store.AccountInfo(
            AccountEmail, null, listOf("user:inference", "user:profile"),
            now.plusSeconds(21L * 24 * 3600), true, "demo-account",
        )
    }

    fun advanced(source: Map<UUID, AgentSnapshot>, now: Instant = Instant.now()): Map<UUID, AgentSnapshot> =
        source.mapValues { (_, snapshot) ->
            snapshot.copy(windows = snapshot.windows.map { tick(it, now) }, fetchedAt = now, error = null)
        }

    private fun tick(window: UsageWindow, now: Instant): UsageWindow {
        val reset = window.resetsAt
        val period = window.windowSeconds
        if (reset != null && !reset.isAfter(now) && period != null && period > 0.0) {
            var next: Instant = reset
            val seconds = period.toLong().coerceAtLeast(1L)
            while (!next.isAfter(now)) next = next.plusSeconds(seconds)
            return window.copy(usedPercent = 0.0, resetsAt = next)
        }
        return when (window.style) {
            UsageStyle.GAUGE -> window.copy(usedPercent = minOf(100.0, window.usedPercent + Random.nextDouble(0.2, 1.1)))
            UsageStyle.CREDIT_GAUGE -> {
                val total = window.balanceTotal ?: return window
                val left = maxOf(0.0, (window.balanceRemaining ?: return window) - Random.nextDouble(0.01, 0.09))
                window.copy(
                    usedPercent = (1.0 - left / total) * 100.0,
                    valueText = creditText(left),
                    balanceRemaining = left,
                )
            }
            UsageStyle.BALANCE -> window
        }
    }

    private fun creditText(amount: Double): String = String.format(Locale.US, "$%.2f left", amount)
}
