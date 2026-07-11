package com.ScienceFiction.TokenWatchAndroid.network.providers.apikey

import com.ScienceFiction.TokenWatchAndroid.auth.OAuthTokens
import com.ScienceFiction.TokenWatchAndroid.domain.UsageWindow
import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkTransport
import java.util.Locale

class PoeUsageClient(
    transport: NetworkTransport,
    endpoint: String = DEFAULT_ENDPOINT,
) : ApiKeyUsageClientBase(transport, endpoint, unauthorizedStatuses = setOf(401, 403)) {
    override fun requestHeaders(tokens: OAuthTokens) =
        mapOf("Authorization" to "Bearer ${tokens.accessToken}")

    override fun mapSuccessfulBody(body: String): List<UsageWindow> {
        val response = checkNotNull(apiKeyProviderMoshi.adapter(Response::class.java).fromJson(body))
        val value = String.format(Locale.US, "%,d pts", response.current_point_balance ?: 0L)
        return listOf(balanceWindow("Compute points", value))
    }

    private data class Response(val current_point_balance: Long? = null)

    companion object {
        const val DEFAULT_ENDPOINT = "https://api.poe.com/usage/current_balance"
    }
}
