package com.ScienceFiction.TokenWatchAndroid.network.core

import java.time.Instant
import java.io.IOException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

data class NetworkResponse(
    val statusCode: Int,
    val headers: Map<String, List<String>>,
    val body: ByteArray,
) {
    fun header(name: String): String? = headers.entries
        .firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }
        ?.value
        ?.firstOrNull()

    fun bodyText(): String = body.toString(Charsets.UTF_8)

    fun requireUsageSuccess(now: Instant = Instant.now()): NetworkResponse {
        when (statusCode) {
            401 -> throw UsageException.Unauthorized()
            429 -> throw UsageException.RateLimited(parseRetryAfter(header("Retry-After"), now))
        }
        if (statusCode !in 200..299) throw UsageException.Http(statusCode, bodyText())
        return this
    }
}

fun interface NetworkTransport {
    suspend fun execute(request: Request): NetworkResponse
}

class HttpTransport(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .build(),
) : NetworkTransport {
    override suspend fun execute(request: Request): NetworkResponse =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, error: IOException) {
                        continuation.resumeWith(Result.failure(error))
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val result = runCatching {
                            response.use {
                                NetworkResponse(
                                    statusCode = it.code,
                                    headers = it.headers.toMultimap(),
                                    body = it.body?.bytes() ?: ByteArray(0),
                                )
                            }
                        }
                        result.fold(
                            onSuccess = { value ->
                                continuation.resumeWith(Result.success(value))
                            },
                            onFailure = { error ->
                                continuation.resumeWith(Result.failure(error))
                            },
                        )
                    }
                },
            )
        }
}
