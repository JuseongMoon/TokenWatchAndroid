package com.ScienceFiction.TokenWatchAndroid.network.providers.subscription

import com.ScienceFiction.TokenWatchAndroid.auth.OAuthTokens
import com.ScienceFiction.TokenWatchAndroid.auth.device.CursorAuth
import com.ScienceFiction.TokenWatchAndroid.domain.UsageWindow
import com.ScienceFiction.TokenWatchAndroid.domain.WindowKind
import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkTransport
import com.ScienceFiction.TokenWatchAndroid.network.core.PlanReportingUsageClient
import com.ScienceFiction.TokenWatchAndroid.network.core.ProviderUsage
import com.ScienceFiction.TokenWatchAndroid.network.core.UsageException
import java.time.Duration
import okhttp3.Request

/**
 * Cursor subscription usage from the dashboard's `GET /api/usage-summary`, ported from iOS
 * `CursorUsageClient` (d856eac).
 *
 * Since 2026-02 individual plans have two pools that reset each billing cycle: Cursor models
 * (`autoPercentUsed`) and other models (`apiPercentUsed`). Percentages are already percent
 * (0.36 = 0.36%). `totalPercentUsed` has disagreed with the dashboard and is not used.
 *
 * Not an official API and its shape changes often, so a 200 without the required fields is an error
 * rather than a 0% gauge. No session answers 401. [transport] must not follow redirects: a redirect
 * (usually to the login page) means there is no session, and the cookie must not follow it.
 */
class CursorUsageClient(
    private val transport: NetworkTransport,
    private val endpoint: String = SUMMARY_URL,
) : PlanReportingUsageClient {
    override suspend fun fetchUsage(tokens: OAuthTokens): ProviderUsage {
        val cookie = CursorAuth.cookieHeader(tokens) ?: throw UsageException.Unauthorized()
        val response = transport.execute(
            Request.Builder()
                .url(endpoint)
                .get()
                .header("Cookie", cookie)
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .build(),
        )
        if (response.statusCode == 401 || response.statusCode == 403 || response.statusCode in 300..399) {
            throw UsageException.Unauthorized()
        }
        response.requireUsageSuccess()
        return CursorUsageMapper.usage(response.body)
    }

    companion object {
        const val SUMMARY_URL = "https://cursor.com/api/usage-summary"
        private const val USER_AGENT = "TokenWatch/1.0"
    }
}

object CursorUsageMapper {
    const val CURSOR_MODELS_LABEL = "Cursor models"
    const val OTHER_MODELS_LABEL = "Other models"
    const val INCLUDED_LABEL = "Included usage"

    fun usage(body: ByteArray): ProviderUsage = decodeSubscriptionUsage(body, ::usageFrom)

    fun usageFrom(root: Map<String, Any?>): ProviderUsage {
        // No session is a 401, so a 200 without a billing cycle or plan usage means the shape changed.
        val end = (root["billingCycleEnd"] as? String)?.let(::parseIsoInstant)
        val plan = root["individualUsage"].asObject()?.get("plan").asObject()
        if (end == null || plan == null) throw UsageException.Decode("unexpected Cursor usage summary")

        val start = (root["billingCycleStart"] as? String)?.let(::parseIsoInstant)
        val seconds = if (start != null && end.isAfter(start)) {
            Duration.between(start, end).toMillis() / 1_000.0
        } else {
            null
        }
        fun window(label: String, percent: Double) = UsageWindow(
            label = label,
            usedPercent = percent.coerceIn(0.0, 100.0),
            resetsAt = end,
            kind = WindowKind.WEEKLY,
            windowSeconds = seconds,
        )

        val windows = buildList {
            plan["autoPercentUsed"].asNumber()?.takeIf(Double::isFinite)?.let { add(window(CURSOR_MODELS_LABEL, it)) }
            plan["apiPercentUsed"].asNumber()?.takeIf(Double::isFinite)?.let { add(window(OTHER_MODELS_LABEL, it)) }
            // An older response without pool percentages falls back to the included-usage amount.
            if (isEmpty()) {
                val limit = plan["limit"].asNumber()
                val used = plan["used"].asNumber()
                if (limit != null && limit > 0.0 && used != null && used >= 0.0) {
                    add(window(INCLUDED_LABEL, used / limit * 100.0))
                }
            }
        }
        return ProviderUsage(windows, planLabel(root["membershipType"] as? String))
    }

    /** `membershipType` → display plan name. Terminal chrome, so it is not translated. */
    fun planLabel(membershipType: String?): String? {
        val raw = membershipType?.trim()?.takeIf(String::isNotEmpty) ?: return null
        return when (raw.lowercase()) {
            "free", "hobby" -> "Hobby"
            "free_trial" -> "Pro Trial"
            "pro" -> "Pro"
            "pro_student" -> "Pro Student"
            "pro_plus" -> "Pro Plus"
            "ultra" -> "Ultra"
            "express" -> "Start"
            "team", "business" -> "Teams"
            "enterprise" -> "Enterprise"
            else -> raw.split('_').joinToString(" ") { part ->
                part.take(1).uppercase() + part.drop(1)
            }
        }
    }
}
