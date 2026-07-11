package com.ScienceFiction.TokenWatchAndroid.network.providers.apikey

import com.ScienceFiction.TokenWatchAndroid.auth.OAuthTokens
import com.ScienceFiction.TokenWatchAndroid.domain.UsageWindow
import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkTransport
import java.util.Locale

class FalUsageClient(
    transport: NetworkTransport,
    endpoint: String = DEFAULT_ENDPOINT,
) : ApiKeyUsageClientBase(transport, endpoint, unauthorizedStatuses = setOf(401, 403)) {
    override fun requestHeaders(tokens: OAuthTokens) =
        mapOf("Authorization" to "Key ${tokens.accessToken}")

    override fun mapSuccessfulBody(body: String): List<UsageWindow> {
        val response = checkNotNull(apiKeyProviderMoshi.adapter(Response::class.java).fromJson(body))
        val credits = response.credits ?: return emptyList()
        val value = String.format(
            Locale.US,
            "%.2f %s",
            credits.current_balance ?: 0.0,
            credits.currency.orEmpty(),
        ).trim()
        return listOf(balanceWindow("Balance", value))
    }

    private data class Response(
        val username: String? = null,
        val credits: Credits? = null,
    )

    private data class Credits(
        val current_balance: Double? = null,
        val currency: String? = null,
    )

    companion object {
        const val DEFAULT_ENDPOINT = "https://api.fal.ai/v1/account/billing?expand=credits"
    }
}
