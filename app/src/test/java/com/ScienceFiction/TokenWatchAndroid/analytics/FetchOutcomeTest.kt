package com.ScienceFiction.TokenWatchAndroid.analytics

import com.ScienceFiction.TokenWatchAndroid.domain.AgentProvider
import com.ScienceFiction.TokenWatchAndroid.network.core.UsageException
import java.io.IOException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FetchOutcomeTest {
    private val provider = AgentProvider.CLAUDE

    @Test
    fun failuresAreBucketedNotQuoted() {
        // Raw provider text must never reach analytics, so every failure collapses to a bucket.
        assertEquals(FetchErrorReason.AUTH, UsageException.Unauthorized().toFetchErrorReason())
        assertEquals(FetchErrorReason.RATE_LIMIT, UsageException.RateLimited(null).toFetchErrorReason())
        assertEquals(FetchErrorReason.HTTP_4XX, UsageException.Http(404, "secret body").toFetchErrorReason())
        assertEquals(FetchErrorReason.HTTP_5XX, UsageException.Http(503, "secret body").toFetchErrorReason())
        assertEquals(FetchErrorReason.PARSE, UsageException.Decode("field x").toFetchErrorReason())
        assertEquals(FetchErrorReason.EMPTY, UsageException.NoWindows().toFetchErrorReason())
        assertEquals(FetchErrorReason.NETWORK, UnknownHostException("api.example.com").toFetchErrorReason())
        assertEquals(FetchErrorReason.NETWORK, IOException("offline").toFetchErrorReason())
        assertEquals(FetchErrorReason.OTHER, IllegalStateException("boom").toFetchErrorReason())
    }

    @Test
    fun onlyTransitionsAreReported() {
        // First failure reports; further failures stay quiet so one broken provider cannot flood.
        val first = fetchOutcomeEvent(provider, IOException("offline"), previouslyFailed = false)
        assertTrue(first is AnalyticsEvent.UsageFetchError)
        assertNull(fetchOutcomeEvent(provider, IOException("offline"), previouslyFailed = true))

        // Recovery closes the pair, and steady success stays quiet too.
        val recovered = fetchOutcomeEvent(provider, null, previouslyFailed = true)
        assertTrue(recovered is AnalyticsEvent.UsageFetchRecover)
        assertNull(fetchOutcomeEvent(provider, null, previouslyFailed = false))
    }

    @Test
    fun errorEventCarriesOnlyProviderAndBucket() {
        val event = fetchOutcomeEvent(
            provider,
            UsageException.Http(500, "upstream said something identifying"),
            previouslyFailed = false,
        )!!
        assertEquals(mapOf("provider" to "claude", "reason" to "http_5xx"), event.parameters)
        assertEquals("usage_fetch_error", event.eventName)
    }
}
