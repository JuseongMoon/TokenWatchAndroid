package com.ScienceFiction.TokenWatchAndroid.network.providers.subscription

import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkResponse
import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkTransport
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import okhttp3.Request

internal class RecordingTransport(
    var response: NetworkResponse = jsonResponse("{}"),
) : NetworkTransport {
    val requests = mutableListOf<Request>()

    override suspend fun execute(request: Request): NetworkResponse {
        requests += request
        return response
    }

    val lastRequest: Request
        get() = requests.last()
}

internal fun jsonResponse(
    json: String,
    statusCode: Int = 200,
    headers: Map<String, List<String>> = emptyMap(),
) = NetworkResponse(
    statusCode = statusCode,
    headers = headers,
    body = json.toByteArray(),
)

internal fun <T> runSuspend(block: suspend () -> T): T {
    var completion: Result<T>? = null
    block.startCoroutine(object : Continuation<T> {
        override val context = EmptyCoroutineContext

        override fun resumeWith(result: Result<T>) {
            completion = result
        }
    })
    return checkNotNull(completion) { "Test coroutine unexpectedly suspended" }.getOrThrow()
}
