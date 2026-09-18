package com.ScienceFiction.TokenWatchAndroid.network.providers.subscription

import com.ScienceFiction.TokenWatchAndroid.auth.OAuthTokens
import com.ScienceFiction.TokenWatchAndroid.domain.UsageWindow
import com.ScienceFiction.TokenWatchAndroid.domain.WindowKind
import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkTransport
import com.ScienceFiction.TokenWatchAndroid.network.core.PlanReportingUsageClient
import com.ScienceFiction.TokenWatchAndroid.network.core.ProviderUsage
import com.ScienceFiction.TokenWatchAndroid.network.core.UsageException
import java.time.Duration
import java.time.Instant
import okhttp3.Request

/**
 * Grok (xAI) weekly usage: `cli-chat-proxy.grok.com/v1/billing?format=credits`, ported from iOS
 * `GrokUsageClient` (8cff71c), whose interpretation follows TokenBar `agent_grok.rs`.
 *
 * Since 2026-06 every paid Grok product shares one weekly pool. `creditUsagePercent` is that pool;
 * `productUsage[]` only splits it per product and is never the window value — reading the CLI row
 * alone shows an exhausted pool as "4% used" (TokenBar #240).
 *
 * Only 401 is an authentication failure. xAI's 403 is a quota or permission refusal that a refresh
 * cannot fix, and refreshing on it would burn a rotating refresh token on every poll.
 */
class GrokUsageClient(
    private val transport: NetworkTransport,
    private val endpoint: String = CREDITS_URL,
) : PlanReportingUsageClient {
    override suspend fun fetchUsage(tokens: OAuthTokens): ProviderUsage {
        val response = transport.execute(
            Request.Builder()
                .url(endpoint)
                .get()
                .header("Authorization", "Bearer ${tokens.accessToken}")
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .build(),
        )
        response.requireUsageSuccess()
        return GrokBillingMapper.usage(response.body)
    }

    companion object {
        const val CREDITS_URL = "https://cli-chat-proxy.grok.com/v1/billing?format=credits"
        private const val USER_AGENT = "TokenWatch/1.0"
    }
}

/** `?format=credits` response → one weekly window plus the optional plan label. */
object GrokBillingMapper {
    const val WEEKLY_PERIOD_TYPE = "USAGE_PERIOD_TYPE_WEEKLY"
    const val WEEKLY_LABEL = "Current week"

    /** A percent field: absent/null, a number, or present but unusable (never read as 0%). */
    private sealed interface PercentField {
        data class Number(val value: Double) : PercentField
        data object Invalid : PercentField
    }

    private sealed interface WeeklyPercent {
        data class Value(val percent: Double) : WeeklyPercent
        data object Absent : WeeklyPercent
        data object Invalid : WeeklyPercent
    }

    /** No window when the usage cannot be established; the gateway reports that as no-windows. */
    fun usage(body: ByteArray): ProviderUsage {
        val root = try {
            SubscriptionProviderJson.root(body)
        } catch (error: Exception) {
            throw UsageException.Decode(error.message ?: "Invalid Grok billing response", error)
        }
        val plan = planLabel(root["subscriptionTiers"])
        val configValue = root["config"] ?: return ProviderUsage(emptyList(), plan)
        val config = configValue.asObject() ?: throw UsageException.Decode("Invalid Grok billing config")
        val window = weeklyWindow(config) ?: return ProviderUsage(emptyList(), plan)
        return ProviderUsage(listOf(window), plan)
    }

    /** Optional label; a string or a string array. Any other shape is ignored, not an error. */
    private fun planLabel(raw: Any?): String? {
        val text = when (raw) {
            is String -> raw
            is List<*> -> raw.takeIf { list -> list.all { it is String } }?.joinToString(", ")
            else -> null
        }
        return text?.trim()?.takeIf(String::isNotEmpty)
    }

    private fun weeklyWindow(config: Map<String, Any?>): UsageWindow? {
        val used = when (val weekly = weeklyUsedPercent(config)) {
            is WeeklyPercent.Value -> weekly.percent
            WeeklyPercent.Invalid -> throw UsageException.Decode("invalid Grok weekly usage")
            // Right after a reset xAI omits a zero percent. Read that as 0% only with a
            // self-contained weekly period.
            WeeklyPercent.Absent -> {
                if (!hasSelfContainedWeeklyPeriod(config)) return null
                0.0
            }
        }
        val period = config["currentPeriod"].asObject()
        val start = ((period?.get("start") ?: config["billingPeriodStart"]) as? String)?.let(::parseIsoInstant)
        val end = ((period?.get("end") ?: config["billingPeriodEnd"]) as? String)?.let(::parseIsoInstant)
        val seconds = if (start != null && end != null && end.isAfter(start)) {
            Duration.between(start, end).toMillis() / 1_000.0
        } else {
            null
        }
        return UsageWindow(
            label = WEEKLY_LABEL,
            usedPercent = used,
            resetsAt = end,
            kind = WindowKind.WEEKLY,
            windowSeconds = seconds,
        )
    }

    /**
     * Only `creditUsagePercent` can be the window value. Product rows serve solely as evidence that
     * the week is not empty: the pool is at least as large as any product's share.
     */
    private fun weeklyUsedPercent(config: Map<String, Any?>): WeeklyPercent {
        percentField(config["creditUsagePercent"])?.let { field ->
            return validPercent(field)?.let(WeeklyPercent::Value) ?: WeeklyPercent.Invalid
        }
        val usageSeen = config["productUsage"].asArray().orEmpty().any { row ->
            val field = percentField(row.asObject()?.get("usagePercent")) ?: return@any false
            validPercent(field) != 0.0
        }
        return if (usageSeen) WeeklyPercent.Invalid else WeeklyPercent.Absent
    }

    private fun percentField(raw: Any?): PercentField? = when (raw) {
        null -> null
        is Number -> PercentField.Number(raw.toDouble())
        else -> PercentField.Invalid
    }

    private fun validPercent(field: PercentField): Double? {
        val value = (field as? PercentField.Number)?.value ?: return null
        return value.takeIf { it.isFinite() && it in 0.0..100.0 }
    }

    /**
     * A period whose type is exactly weekly with a parseable start < end in the same object. Flat
     * `billingPeriod*` values are not borrowed, so a partial or non-weekly period cannot pose as a
     * weekly window.
     */
    private fun hasSelfContainedWeeklyPeriod(config: Map<String, Any?>): Boolean {
        val period = config["currentPeriod"].asObject() ?: return false
        if (period["type"] != WEEKLY_PERIOD_TYPE) return false
        val start = (period["start"] as? String)?.let(::parseIsoInstant) ?: return false
        val end = (period["end"] as? String)?.let(::parseIsoInstant) ?: return false
        return end.isAfter(start)
    }

    /** RFC 3339 timestamp, whatever the number of fractional-second digits. */
    fun parseDate(raw: String): Instant? = parseIsoInstant(raw.trim())
}
