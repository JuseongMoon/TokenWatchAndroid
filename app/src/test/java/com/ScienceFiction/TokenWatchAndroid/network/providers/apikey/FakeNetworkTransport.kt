package com.ScienceFiction.TokenWatchAndroid.network.providers.apikey

import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkResponse
import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkTransport
import okhttp3.Request

internal class FakeNetworkTransport(
    var response: NetworkResponse,
) : NetworkTransport {
    val requests = mutableListOf<Request>()

    override suspend fun execute(request: Request): NetworkResponse {
        requests += request
        return response
    }
}

internal fun networkResponse(
    statusCode: Int = 200,
    body: String = "{}",
    headers: Map<String, List<String>> = emptyMap(),
) = NetworkResponse(
    statusCode = statusCode,
    headers = headers,
    body = body.toByteArray(),
)
