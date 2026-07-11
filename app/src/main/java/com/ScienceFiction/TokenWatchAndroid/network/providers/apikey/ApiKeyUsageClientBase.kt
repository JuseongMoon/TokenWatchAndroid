package com.ScienceFiction.TokenWatchAndroid.network.providers.apikey

import com.ScienceFiction.TokenWatchAndroid.auth.OAuthTokens
import com.ScienceFiction.TokenWatchAndroid.domain.UsageWindow
import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkTransport
import com.ScienceFiction.TokenWatchAndroid.network.core.ProviderUsageClient
import com.ScienceFiction.TokenWatchAndroid.network.core.UsageException
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.Request

internal val apiKeyProviderMoshi: Moshi = Moshi.Builder()
    .addLast(KotlinJsonAdapterFactory())
    .build()

abstract class ApiKeyUsageClientBase protected constructor(
    private val transport: NetworkTransport,
    private val endpoint: String,
    private val unauthorizedStatuses: Set<Int> = setOf(401),
) : ProviderUsageClient {
    final override suspend fun fetch(tokens: OAuthTokens): List<UsageWindow> {
        val requestBuilder = Request.Builder()
            .url(endpoint)
            .get()
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
        requestHeaders(tokens).forEach(requestBuilder::header)

        val response = transport.execute(requestBuilder.build())
        if (response.statusCode in unauthorizedStatuses) throw UsageException.Unauthorized()
        response.requireUsageSuccess()

        return try {
            mapSuccessfulBody(response.bodyText())
        } catch (exception: UsageException) {
            throw exception
        } catch (exception: Exception) {
            throw UsageException.Decode(exception.message ?: "Failed to decode provider usage", exception)
        }
    }

    protected abstract fun requestHeaders(tokens: OAuthTokens): Map<String, String>

    protected abstract fun mapSuccessfulBody(body: String): List<UsageWindow>

    private companion object {
        const val USER_AGENT = "TokenWatch/1.0"
    }
}

internal fun balanceWindow(label: String, valueText: String) = UsageWindow(
    label = label,
    usedPercent = 0.0,
    resetsAt = null,
    kind = com.ScienceFiction.TokenWatchAndroid.domain.WindowKind.WEEKLY,
    style = com.ScienceFiction.TokenWatchAndroid.domain.UsageStyle.BALANCE,
    valueText = valueText,
)
