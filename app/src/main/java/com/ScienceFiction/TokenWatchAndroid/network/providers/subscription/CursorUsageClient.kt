package com.ScienceFiction.TokenWatchAndroid.network.providers.subscription

import com.ScienceFiction.TokenWatchAndroid.auth.OAuthTokens
import com.ScienceFiction.TokenWatchAndroid.auth.session.CursorSessionAuth
import com.ScienceFiction.TokenWatchAndroid.domain.UsageWindow
import com.ScienceFiction.TokenWatchAndroid.domain.WindowKind
import com.ScienceFiction.TokenWatchAndroid.network.core.HttpTransport
import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkTransport
import com.ScienceFiction.TokenWatchAndroid.network.core.ProviderUsageClient
import com.ScienceFiction.TokenWatchAndroid.network.core.UsageException
import java.time.ZoneId
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/** Cursor session-cookie usage client matching clean iOS commit 6df2689. */
class CursorUsageClient(
    private val transport: NetworkTransport = HttpTransport(),
    private val endpoint: HttpUrl = DefaultEndpoint.toHttpUrl(),
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) : ProviderUsageClient {
    override suspend fun fetch(tokens: OAuthTokens): List<UsageWindow> {
        val userId = tokens.accountId?.takeIf(String::isNotEmpty)
            ?: throw UsageException.Unauthorized()
        val requestUrl = endpoint.newBuilder()
            .setQueryParameter("user", userId)
            .build()
        val request = Request.Builder()
            .url(requestUrl)
            .get()
            .header("Cookie", "${CursorSessionAuth.cookieName}=${tokens.accessToken}")
            .header("Accept", "application/json")
            .header("User-Agent", UserAgent)
            .build()
        val response = transport.executeUsage(request, forbiddenIsUnauthorized = true)
        return decodeSubscriptionUsage(response.body, ::mapWindows)
    }

    private fun mapWindows(root: Map<String, Any?>): List<UsageWindow> {
        var startOfMonth: String? = null
        val buckets = linkedMapOf<String, Bucket>()
        for ((key, rawValue) in root) {
            if (key == "startOfMonth") {
                startOfMonth = rawValue as? String
                continue
            }
            val rawBucket = rawValue.asObject() ?: continue
            val bucket = runCatching { parseBucket(rawBucket) }.getOrNull() ?: continue
            if (bucket.numRequests != null || bucket.maxRequestUsage != null) {
                buckets[key] = bucket
            }
        }

        val bucket = buckets["gpt-4"]
            ?: buckets.values.firstOrNull { (it.maxRequestUsage ?: 0L) > 0L }
            ?: return emptyList()
        val maximum = bucket.maxRequestUsage?.takeIf { it > 0L } ?: return emptyList()
        val usedPercent = ((bucket.numRequests ?: 0L).toDouble() / maximum.toDouble() * 100.0)
            .coerceIn(0.0, 100.0)
        val resetsAt = parseIsoInstant(startOfMonth)
            ?.atZone(zoneId)
            ?.plusMonths(1)
            ?.toInstant()
        return listOf(
            UsageWindow(
                label = "Fast requests",
                usedPercent = usedPercent,
                resetsAt = resetsAt,
                kind = WindowKind.WEEKLY,
                windowSeconds = null,
            ),
        )
    }

    private fun parseBucket(value: Map<String, Any?>): Bucket = Bucket(
        numRequests = value.optionalLong("numRequests"),
        maxRequestUsage = value.optionalLong("maxRequestUsage"),
    )

    private fun Map<String, Any?>.optionalLong(key: String): Long? {
        if (!containsKey(key) || this[key] == null) return null
        return this[key].asLong()
            ?: throw IllegalArgumentException("$key must be an integer")
    }

    private data class Bucket(
        val numRequests: Long?,
        val maxRequestUsage: Long?,
    )

    companion object {
        const val DefaultEndpoint = "https://cursor.com/api/usage"
        const val UserAgent = "TokenWatch/1.0"
    }
}
