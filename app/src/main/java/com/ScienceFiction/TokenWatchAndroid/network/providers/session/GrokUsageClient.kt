package com.ScienceFiction.TokenWatchAndroid.network.providers.session

import com.ScienceFiction.TokenWatchAndroid.auth.OAuthTokens
import com.ScienceFiction.TokenWatchAndroid.domain.UsageWindow
import com.ScienceFiction.TokenWatchAndroid.domain.WindowKind
import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkTransport
import com.ScienceFiction.TokenWatchAndroid.network.core.ProviderUsageClient
import com.ScienceFiction.TokenWatchAndroid.network.core.UsageException
import com.ScienceFiction.TokenWatchAndroid.network.parsing.Protobuf
import java.time.Instant
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Grok session-cookie usage client matching clean iOS commit 6df2689. */
class GrokUsageClient(
    private val transport: NetworkTransport,
    private val endpoint: String = DEFAULT_ENDPOINT,
) : ProviderUsageClient {
    override suspend fun fetch(tokens: OAuthTokens): List<UsageWindow> {
        val request = Request.Builder()
            .url(endpoint)
            .post(EMPTY_GRPC_MESSAGE.toRequestBody(GRPC_WEB_MEDIA_TYPE))
            .header("Content-Type", GRPC_WEB_CONTENT_TYPE)
            .header("Accept", GRPC_WEB_CONTENT_TYPE)
            .header("Cookie", tokens.accessToken)
            .header("User-Agent", USER_AGENT)
            .header("x-grpc-web", "1")
            .build()

        val response = transport.execute(request)
        if (response.statusCode == 403) throw UsageException.Unauthorized()
        response.requireUsageSuccess()

        response.header("grpc-status")
            ?.takeIf { it.isNotEmpty() && it != "0" }
            ?.let { throw UsageException.Unauthorized() }

        val message = Protobuf.grpcWebMessage(response.body) ?: return emptyList()
        return map(message)
    }

    companion object {
        const val DEFAULT_ENDPOINT =
            "https://grok.com/grok_api_v2.GrokBuildBilling/GetGrokCreditsConfig"

        private const val GRPC_WEB_CONTENT_TYPE = "application/grpc-web+proto"
        private const val USER_AGENT = "TokenWatch/1.0"
        private val GRPC_WEB_MEDIA_TYPE = GRPC_WEB_CONTENT_TYPE.toMediaType()
        private val EMPTY_GRPC_MESSAGE = byteArrayOf(0, 0, 0, 0, 0)

        /** Maps schema-less protobuf numeric leaves using the iOS heuristic. */
        fun map(message: ByteArray): List<UsageWindow> {
            val numbers = Protobuf.collectNumbers(message)
            val usedPercent = numbers.doubles.firstOrNull { it in 0.0..100.0 }
                ?: numbers.varints.firstOrNull { it <= 100uL }?.toDouble()
                ?: return emptyList()

            val resetsAt = numbers.varints.firstNotNullOfOrNull(::timestamp)
            return listOf(
                UsageWindow(
                    label = "Credits",
                    usedPercent = usedPercent.coerceIn(0.0, 100.0),
                    resetsAt = resetsAt,
                    kind = WindowKind.WEEKLY,
                ),
            )
        }

        private fun timestamp(value: ULong): Instant? = when (value) {
            in 1_500_000_000uL..4_100_000_000uL -> Instant.ofEpochSecond(value.toLong())
            in 1_500_000_000_000uL..4_100_000_000_000uL -> Instant.ofEpochMilli(value.toLong())
            else -> null
        }
    }
}
