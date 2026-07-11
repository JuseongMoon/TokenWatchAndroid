package com.ScienceFiction.TokenWatchAndroid.network.providers.apikey

import com.ScienceFiction.TokenWatchAndroid.auth.OAuthTokens
import com.ScienceFiction.TokenWatchAndroid.domain.UsageWindow
import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkTransport
import kotlin.math.roundToInt

class RunwayUsageClient(
    transport: NetworkTransport,
    endpoint: String = DEFAULT_ENDPOINT,
) : ApiKeyUsageClientBase(transport, endpoint, unauthorizedStatuses = setOf(401, 403)) {
    override fun requestHeaders(tokens: OAuthTokens) = mapOf(
        "Authorization" to "Bearer ${tokens.accessToken}",
        "X-Runway-Version" to API_VERSION,
    )

    override fun mapSuccessfulBody(body: String): List<UsageWindow> {
        val response = checkNotNull(apiKeyProviderMoshi.adapter(Response::class.java).fromJson(body))
        val credits = (response.creditBalance ?: 0.0).roundToInt()
        return listOf(balanceWindow("Credits", "$credits credits"))
    }

    private data class Response(val creditBalance: Double? = null)

    companion object {
        const val DEFAULT_ENDPOINT = "https://api.dev.runwayml.com/v1/organization"
        const val API_VERSION = "2024-11-06"
    }
}
