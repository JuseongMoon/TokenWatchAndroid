package com.ScienceFiction.TokenWatchAndroid.network.providers.subscription

import com.ScienceFiction.TokenWatchAndroid.auth.OAuthTokens
import com.ScienceFiction.TokenWatchAndroid.domain.UsageStyle
import com.ScienceFiction.TokenWatchAndroid.domain.UsageWindow
import com.ScienceFiction.TokenWatchAndroid.domain.WindowKind
import com.ScienceFiction.TokenWatchAndroid.network.core.HttpTransport
import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkTransport
import com.ScienceFiction.TokenWatchAndroid.network.core.ProviderUsageClient
import java.util.Locale
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/** Claude OAuth usage client matching iOS parity commit 5515740. */
class ClaudeUsageClient(
    private val transport: NetworkTransport = HttpTransport(),
    private val endpoint: HttpUrl = DefaultEndpoint.toHttpUrl(),
) : ProviderUsageClient {
    override suspend fun fetch(tokens: OAuthTokens): List<UsageWindow> {
        val request = Request.Builder()
            .url(endpoint)
            .get()
            .header("Authorization", "Bearer ${tokens.accessToken}")
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("anthropic-beta", BetaHeader)
            .header("User-Agent", UserAgent)
            .build()
        val response = transport.executeUsage(request, forbiddenIsUnauthorized = false)
        return decodeSubscriptionUsage(response.body, ::mapWindows)
    }

    private fun mapWindows(root: Map<String, Any?>): List<UsageWindow> {
        val windows = baseWindows(root).toMutableList()
        extraUsageWindow(root["extra_usage"])?.let(windows::add)
        return windows
    }

    private fun baseWindows(root: Map<String, Any?>): List<UsageWindow> {
        val rawLimits = root["limits"].asArray()
        val limits = rawLimits?.map { it.asObject() }
        if (!limits.isNullOrEmpty() && limits.all { it != null }) {
            return limits.map { limit -> mapLimit(limit!!) }
        }
        return mapLegacyWindows(root)
    }

    private fun mapLimit(limit: Map<String, Any?>): UsageWindow {
        val rawKind = limit["kind"] as? String ?: ""
        val kind = if ((limit["group"] as? String) == "session") {
            WindowKind.SESSION
        } else {
            WindowKind.WEEKLY
        }
        val label = when (rawKind) {
            "session" -> "Current session"
            "weekly_all" -> "Current week (all models)"
            "weekly_scoped" -> {
                val scope = limit["scope"].asObject()
                val model = scope?.get("model").asObject()
                val displayName = model?.get("display_name") as? String ?: "scoped"
                "Current week ($displayName)"
            }
            else -> rawKind
        }
        return UsageWindow(
            label = label,
            usedPercent = (limit["percent"].asNumber() ?: 0.0).coerceIn(0.0, 100.0),
            resetsAt = parseIsoInstant(limit["resets_at"] as? String),
            kind = kind,
            windowSeconds = kind.defaultSeconds,
        )
    }

    private fun mapLegacyWindows(root: Map<String, Any?>): List<UsageWindow> {
        val legacy = linkedMapOf<String, LegacyWindow>()
        for ((key, rawValue) in root) {
            if (key == "limits" || key == "extra_usage") continue
            val value = rawValue.asObject() ?: continue
            val utilization = value["utilization"].asNumberOrString()
            val resetsAt = parseIsoInstant(value["resets_at"] as? String)
            if (utilization != null || resetsAt != null) {
                legacy[key] = LegacyWindow(utilization = utilization, resetsAt = resetsAt)
            }
        }

        val output = mutableListOf<UsageWindow>()
        val consumed = mutableSetOf<String>()
        fun take(key: String, label: String, kind: WindowKind) {
            val window = legacy[key] ?: return
            output += UsageWindow(
                label = label,
                usedPercent = (window.utilization ?: 0.0).coerceIn(0.0, 100.0),
                resetsAt = window.resetsAt,
                kind = kind,
                windowSeconds = kind.defaultSeconds,
            )
            consumed += key
        }

        take("five_hour", "Current session", WindowKind.SESSION)
        take("seven_day", "Current week (all models)", WindowKind.WEEKLY)
        legacy.keys.firstOrNull { it.contains("fable", ignoreCase = true) }?.let { key ->
            take(key, "Current week (Fable)", WindowKind.WEEKLY)
        }
        listOf(
            "seven_day_opus" to "Current week (Opus)",
            "seven_day_sonnet" to "Current week (Sonnet)",
        ).forEach { (key, label) ->
            if (key !in consumed) take(key, label, WindowKind.WEEKLY)
        }
        return output
    }

    /** Maps Claude's optional extra-usage allowance from minor currency units into a balance. */
    private fun extraUsageWindow(raw: Any?): UsageWindow? {
        val extra = raw.asObject() ?: return null
        val enabled = extra["is_enabled"] as? Boolean ?: false
        val limitMinor = extra["monthly_limit"].asNumberOrString()
        if (!enabled || limitMinor == null || limitMinor <= 0.0) return null

        val usedMinor = extra["used_credits"].asNumberOrString()
            ?: extra["utilization"].asNumberOrString()?.let { limitMinor * it / 100.0 }
            ?: 0.0
        val total = limitMinor / 100.0
        val remaining = ((limitMinor - usedMinor) / 100.0).coerceAtLeast(0.0)
        val currency = (extra["currency"] as? String)
            ?.trim()
            ?.uppercase(Locale.US)
            .orEmpty()
        val amount = if (currency.isEmpty() || currency == "USD") {
            String.format(Locale.US, "\$%.2f", remaining)
        } else {
            String.format(Locale.US, "%.2f %s", remaining, currency)
        }
        return UsageWindow(
            label = "Extra usage",
            usedPercent = 0.0,
            resetsAt = null,
            kind = WindowKind.WEEKLY,
            style = UsageStyle.BALANCE,
            valueText = "$amount left",
            balanceRemaining = remaining,
            balanceTotal = total,
        )
    }

    private data class LegacyWindow(
        val utilization: Double?,
        val resetsAt: java.time.Instant?,
    )

    companion object {
        const val DefaultEndpoint = "https://api.anthropic.com/api/oauth/usage"
        const val BetaHeader = "oauth-2025-04-20"
        const val UserAgent = "claude-code/2.1.0"
    }
}
