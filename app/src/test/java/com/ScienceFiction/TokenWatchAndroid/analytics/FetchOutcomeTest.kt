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

    /** D021: the comparison against the stored outcome (null: none, "ok", or a reason). */
    @Test
    fun onlyTransitionsAreReported() {
        val network = FetchErrorReason.NETWORK
        val ok = FetchOutcomeLedger.OK

        // None or ok, then an error: one error.
        assertEquals("usage_fetch_error", fetchOutcomeEvent(provider, null, network)?.eventName)
        assertEquals("usage_fetch_error", fetchOutcomeEvent(provider, ok, network)?.eventName)
        // The same error again stays quiet, so one broken provider cannot flood.
        assertNull(fetchOutcomeEvent(provider, "network", network))
        // A different reason is a new error.
        val changed = fetchOutcomeEvent(provider, "network", FetchErrorReason.HTTP_5XX)
        assertEquals("http_5xx", changed?.parameters?.get("reason"))

        // Recovery closes the pair; steady success, and a first success, stay quiet.
        assertTrue(fetchOutcomeEvent(provider, "network", null) is AnalyticsEvent.UsageFetchRecover)
        assertNull(fetchOutcomeEvent(provider, ok, null))
        assertNull(fetchOutcomeEvent(provider, null, null))

        // After "paused" (analytics was off, or demo mode) a result only sets the baseline.
        assertNull(fetchOutcomeEvent(provider, FetchOutcomeLedger.PAUSED, network))
        assertNull(fetchOutcomeEvent(provider, FetchOutcomeLedger.PAUSED, null))
    }

    @Test
    fun errorEventCarriesOnlyProviderAndBucket() {
        val event = fetchOutcomeEvent(provider, null, UsageException.Http(500, "upstream said something identifying").toFetchErrorReason())!!
        assertEquals(mapOf("provider" to "claude", "reason" to "http_5xx"), event.parameters)
        assertEquals("usage_fetch_error", event.eventName)
    }
}
