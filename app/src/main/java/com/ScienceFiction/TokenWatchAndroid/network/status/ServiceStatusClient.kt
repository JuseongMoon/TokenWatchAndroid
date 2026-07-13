package com.ScienceFiction.TokenWatchAndroid.network.status

import com.ScienceFiction.TokenWatchAndroid.domain.ServiceHealth
import com.ScienceFiction.TokenWatchAndroid.domain.ServiceStatusSource
import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkTransport
import com.ScienceFiction.TokenWatchAndroid.network.parsing.ServiceStatusParser
import kotlinx.coroutines.CancellationException
import okhttp3.Request

/**
 * Fetches a provider's public status endpoint without allowing failures to disturb usage data.
 * Null is a transient fetch/parse failure; UNKNOWN is a successfully parsed unsupported value.
 */
class ServiceStatusClient(
    private val transport: NetworkTransport,
) {
    suspend fun fetch(source: ServiceStatusSource): ServiceHealth? = try {
        val response = transport.execute(
            Request.Builder()
                .url(source.jsonUrl)
                .get()
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .build(),
        )
        if (response.statusCode !in 200..299) null
        else ServiceStatusParser.parse(source.platform, response.body)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        null
    }

    companion object {
        const val USER_AGENT = "TokenWatch/1.0"
    }
}
