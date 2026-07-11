package com.ScienceFiction.TokenWatchAndroid.network.core

import java.time.Instant

sealed class UsageException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Unauthorized : UsageException("Authentication expired")
    class RateLimited(val retryAfter: Instant?) : UsageException("Rate limited")
    class Http(val statusCode: Int, val responseBody: String) :
        UsageException("Usage request failed with HTTP $statusCode")
    class Decode(detail: String, cause: Throwable? = null) : UsageException(detail, cause)
    class NoWindows : UsageException("No usage windows")
}
