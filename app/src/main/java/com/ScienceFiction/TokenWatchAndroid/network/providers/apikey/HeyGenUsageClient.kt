package com.ScienceFiction.TokenWatchAndroid.network.providers.apikey

import com.ScienceFiction.TokenWatchAndroid.auth.OAuthTokens
import com.ScienceFiction.TokenWatchAndroid.domain.UsageWindow
import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkTransport
import java.util.Locale

class HeyGenUsageClient(
    transport: NetworkTransport,
    endpoint: String = DEFAULT_ENDPOINT,
) : ApiKeyUsageClientBase(transport, endpoint, unauthorizedStatuses = setOf(401, 403)) {
    override fun requestHeaders(tokens: OAuthTokens) = mapOf("X-Api-Key" to tokens.accessToken)

    override fun mapSuccessfulBody(body: String): List<UsageWindow> {
        val response = checkNotNull(apiKeyProviderMoshi.adapter(Response::class.java).fromJson(body))
        val quota = response.data?.remaining_quota ?: return emptyList()
        val credits = quota / 60.0
        val value = String.format(Locale.US, "%.0f credits", credits)
        return listOf(
            balanceWindow(
                label = "Credits",
                valueText = value,
                balanceRemaining = credits,
            ),
        )
    }

    private data class Response(val data: Quota? = null)

    private data class Quota(val remaining_quota: Double? = null)

    companion object {
        const val DEFAULT_ENDPOINT = "https://api.heygen.com/v2/user/remaining_quota"
    }
}
