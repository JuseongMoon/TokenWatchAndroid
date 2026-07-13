package com.ScienceFiction.TokenWatchAndroid.network.providers.apikey

import com.ScienceFiction.TokenWatchAndroid.auth.OAuthTokens
import com.ScienceFiction.TokenWatchAndroid.domain.UsageWindow
import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkTransport
import java.util.Locale

class LumaUsageClient(
    transport: NetworkTransport,
    endpoint: String = DEFAULT_ENDPOINT,
) : ApiKeyUsageClientBase(transport, endpoint, unauthorizedStatuses = setOf(401, 403)) {
    override fun requestHeaders(tokens: OAuthTokens) =
        mapOf("Authorization" to "Bearer ${tokens.accessToken}")

    override fun mapSuccessfulBody(body: String): List<UsageWindow> {
        val response = checkNotNull(apiKeyProviderMoshi.adapter(Response::class.java).fromJson(body))
        val usd = (response.credit_balance ?: 0.0) / 100.0
        val value = String.format(Locale.US, "%.2f USD", usd)
        return listOf(
            balanceWindow(
                label = "Balance",
                valueText = value,
                balanceRemaining = usd,
            ),
        )
    }

    private data class Response(val credit_balance: Double? = null)

    companion object {
        const val DEFAULT_ENDPOINT = "https://api.lumalabs.ai/dream-machine/v1/credits"
    }
}
