package com.ScienceFiction.TokenWatchAndroid.network.providers.apikey

import com.ScienceFiction.TokenWatchAndroid.auth.OAuthTokens
import com.ScienceFiction.TokenWatchAndroid.domain.UsageWindow
import com.ScienceFiction.TokenWatchAndroid.domain.WindowKind
import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkTransport
import com.ScienceFiction.TokenWatchAndroid.network.core.ProviderUsageClient
import com.ScienceFiction.TokenWatchAndroid.network.core.UsageException
import com.ScienceFiction.TokenWatchAndroid.network.providers.subscription.SubscriptionProviderJson
import com.ScienceFiction.TokenWatchAndroid.network.providers.subscription.asArray
import com.ScienceFiction.TokenWatchAndroid.network.providers.subscription.asObject
import com.ScienceFiction.TokenWatchAndroid.network.providers.subscription.parseIsoInstant
import kotlinx.coroutines.CancellationException
import okhttp3.Request

/** Both regional hosts rejected the key. */
class KimiKeyException : Exception("Kimi rejected the API key on every host")

/**
 * Kimi Code subscription usage: `GET https://api.kimi.com/coding/v1/usages` (global accounts use
 * api.kimi.ai), ported from iOS `KimiUsageClient` (db7fc6b). Same address and headers as the
 * official kimi-code CLI, without the CLI's identifying headers.
 *
 * The credential is an API key (`sk-kimi-…`) the user creates in the Kimi Code console. The CLI's
 * own device login is not used because it would mean impersonating the CLI.
 *
 * The endpoint is undocumented and mid-migration (the official CLI added a new parser on
 * 2026-09-15): the new `usages.limit_*` shape is read first, then the legacy `usage` + `limits[]`.
 */
class KimiUsageClient(
    private val transport: NetworkTransport,
) : ProviderUsageClient {
    /**
     * Finds the host the key works on before the card is created (only a 401 moves on to the next
     * host) and attaches the plan name. Throws when both hosts reject the key, so no card is made.
     */
    suspend fun prepareCredential(apiKey: String): OAuthTokens {
        val key = apiKey.trim()
        for (host in HOSTS) {
            try {
                fetchUsagesBody(key, host)
            } catch (_: UsageException.Unauthorized) {
                continue
            }
            // The plan name is decoration; failing to read it does not block adding the key.
            val plan = try {
                fetchPlanName(key, host)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            return OAuthTokens.apiKey(key, plan = plan, accountId = host)
        }
        throw KimiKeyException()
    }

    override suspend fun fetch(tokens: OAuthTokens): List<UsageWindow> {
        val host = tokens.accountId?.takeIf { it in HOSTS } ?: HOSTS.first()
        return KimiUsageMapper.windows(fetchUsagesBody(tokens.accessToken, host))
    }

    /** 401 → unauthorized (bad key); 403/404 → HTTP error, never a full gauge; 429 → rate limited. */
    private suspend fun fetchUsagesBody(key: String, host: String): ByteArray {
        val response = transport.execute(request(usagesUrl(host), key))
        response.requireUsageSuccess()
        return response.body
    }

    /** `user_level_name` from `/coding/v1/me`. The response also has email and phone; only this is read. */
    private suspend fun fetchPlanName(key: String, host: String): String? {
        val response = transport.execute(request(meUrl(host), key))
        if (response.statusCode != 200) return null
        return KimiUsageMapper.planName(response.body)
    }

    private fun request(url: String, key: String) = Request.Builder()
        .url(url)
        .get()
        .header("Authorization", "Bearer $key")
        .header("Accept", "application/json")
        .header("User-Agent", USER_AGENT)
        .build()

    companion object {
        /** Hosts tried in order (China default, then global), the official CLI's two defaults. */
        val HOSTS = listOf("api.kimi.com", "api.kimi.ai")
        private const val USER_AGENT = "TokenWatch/1.0"

        fun usagesUrl(host: String) = "https://$host/coding/v1/usages"
        fun meUrl(host: String) = "https://$host/coding/v1/me"
    }
}

object KimiUsageMapper {
    const val SESSION_LABEL = "Current session"
    const val WEEKLY_LABEL = "Current week"
    const val MONTHLY_LABEL = "Current month"
    const val MONTHLY_CODE_LABEL = "Current month (Code)"

    private const val FIVE_HOURS = 5.0 * 3_600
    private const val SEVEN_DAYS = 7.0 * 24 * 3_600

    private data class Spec(val key: String, val label: String, val kind: WindowKind, val seconds: Double?)

    private val modernSpecs = listOf(
        Spec("limit_5h", SESSION_LABEL, WindowKind.SESSION, FIVE_HOURS),
        Spec("limit_7d", WEEKLY_LABEL, WindowKind.WEEKLY, SEVEN_DAYS),
        Spec("limit_month_total", MONTHLY_LABEL, WindowKind.WEEKLY, null),
        Spec("limit_month_code", MONTHLY_CODE_LABEL, WindowKind.WEEKLY, null),
    )

    /** New shape first; the legacy shape when it yields nothing. Empty when neither has usable values. */
    fun windows(body: ByteArray): List<UsageWindow> {
        val root = try {
            SubscriptionProviderJson.root(body)
        } catch (error: Exception) {
            throw UsageException.Decode("Kimi usages is not a JSON object", error)
        }
        return modernWindows(root["usages"]).ifEmpty { legacyWindows(root) }
    }

    /** `usages.{limit_5h, limit_7d, limit_month_total, limit_month_code}.{used_ratio, reset_time}`. */
    private fun modernWindows(raw: Any?): List<UsageWindow> {
        val usages = raw.asObject() ?: return emptyList()
        return modernSpecs.mapNotNull { spec ->
            val entry = usages[spec.key].asObject() ?: return@mapNotNull null
            val ratio = number(entry["used_ratio"]) ?: return@mapNotNull null
            UsageWindow(
                label = spec.label,
                usedPercent = clampPercent(ratio * 100.0),
                resetsAt = (entry["reset_time"] as? String)?.let(::parseIsoInstant),
                kind = spec.kind,
                windowSeconds = spec.seconds,
            )
        }
    }

    /** `limits[]` item whose window is 300 minutes = 5 hours; `usage{limit, used?, remaining, resetTime}` = weekly. */
    private fun legacyWindows(root: Map<String, Any?>): List<UsageWindow> = buildList {
        root["limits"].asArray().orEmpty()
            .firstNotNullOfOrNull { item ->
                val entry = item.asObject() ?: return@firstNotNullOfOrNull null
                val window = entry["window"].asObject() ?: return@firstNotNullOfOrNull null
                if (windowSeconds(window) != FIVE_HOURS) return@firstNotNullOfOrNull null
                val detail = entry["detail"].asObject() ?: return@firstNotNullOfOrNull null
                legacyWindow(detail, SESSION_LABEL, WindowKind.SESSION, FIVE_HOURS)
            }
            ?.let(::add)
        root["usage"].asObject()
            ?.let { legacyWindow(it, WEEKLY_LABEL, WindowKind.WEEKLY, SEVEN_DAYS) }
            ?.let(::add)
    }

    /** Counts arrive as strings. Without `used`, it is `limit − remaining`. */
    private fun legacyWindow(entry: Map<String, Any?>, label: String, kind: WindowKind, seconds: Double): UsageWindow? {
        val limit = number(entry["limit"])?.takeIf { it > 0.0 } ?: return null
        val used = number(entry["used"])
            ?: number(entry["remaining"])?.let { limit - it }
            ?: return null
        return UsageWindow(
            label = label,
            usedPercent = clampPercent(used / limit * 100.0),
            resetsAt = (entry["resetTime"] as? String)?.let(::parseIsoInstant),
            kind = kind,
            windowSeconds = seconds,
        )
    }

    /** `window{duration, timeUnit}` → seconds; null for an unknown unit. */
    private fun windowSeconds(window: Map<String, Any?>): Double? {
        val duration = number(window["duration"]) ?: return null
        return when (window["timeUnit"]) {
            "TIME_UNIT_SECOND" -> duration
            "TIME_UNIT_MINUTE" -> duration * 60
            "TIME_UNIT_HOUR" -> duration * 3_600
            "TIME_UNIT_DAY" -> duration * 86_400
            else -> null
        }
    }

    /** A number or numeric string. Booleans and non-finite values are not numbers here. */
    fun number(value: Any?): Double? {
        val parsed = when (value) {
            is Boolean -> null
            is Number -> value.toDouble()
            is String -> value.trim().toDoubleOrNull()
            else -> null
        }
        return parsed?.takeIf(Double::isFinite)
    }

    private fun clampPercent(value: Double): Double = value.coerceIn(0.0, 100.0)

    /** Only the plan name from `/me`; null when empty. */
    fun planName(body: ByteArray): String? {
        val root = try {
            SubscriptionProviderJson.root(body)
        } catch (_: Exception) {
            return null
        }
        return (root["user_level_name"] as? String)?.trim()?.takeIf(String::isNotEmpty)
    }
}
