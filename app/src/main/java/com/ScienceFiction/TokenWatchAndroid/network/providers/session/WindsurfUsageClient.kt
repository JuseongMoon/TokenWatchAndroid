package com.ScienceFiction.TokenWatchAndroid.network.providers.session

import com.ScienceFiction.TokenWatchAndroid.auth.OAuthTokens
import com.ScienceFiction.TokenWatchAndroid.domain.UsageWindow
import com.ScienceFiction.TokenWatchAndroid.domain.WindowKind
import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkTransport
import com.ScienceFiction.TokenWatchAndroid.network.core.ProviderUsageClient
import com.ScienceFiction.TokenWatchAndroid.network.core.UsageException
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Windsurf captured-session usage client matching clean iOS commit 6df2689. */
class WindsurfUsageClient(
    private val transport: NetworkTransport,
    private val endpoint: String = DEFAULT_ENDPOINT,
) : ProviderUsageClient {
    override suspend fun fetch(tokens: OAuthTokens): List<UsageWindow> {
        val builder = Request.Builder()
            .url(endpoint)
            .post(EMPTY_JSON_OBJECT.toRequestBody(JSON_MEDIA_TYPE))
            .header("Content-Type", JSON_CONTENT_TYPE)
            .header("Accept", JSON_CONTENT_TYPE)
            .header("Connect-Protocol-Version", "1")
            .header("User-Agent", USER_AGENT)

        // accessToken is the JSON-packed header dictionary captured from localStorage.
        unpackHeaders(tokens.accessToken).forEach { (name, value) -> builder.header(name, value) }

        val response = transport.execute(builder.build())
        if (response.statusCode == 403) throw UsageException.Unauthorized()
        response.requireUsageSuccess()
        return map(response.body)
    }

    companion object {
        const val DEFAULT_ENDPOINT =
            "https://windsurf.com/_backend/exa.seat_management_pb.SeatManagementService/GetPlanStatus"

        private const val JSON_CONTENT_TYPE = "application/json"
        private const val USER_AGENT = "TokenWatch/1.0"
        private const val EMPTY_JSON_OBJECT = "{}"
        private val JSON_MEDIA_TYPE = JSON_CONTENT_TYPE.toMediaType()
        private val JSON_ADAPTER: JsonAdapter<Any> = Moshi.Builder().build().adapter(Any::class.java)

        /** Maps both observed ConnectRPC JSON key spellings from remaining% to used%. */
        fun map(data: ByteArray): List<UsageWindow> {
            val root = parseObject(data.toString(Charsets.UTF_8)) ?: return emptyList()
            val windows = mutableListOf<UsageWindow>()

            number(root, "dailyQuotaRemainingPercent", "daily_quota_remaining_percent")?.let { remaining ->
                windows += UsageWindow(
                    label = "Daily quota",
                    usedPercent = (100.0 - remaining).coerceIn(0.0, 100.0),
                    resetsAt = null,
                    kind = WindowKind.SESSION,
                )
            }
            number(root, "weeklyQuotaRemainingPercent", "weekly_quota_remaining_percent")?.let { remaining ->
                windows += UsageWindow(
                    label = "Weekly quota",
                    usedPercent = (100.0 - remaining).coerceIn(0.0, 100.0),
                    resetsAt = null,
                    kind = WindowKind.WEEKLY,
                )
            }
            return windows
        }

        private fun unpackHeaders(json: String): Map<String, String> {
            val root = parseObject(json) ?: return emptyMap()
            return root.entries.mapNotNull { (key, value) ->
                if (key is String && value is String) key to value else null
            }.toMap()
        }

        private fun parseObject(json: String): Map<*, *>? = runCatching {
            JSON_ADAPTER.fromJson(json) as? Map<*, *>
        }.getOrNull()

        private fun number(root: Map<*, *>, vararg keys: String): Double? {
            for (key in keys) {
                when (val value = root[key]) {
                    is Number -> return value.toDouble()
                    is String -> value.toDoubleOrNull()?.let { return it }
                }
            }
            return null
        }
    }
}
