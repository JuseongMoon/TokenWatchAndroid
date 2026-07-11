package com.ScienceFiction.TokenWatchAndroid.network.providers.subscription

import com.ScienceFiction.TokenWatchAndroid.auth.OAuthTokens
import com.ScienceFiction.TokenWatchAndroid.domain.UsageWindow
import com.ScienceFiction.TokenWatchAndroid.domain.WindowKind
import com.ScienceFiction.TokenWatchAndroid.network.core.HttpTransport
import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkTransport
import com.ScienceFiction.TokenWatchAndroid.network.core.ProviderUsageClient
import java.time.Instant
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/** ChatGPT/Codex wham usage client matching clean iOS commit 6df2689. */
class CodexUsageClient(
    private val transport: NetworkTransport = HttpTransport(),
    private val endpoint: HttpUrl = DefaultEndpoint.toHttpUrl(),
    private val additionalLimitLabel: () -> String = { "Additional limit" },
) : ProviderUsageClient {
    override suspend fun fetch(tokens: OAuthTokens): List<UsageWindow> {
        val requestBuilder = Request.Builder()
            .url(endpoint)
            .get()
            .header("Authorization", "Bearer ${tokens.accessToken}")
            .header("Accept", "application/json")
            .header("User-Agent", UserAgent)
        tokens.accountId?.takeIf(String::isNotEmpty)?.let { accountId ->
            requestBuilder.header("ChatGPT-Account-Id", accountId)
        }
        val response = transport.executeUsage(
            requestBuilder.build(),
            forbiddenIsUnauthorized = true,
        )
        return decodeSubscriptionUsage(response.body, ::mapWindows)
    }

    private fun mapWindows(root: Map<String, Any?>): List<UsageWindow> {
        val output = mutableListOf<UsageWindow>()
        val seen = mutableSetOf<String>()
        val rateLimit = parseRateLimit(root.optionalObject("rate_limit"))
        if (rateLimit != null) {
            var primary = rateLimit.primary
            var secondary = rateLimit.secondary
            if (role(primary) == WindowRole.WEEKLY && role(secondary) != WindowRole.WEEKLY) {
                val temporary = primary
                primary = secondary
                secondary = temporary
            }
            primary?.let { window ->
                mapWindow("Current session", window).also {
                    output += it
                    seen += it.label
                }
            }
            secondary?.let { window ->
                mapWindow("Current week", window).also {
                    output += it
                    seen += it.label
                }
            }
        }

        val additional = root.optionalArray("additional_rate_limits")
        for (rawExtra in additional.orEmpty()) {
            val extra = rawExtra.asObject()
                ?: throw IllegalArgumentException("Invalid additional Codex rate limit")
            val nested = parseRateLimit(extra.optionalObject("rate_limit")) ?: continue
            val window = nested.primary ?: nested.secondary ?: continue
            val label = extra.optionalString("limit_name")
                ?: extra.optionalString("metered_feature")
                ?: additionalLimitLabel()
            if (label in seen) continue
            output += mapWindow(label, window)
            seen += label
        }
        return output
    }

    private fun parseRateLimit(value: Map<String, Any?>?): RateLimit? {
        if (value == null) return null
        return RateLimit(
            primary = parseWindow(value.optionalObject("primary_window")),
            secondary = parseWindow(value.optionalObject("secondary_window")),
        )
    }

    private fun parseWindow(value: Map<String, Any?>?): CodexWindow? {
        if (value == null) return null
        val used = value["used_percent"].asNumber()
            ?: throw IllegalArgumentException("Codex window is missing used_percent")
        return CodexWindow(
            usedPercent = used,
            resetAt = value.optionalLong("reset_at"),
            windowSeconds = value.optionalLong("limit_window_seconds"),
        )
    }

    private fun mapWindow(label: String, window: CodexWindow): UsageWindow {
        val kind = if (role(window) == WindowRole.SESSION) {
            WindowKind.SESSION
        } else {
            WindowKind.WEEKLY
        }
        return UsageWindow(
            label = label,
            usedPercent = window.usedPercent.coerceIn(0.0, 100.0),
            resetsAt = window.resetAt?.takeIf { it > 0L }?.let(Instant::ofEpochSecond),
            kind = kind,
            windowSeconds = window.windowSeconds?.toDouble() ?: kind.defaultSeconds,
        )
    }

    private fun role(window: CodexWindow?): WindowRole = when (window?.windowSeconds) {
        18_000L -> WindowRole.SESSION
        604_800L -> WindowRole.WEEKLY
        else -> WindowRole.OTHER
    }

    private fun Map<String, Any?>.optionalObject(key: String): Map<String, Any?>? {
        if (!containsKey(key) || this[key] == null) return null
        return this[key].asObject()
            ?: throw IllegalArgumentException("$key must be an object")
    }

    private fun Map<String, Any?>.optionalArray(key: String): List<Any?>? {
        if (!containsKey(key) || this[key] == null) return null
        return this[key].asArray()
            ?: throw IllegalArgumentException("$key must be an array")
    }

    private fun Map<String, Any?>.optionalString(key: String): String? {
        if (!containsKey(key) || this[key] == null) return null
        return this[key] as? String
            ?: throw IllegalArgumentException("$key must be a string")
    }

    private fun Map<String, Any?>.optionalLong(key: String): Long? {
        if (!containsKey(key) || this[key] == null) return null
        return this[key].asLong()
            ?: throw IllegalArgumentException("$key must be an integer")
    }

    private data class RateLimit(
        val primary: CodexWindow?,
        val secondary: CodexWindow?,
    )

    private data class CodexWindow(
        val usedPercent: Double,
        val resetAt: Long?,
        val windowSeconds: Long?,
    )

    private enum class WindowRole { SESSION, WEEKLY, OTHER }

    companion object {
        const val DefaultEndpoint = "https://chatgpt.com/backend-api/wham/usage"
        const val UserAgent = "TokenWatch/1.0"
    }
}
