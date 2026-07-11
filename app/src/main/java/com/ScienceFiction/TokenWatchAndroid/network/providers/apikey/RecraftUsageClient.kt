package com.ScienceFiction.TokenWatchAndroid.network.providers.apikey

import com.ScienceFiction.TokenWatchAndroid.auth.OAuthTokens
import com.ScienceFiction.TokenWatchAndroid.domain.UsageWindow
import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkTransport

class RecraftUsageClient(
    transport: NetworkTransport,
    endpoint: String = DEFAULT_ENDPOINT,
) : ApiKeyUsageClientBase(transport, endpoint) {
    override fun requestHeaders(tokens: OAuthTokens) =
        mapOf("Authorization" to "Bearer ${tokens.accessToken}")

    override fun mapSuccessfulBody(body: String): List<UsageWindow> {
        val response = checkNotNull(apiKeyProviderMoshi.adapter(Response::class.java).fromJson(body))
        return listOf(balanceWindow("Credits", "${response.credits ?: 0} credits"))
    }

    private data class Response(
        val credits: Int? = null,
        val email: String? = null,
        val name: String? = null,
    )

    companion object {
        const val DEFAULT_ENDPOINT = "https://external.api.recraft.ai/v1/users/me"
    }
}
