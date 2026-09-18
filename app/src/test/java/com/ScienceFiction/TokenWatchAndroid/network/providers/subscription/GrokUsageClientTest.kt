package com.ScienceFiction.TokenWatchAndroid.network.providers.subscription

import com.ScienceFiction.TokenWatchAndroid.auth.OAuthTokens
import com.ScienceFiction.TokenWatchAndroid.domain.UsageStyle
import com.ScienceFiction.TokenWatchAndroid.domain.WindowKind
import com.ScienceFiction.TokenWatchAndroid.network.core.ProviderUsage
import com.ScienceFiction.TokenWatchAndroid.network.core.UsageException
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Grok weekly usage mapping, ported from iOS GrokUsageTests; fixtures are TokenBar `agent_grok.rs`'s
 * measured responses and regressions.
 */
class GrokUsageClientTest {
    private fun usage(json: String): ProviderUsage = GrokBillingMapper.usage(json.toByteArray())

    private val weeklyPeriod =
        """"currentPeriod":{"type":"USAGE_PERIOD_TYPE_WEEKLY","start":"2026-07-15T00:00:00+00:00","end":"2026-07-22T00:00:00+00:00"}"""

    @Test fun measuredWeeklyResponseMapsToOneWindow() {
        val result = usage(
            """{"config":{$weeklyPeriod,
              "creditUsagePercent":4.0,"productUsage":[{"product":"GrokBuild","usagePercent":4.0}],"isUnifiedBillingUser":true,
              "billingPeriodStart":"2026-07-15T00:00:00+00:00","billingPeriodEnd":"2026-07-22T00:00:00+00:00"},
             "subscriptionTiers":"X Premium+"}""",
        )
        val window = result.windows.single()
        assertEquals("Current week", window.label)
        assertEquals(4.0, window.usedPercent, 0.0)
        assertEquals(WindowKind.WEEKLY, window.kind)
        assertEquals(UsageStyle.GAUGE, window.style)
        assertEquals(604_800.0, window.windowSeconds!!, 0.0)
        assertEquals(Instant.parse("2026-07-22T00:00:00Z"), window.resetsAt)
        assertEquals("X Premium+", result.plan)
    }

    /** Right after a reset the percent is omitted and only the weekly period arrives: 0% used. */
    @Test fun missingPercentRightAfterAResetIsZero() {
        val window = usage(
            """{"config":{$weeklyPeriod,"onDemandCap":{"val":0},"isUnifiedBillingUser":true,
              "billingPeriodStart":"2026-07-15T00:00:00+00:00","billingPeriodEnd":"2026-07-22T00:00:00+00:00"}}""",
        ).windows.single()
        assertEquals(0.0, window.usedPercent, 0.0)
        assertEquals(Instant.parse("2026-07-22T00:00:00Z"), window.resetsAt)
    }

    /** TokenBar #240: the pool is exhausted while the CLI's share is 96% — read the pool, not a row. */
    @Test fun anExhaustedPoolIsFullWhateverTheProductRowsSay() {
        val window = usage(
            """{"config":{$weeklyPeriod,"creditUsagePercent":100.0,
              "productUsage":[{"product":"GrokChat","usagePercent":4.0},{"product":"GrokBuild","usagePercent":96.0}]}}""",
        ).windows.single()
        assertEquals(100.0, window.usedPercent, 0.0)
        assertEquals(0.0, window.remainingPercent, 0.0)
    }

    @Test fun microsecondPeriodsAreRead() {
        val window = usage(
            """{"config":{"currentPeriod":{"type":"USAGE_PERIOD_TYPE_WEEKLY","start":"2026-07-07T15:40:06.727001+00:00","end":"2026-07-14T15:40:06.727001+00:00"},
              "creditUsagePercent":4.0,"billingPeriodEnd":"2026-07-14T15:40:06.727001+00:00"},
             "subscriptionTiers":"X Premium+"}""",
        ).windows.single()
        assertEquals(Instant.parse("2026-07-14T15:40:06.727001Z"), window.resetsAt)
        assertEquals(604_800.0, window.windowSeconds!!, 0.001)
    }

    @Test fun dateParserIgnoresTheNumberOfFractionDigits() {
        val base = Instant.parse("2026-07-14T15:40:06Z")
        assertEquals(base, GrokBillingMapper.parseDate("2026-07-14T15:40:06Z"))
        assertEquals(base.plusMillis(500), GrokBillingMapper.parseDate("2026-07-14T15:40:06.5+00:00"))
        assertEquals(base.plusNanos(727_001_000), GrokBillingMapper.parseDate("2026-07-14T15:40:06.727001+00:00"))
        assertNull(GrokBillingMapper.parseDate("not-a-date"))
    }

    /** Without grounds for the usage there is no window (reported as no-windows), never a made-up 0%. */
    @Test fun ungroundedResponsesHaveNoWindow() {
        assertTrue(usage("""{"config":{}}""").windows.isEmpty())
        assertTrue(usage("{}").windows.isEmpty())
    }

    @Test fun outOfRangeOrNonNumericPercentsAreErrors() {
        for (bad in listOf("150", "-1", "\"4.0\"", "true")) {
            assertThrows(bad, UsageException.Decode::class.java) {
                usage("""{"config":{"creditUsagePercent":$bad}}""")
            }
        }
    }

    /** No percent but a product reporting usage: the week is not empty, so it is not read as 0%. */
    @Test fun productUsageWithoutAPercentIsAnError() {
        assertThrows(UsageException.Decode::class.java) {
            usage("""{"config":{$weeklyPeriod,"productUsage":[{"product":"GrokBuild","usagePercent":12.5}]}}""")
        }
        val zero = usage("""{"config":{$weeklyPeriod,"productUsage":[{"product":"GrokBuild","usagePercent":0}]}}""")
        assertEquals(0.0, zero.windows.single().usedPercent, 0.0)
    }

    /** 0% only from a self-contained weekly period — never non-weekly, partial, or borrowed flat values. */
    @Test fun nonWeeklyOrIncompletePeriodsAreNotReadAsZero() {
        val cases = listOf(
            """{"config":{"currentPeriod":{}}}""",
            """{"config":{"currentPeriod":{"type":"USAGE_PERIOD_TYPE_WEEKLY"}}}""",
            """{"config":{"currentPeriod":{"type":"USAGE_PERIOD_TYPE_MONTHLY","start":"2026-07-01T00:00:00+00:00","end":"2026-08-01T00:00:00+00:00"}}}""",
            """{"config":{"currentPeriod":{"type":"USAGE_PERIOD_TYPE_WEEKLY"},"billingPeriodStart":"2026-07-15T00:00:00+00:00","billingPeriodEnd":"2026-07-22T00:00:00+00:00"}}""",
            """{"config":{"currentPeriod":{"type":"USAGE_PERIOD_TYPE_BIWEEKLY","start":"2026-07-15T00:00:00+00:00","end":"2026-07-22T00:00:00+00:00"}}}""",
            """{"config":{"currentPeriod":{"type":"USAGE_PERIOD_TYPE_NOT_WEEKLY","start":"2026-07-15T00:00:00+00:00","end":"2026-07-22T00:00:00+00:00"}}}""",
        )
        cases.forEach { json -> assertTrue(json, usage(json).windows.isEmpty()) }
    }

    @Test fun resetFallsBackToTheFlatBillingPeriod() {
        val window = usage(
            """{"config":{"creditUsagePercent":30,"billingPeriodStart":"2026-07-15T00:00:00+00:00","billingPeriodEnd":"2026-07-22T00:00:00+00:00"}}""",
        ).windows.single()
        assertEquals(30.0, window.usedPercent, 0.0)
        assertEquals(Instant.parse("2026-07-22T00:00:00Z"), window.resetsAt)
        assertEquals(604_800.0, window.windowSeconds!!, 0.0)
    }

    @Test fun aNullPercentCountsAsOmitted() {
        assertEquals(0.0, usage("""{"config":{$weeklyPeriod,"creditUsagePercent":null}}""").windows.single().usedPercent, 0.0)
    }

    /** The plan label is optional; an unexpected shape never breaks the usage. */
    @Test fun planLabelIsOptional() {
        val config = """"config":{"creditUsagePercent":10,"billingPeriodEnd":"2026-07-22T00:00:00+00:00"}"""
        assertEquals("SuperGrok", usage("""{$config,"subscriptionTiers":"  SuperGrok  "}""").plan)
        assertNull(usage("""{$config,"subscriptionTiers":"   "}""").plan)
        assertNull(usage("{$config}").plan)
        assertNull(usage("""{$config,"subscriptionTiers":42}""").plan)
        assertEquals("SuperGrok, X Premium+", usage("""{$config,"subscriptionTiers":["SuperGrok","X Premium+"]}""").plan)
        assertEquals(1, usage("""{$config,"subscriptionTiers":42}""").windows.size)
    }

    /** Only 401 is an auth failure; 403 is a refusal that must not burn a rotating refresh token. */
    @Test fun onlyUnauthorizedTriggersARefresh() {
        val transport = RecordingTransport(jsonResponse("""{"config":{"creditUsagePercent":5}}"""))
        val result = runSuspend { GrokUsageClient(transport).fetchUsage(OAuthTokens("at")) }
        assertEquals(5.0, result.windows.single().usedPercent, 0.0)
        assertEquals("Bearer at", transport.lastRequest.header("Authorization"))
        assertEquals(GrokUsageClient.CREDITS_URL, transport.lastRequest.url.toString())

        transport.response = jsonResponse("{}", statusCode = 401)
        assertThrows(UsageException.Unauthorized::class.java) {
            runSuspend { GrokUsageClient(transport).fetchUsage(OAuthTokens("at")) }
        }
        transport.response = jsonResponse("{}", statusCode = 403)
        assertThrows(UsageException.Http::class.java) {
            runSuspend { GrokUsageClient(transport).fetchUsage(OAuthTokens("at")) }
        }
    }
}
