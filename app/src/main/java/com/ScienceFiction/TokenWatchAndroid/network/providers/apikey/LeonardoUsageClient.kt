package com.ScienceFiction.TokenWatchAndroid.network.providers.apikey

import com.ScienceFiction.TokenWatchAndroid.auth.OAuthTokens
import com.ScienceFiction.TokenWatchAndroid.domain.UsageWindow
import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkTransport

class LeonardoUsageClient(
    transport: NetworkTransport,
    endpoint: String = DEFAULT_ENDPOINT,
) : ApiKeyUsageClientBase(transport, endpoint, unauthorizedStatuses = setOf(401, 403)) {
    override fun requestHeaders(tokens: OAuthTokens) =
        mapOf("Authorization" to "Bearer ${tokens.accessToken}")

    override fun mapSuccessfulBody(body: String): List<UsageWindow> {
        val response = checkNotNull(apiKeyProviderMoshi.adapter(Response::class.java).fromJson(body))
        val detail = response.user_details?.firstOrNull() ?: return emptyList()
        val apiTokens = (detail.apiSubscriptionTokens ?: 0) + (detail.apiPaidTokens ?: 0)
        val tokensLeft = if (apiTokens > 0) apiTokens else detail.subscriptionTokens ?: 0
        return listOf(balanceWindow("API tokens", "$tokensLeft tokens"))
    }

    private data class Response(val user_details: List<Detail>? = null)

    private data class Detail(
        val subscriptionTokens: Int? = null,
        val apiSubscriptionTokens: Int? = null,
        val apiPaidTokens: Int? = null,
    )

    companion object {
        const val DEFAULT_ENDPOINT = "https://cloud.leonardo.ai/api/rest/v1/me"
    }
}
