package com.ScienceFiction.TokenWatchAndroid.network.providers.apikey

import com.ScienceFiction.TokenWatchAndroid.auth.OAuthTokens
import com.ScienceFiction.TokenWatchAndroid.domain.UsageWindow
import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkTransport
import kotlin.math.roundToInt

class DIDUsageClient(
    transport: NetworkTransport,
    endpoint: String = DEFAULT_ENDPOINT,
) : ApiKeyUsageClientBase(transport, endpoint, unauthorizedStatuses = setOf(401, 403)) {
    override fun requestHeaders(tokens: OAuthTokens) =
        mapOf("Authorization" to "Basic ${tokens.accessToken}")

    override fun mapSuccessfulBody(body: String): List<UsageWindow> {
        val response = checkNotNull(apiKeyProviderMoshi.adapter(Response::class.java).fromJson(body))
        val remaining = response.remaining ?: response.credits?.firstOrNull()?.remaining ?: 0.0
        val total = response.total ?: response.credits?.firstOrNull()?.total
        return listOf(
            balanceWindow(
                label = "Credits",
                valueText = "${remaining.roundToInt()} credits",
                balanceRemaining = remaining,
                balanceTotal = total,
            ),
        )
    }

    private data class Response(
        val remaining: Double? = null,
        val total: Double? = null,
        val credits: List<Item>? = null,
    )

    private data class Item(
        val remaining: Double? = null,
        val total: Double? = null,
    )

    companion object {
        const val DEFAULT_ENDPOINT = "https://api.d-id.com/credits"
    }
}
