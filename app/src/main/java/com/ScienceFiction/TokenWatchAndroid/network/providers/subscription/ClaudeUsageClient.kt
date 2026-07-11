package com.ScienceFiction.TokenWatchAndroid.network.providers.subscription

import com.ScienceFiction.TokenWatchAndroid.auth.OAuthTokens
import com.ScienceFiction.TokenWatchAndroid.domain.UsageWindow
import com.ScienceFiction.TokenWatchAndroid.domain.WindowKind
import com.ScienceFiction.TokenWatchAndroid.network.core.HttpTransport
import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkTransport
import com.ScienceFiction.TokenWatchAndroid.network.core.ProviderUsageClient
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/** Claude OAuth usage client matching iOS parity commit e7d1715. */
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
            if (key == "limits") continue
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
