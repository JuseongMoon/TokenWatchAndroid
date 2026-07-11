package com.ScienceFiction.TokenWatchAndroid.network.providers.apikey

import com.ScienceFiction.TokenWatchAndroid.auth.OAuthTokens
import com.ScienceFiction.TokenWatchAndroid.domain.UsageWindow
import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkTransport

class DeepSeekUsageClient(
    transport: NetworkTransport,
    endpoint: String = DEFAULT_ENDPOINT,
) : ApiKeyUsageClientBase(transport, endpoint) {
    override fun requestHeaders(tokens: OAuthTokens) =
        mapOf("Authorization" to "Bearer ${tokens.accessToken}")

    override fun mapSuccessfulBody(body: String): List<UsageWindow> {
        val response = checkNotNull(apiKeyProviderMoshi.adapter(Response::class.java).fromJson(body))
        val balance = response.balance_infos?.firstOrNull() ?: return emptyList()
        val value = "${balance.total_balance ?: "0"} ${balance.currency.orEmpty()}".trim()
        return listOf(balanceWindow("Balance", value))
    }

    private data class Response(
        val is_available: Boolean? = null,
        val balance_infos: List<BalanceInfo>? = null,
    )

    private data class BalanceInfo(
        val currency: String? = null,
        val total_balance: String? = null,
    )

    companion object {
        const val DEFAULT_ENDPOINT = "https://api.deepseek.com/user/balance"
    }
}
