package com.ScienceFiction.TokenWatchAndroid.network.providers.apikey

import com.ScienceFiction.TokenWatchAndroid.auth.OAuthTokens
import com.ScienceFiction.TokenWatchAndroid.domain.UsageWindow
import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkTransport
import java.util.Locale

class OpenRouterUsageClient(
    transport: NetworkTransport,
    endpoint: String = DEFAULT_ENDPOINT,
) : ApiKeyUsageClientBase(transport, endpoint) {
    override fun requestHeaders(tokens: OAuthTokens) =
        mapOf("Authorization" to "Bearer ${tokens.accessToken}")

    override fun mapSuccessfulBody(body: String): List<UsageWindow> {
        val response = checkNotNull(apiKeyProviderMoshi.adapter(Response::class.java).fromJson(body))
        val total = response.data?.total_credits ?: 0.0
        val remaining = (total - (response.data?.total_usage ?: 0.0)).coerceAtLeast(0.0)
        val value = String.format(Locale.US, "%.2f credits left", remaining)
        return listOf(
            balanceWindow(
                label = "Credits",
                valueText = value,
                balanceRemaining = remaining,
                balanceTotal = total,
            ),
        )
    }

    private data class Response(val data: Credits? = null)

    private data class Credits(
        val total_credits: Double? = null,
        val total_usage: Double? = null,
    )

    companion object {
        const val DEFAULT_ENDPOINT = "https://openrouter.ai/api/v1/credits"
    }
}
