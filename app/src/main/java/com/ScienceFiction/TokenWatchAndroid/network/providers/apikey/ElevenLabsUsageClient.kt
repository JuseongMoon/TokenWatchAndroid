package com.ScienceFiction.TokenWatchAndroid.network.providers.apikey

import com.ScienceFiction.TokenWatchAndroid.auth.OAuthTokens
import com.ScienceFiction.TokenWatchAndroid.domain.UsageWindow
import com.ScienceFiction.TokenWatchAndroid.domain.WindowKind
import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkTransport
import java.time.Instant

class ElevenLabsUsageClient(
    transport: NetworkTransport,
    endpoint: String = DEFAULT_ENDPOINT,
) : ApiKeyUsageClientBase(transport, endpoint) {
    override fun requestHeaders(tokens: OAuthTokens) = mapOf("xi-api-key" to tokens.accessToken)

    override fun mapSuccessfulBody(body: String): List<UsageWindow> {
        val response = checkNotNull(apiKeyProviderMoshi.adapter(Response::class.java).fromJson(body))
        val used = response.character_count?.toDouble() ?: 0.0
        val limit = response.character_limit?.toDouble() ?: 0.0
        val usedPercent = if (limit > 0.0) (used / limit * 100.0).coerceIn(0.0, 100.0) else 0.0
        val resetsAt = response.next_character_count_reset_unix
            ?.takeIf { it > 0L }
            ?.let(Instant::ofEpochSecond)
        return listOf(
            UsageWindow(
                label = "Monthly characters",
                usedPercent = usedPercent,
                resetsAt = resetsAt,
                kind = WindowKind.WEEKLY,
            ),
        )
    }

    private data class Response(
        val character_count: Long? = null,
        val character_limit: Long? = null,
        val next_character_count_reset_unix: Long? = null,
        val tier: String? = null,
        val status: String? = null,
    )

    companion object {
        const val DEFAULT_ENDPOINT = "https://api.elevenlabs.io/v1/user/subscription"
    }
}
