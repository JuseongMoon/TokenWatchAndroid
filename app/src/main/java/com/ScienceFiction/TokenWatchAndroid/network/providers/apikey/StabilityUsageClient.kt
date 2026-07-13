package com.ScienceFiction.TokenWatchAndroid.network.providers.apikey

import com.ScienceFiction.TokenWatchAndroid.auth.OAuthTokens
import com.ScienceFiction.TokenWatchAndroid.domain.UsageWindow
import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkTransport
import java.util.Locale

class StabilityUsageClient(
    transport: NetworkTransport,
    endpoint: String = DEFAULT_ENDPOINT,
) : ApiKeyUsageClientBase(transport, endpoint) {
    override fun requestHeaders(tokens: OAuthTokens) =
        mapOf("Authorization" to "Bearer ${tokens.accessToken}")

    override fun mapSuccessfulBody(body: String): List<UsageWindow> {
        val response = checkNotNull(apiKeyProviderMoshi.adapter(Response::class.java).fromJson(body))
        val value = String.format(Locale.US, "%.2f credits", response.credits ?: 0.0)
        return listOf(
            balanceWindow(
                label = "Credits",
                valueText = value,
                balanceRemaining = response.credits,
            ),
        )
    }

    private data class Response(val credits: Double? = null)

    companion object {
        const val DEFAULT_ENDPOINT = "https://api.stability.ai/v1/user/balance"
    }
}
