package com.ScienceFiction.TokenWatchAndroid.network.providers.apikey

import com.ScienceFiction.TokenWatchAndroid.auth.OAuthTokens
import com.ScienceFiction.TokenWatchAndroid.domain.AgentProvider
import com.ScienceFiction.TokenWatchAndroid.domain.AuthKind
import com.ScienceFiction.TokenWatchAndroid.domain.UsageWindow
import com.ScienceFiction.TokenWatchAndroid.domain.WindowKind
import com.ScienceFiction.TokenWatchAndroid.localization.L10n
import com.ScienceFiction.TokenWatchAndroid.localization.Lang
import com.ScienceFiction.TokenWatchAndroid.network.core.UsageException
import java.time.Instant
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kimi Code `/coding/v1/usages` mapping, ported from iOS KimiUsageTests: legacy fixtures are measured
 * responses and CodexBar samples, the new shape follows the official kimi-code CLI parser.
 */
class KimiUsageClientTest {
    private fun windows(json: String): List<UsageWindow> = KimiUsageMapper.windows(json.toByteArray())

    /** Measured legacy (percent) response: 5 hours at 1%, weekly with only `remaining`, so 0%. */
    @Test fun measuredLegacyResponseMapsToSessionAndWeek() {
        val result = windows(
            """{"user":{"membership":{"level":"LEVEL_ADVANCED"}},
             "usage":{"limit":"100","remaining":"100","resetTime":"2026-09-21T03:17:42Z"},
             "limits":[{"window":{"duration":300,"timeUnit":"TIME_UNIT_MINUTE"},
                        "detail":{"limit":"100","used":"1","remaining":"99","resetTime":"2026-09-14T10:17:42Z"}}],
             "parallel":{"limit":"30"},
             "authentication":{"method":"METHOD_API_KEY","scope":"FEATURE_CODING"}}""",
        )
        assertEquals(listOf("Current session", "Current week"), result.map { it.label })
        assertEquals(listOf(1.0, 0.0), result.map { it.usedPercent })
        assertEquals(listOf(WindowKind.SESSION, WindowKind.WEEKLY), result.map { it.kind })
        assertEquals(5.0 * 3600, result.first().windowSeconds!!, 0.0)
        assertEquals(7.0 * 24 * 3600, result.last().windowSeconds!!, 0.0)
        assertEquals(Instant.parse("2026-09-14T10:17:42Z"), result.first().resetsAt)
        assertEquals(Instant.parse("2026-09-21T03:17:42Z"), result.last().resetsAt)
    }

    /** Request-count legacy shape: counts as strings, timestamps with nanosecond digits. */
    @Test fun requestCountLegacyShapeAndLongFractionsAreRead() {
        val result = windows(
            """{"usage":{"limit":"2048","used":"214","remaining":"1834","resetTime":"2026-01-09T15:23:13.716839300Z"},
             "limits":[{"window":{"duration":300,"timeUnit":"TIME_UNIT_MINUTE"},
                        "detail":{"limit":"200","used":"139","remaining":"61","resetTime":"2026-01-06T12:00:00Z"}}]}""",
        )
        assertEquals(69.5, result.first().usedPercent, 0.001)
        assertEquals(214.0 / 2048 * 100, result.last().usedPercent, 0.001)
        assertEquals(Instant.parse("2026-01-09T15:23:13.716839300Z"), result.last().resetsAt)
    }

    /** New shape (official CLI, 2026-09-15): used_ratio 0…1, string ratios accepted. */
    @Test fun newShapeMapsRatios() {
        val result = windows(
            """{"usages":{"limit_5h":{"used_ratio":0.25,"reset_time":"2026-09-15T12:00:00Z"},
                       "limit_7d":{"used_ratio":"0.5","reset_time":"2026-09-20T00:00:00Z"}}}""",
        )
        assertEquals(listOf("Current session", "Current week"), result.map { it.label })
        assertEquals(listOf(25.0, 50.0), result.map { it.usedPercent })
        assertEquals(Instant.parse("2026-09-20T00:00:00Z"), result.last().resetsAt)
    }

    /** New plans: 5 hours plus two monthly entries whose window length is unknown. */
    @Test fun newPlansHaveTwoMonthlyEntries() {
        val result = windows(
            """{"usages":{"limit_5h":{"used_ratio":0.1,"reset_time":"2026-09-15T12:00:00Z"},
                       "limit_month_total":{"used_ratio":0.4,"reset_time":"2026-10-01T00:00:00Z"},
                       "limit_month_code":{"used_ratio":0.3,"reset_time":"2026-10-01T00:00:00Z"}}}""",
        )
        assertEquals(listOf("Current session", "Current month", "Current month (Code)"), result.map { it.label })
        assertEquals(listOf(10.0, 40.0, 30.0), result.map { Math.round(it.usedPercent * 10) / 10.0 })
        assertNull(result.last().windowSeconds)
    }

    /** Mid-migration: an empty new shape falls back to the legacy one so no data is lost. */
    @Test fun anEmptyNewShapeFallsBackToLegacy() {
        val json = """{"usages":{},"usage":{"limit":"100","used":"12","remaining":"88","resetTime":"2026-09-21T03:17:42Z"}}"""
        assertEquals(listOf(12.0), windows(json).map { it.usedPercent })
    }

    /** Nothing usable means no window, which the card reports as no-windows. */
    @Test fun nothingUsableYieldsNoWindows() {
        assertTrue(windows("{}").isEmpty())
        assertTrue(windows("""{"usages":{"limit_5h":{"used_ratio":"abc"}}}""").isEmpty())
        assertTrue(windows("""{"usage":{"limit":"0","remaining":"0"}}""").isEmpty())
        // A non-5-hour window is not mistaken for the session window.
        assertTrue(
            windows("""{"limits":[{"window":{"duration":1,"timeUnit":"TIME_UNIT_DAY"},"detail":{"limit":"10","used":"1"}}]}""")
                .isEmpty(),
        )
    }

    @Test fun nonObjectsAreErrors() {
        assertThrows(UsageException.Decode::class.java) { windows("[]") }
        assertThrows(UsageException.Decode::class.java) { windows("not json") }
    }

    @Test fun outOfRangeValuesAreClamped() {
        assertEquals(
            listOf(100.0, 0.0),
            windows("""{"usages":{"limit_5h":{"used_ratio":1.3},"limit_7d":{"used_ratio":-0.2}}}""").map { it.usedPercent },
        )
    }

    /** `/me` also has email and phone; only the plan name is taken. */
    @Test fun planNameIsTheOnlyFieldRead() {
        val me = """{"user_id":"u_1","user_level":30,"user_level_name":"Vivace","email":"user@example.com","phone":{"country_code":"86","number":"176****0000"}}"""
        assertEquals("Vivace", KimiUsageMapper.planName(me.toByteArray()))
        assertNull(KimiUsageMapper.planName("""{"user_level_name":""}""".toByteArray()))
        assertNull(KimiUsageMapper.planName("[]".toByteArray()))
    }

    @Test fun onlyNumbersAndNumericStringsAreNumbers() {
        assertEquals(100.0, KimiUsageMapper.number("100")!!, 0.0)
        assertEquals(1.0, KimiUsageMapper.number(1.0)!!, 0.0)
        assertNull(KimiUsageMapper.number(true))
        assertNull(KimiUsageMapper.number("abc"))
        assertNull(KimiUsageMapper.number(null))
    }

    @Test fun kimiIsAnApiKeyProviderCheckingBothRegionsInOrder() {
        assertEquals(AuthKind.API_KEY, AgentProvider.KIMI.authKind)
        assertEquals("www.kimi.com", AgentProvider.KIMI.apiKeyUrl?.toHttpUrl()?.host)
        assertEquals(listOf("api.kimi.com", "api.kimi.ai"), KimiUsageClient.HOSTS)
        assertEquals("https://api.kimi.ai/coding/v1/usages", KimiUsageClient.usagesUrl("api.kimi.ai"))
        assertNotNull(L10n(Lang.KO).apiKeyHint(AgentProvider.KIMI))
        assertNull(L10n(Lang.EN).apiKeyHint(AgentProvider.OPENROUTER))
    }

    /** Usage goes to the host stored with the key; an unknown stored value falls back to the first. */
    @Test fun usageUsesTheStoredHost() = runBlocking {
        val transport = FakeNetworkTransport(networkResponse(body = """{"usages":{"limit_5h":{"used_ratio":0.5}}}"""))
        val client = KimiUsageClient(transport)
        client.fetch(OAuthTokens.apiKey("sk", accountId = "api.kimi.ai"))
        client.fetch(OAuthTokens.apiKey("sk", accountId = "evil.example"))
        assertEquals(listOf("api.kimi.ai", "api.kimi.com"), transport.requests.map { it.url.host })
        assertEquals("Bearer sk", transport.requests.first().header("Authorization"))
    }
}
