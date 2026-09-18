package com.ScienceFiction.TokenWatchAndroid.network.providers.subscription

import com.ScienceFiction.TokenWatchAndroid.auth.OAuthTokens
import com.ScienceFiction.TokenWatchAndroid.domain.UsageStyle
import com.ScienceFiction.TokenWatchAndroid.domain.WindowKind
import com.ScienceFiction.TokenWatchAndroid.network.core.ProviderUsage
import com.ScienceFiction.TokenWatchAndroid.network.core.UsageException
import java.time.Instant
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** Cursor usage-summary mapping, ported from iOS CursorUsageTests (measured samples, CodexBar fixtures). */
class CursorUsageClientTest {
    private fun usage(json: String): ProviderUsage = CursorUsageMapper.usage(json.toByteArray())

    /** Measured Pro sample: included usage exhausted — Other models 100%, Cursor models 0%. */
    @Test fun measuredProResponseMapsToTwoPools() {
        val result = usage(
            """{"billingCycleStart":"2026-04-02T14:11:55.000Z","billingCycleEnd":"2026-05-02T14:11:55.000Z",
             "membershipType":"pro","limitType":"user","isUnlimited":false,
             "individualUsage":{"plan":{"enabled":true,"used":2000,"limit":2000,"remaining":0,
               "autoPercentUsed":0,"apiPercentUsed":100,"totalPercentUsed":100,
               "breakdown":{"included":2000,"bonus":0,"total":2000}},
              "onDemand":{"enabled":true,"used":2309,"limit":10000,"remaining":7691}},
             "teamUsage":{"onDemand":{"enabled":true,"used":5000,"limit":50000,"remaining":45000}}}""",
        )
        assertEquals(listOf("Cursor models", "Other models"), result.windows.map { it.label })
        assertEquals(listOf(0.0, 100.0), result.windows.map { it.usedPercent })
        result.windows.forEach {
            assertEquals(WindowKind.WEEKLY, it.kind)
            assertEquals(UsageStyle.GAUGE, it.style)
        }
        assertEquals(Instant.parse("2026-05-02T14:11:55Z"), result.windows.first().resetsAt)
        assertEquals(30.0 * 24 * 3600, result.windows.first().windowSeconds!!, 0.0)
        assertEquals("Pro", result.plan)
    }

    /** Pool percents are already percent; totalPercentUsed (30) disagreed with the dashboard and is ignored. */
    @Test fun percentsAreUsedAsIsAndTotalIsIgnored() {
        val result = usage(
            """{"billingCycleStart":"2026-09-01T00:00:00Z","billingCycleEnd":"2026-10-01T00:00:00Z",
             "membershipType":"pro","individualUsage":{"plan":{"enabled":true,"used":1500,
             "limit":5000,"remaining":3500,"totalPercentUsed":30,"autoPercentUsed":10,"apiPercentUsed":20}}}""",
        )
        assertEquals(listOf(10.0, 20.0), result.windows.map { it.usedPercent })
        assertEquals(Instant.parse("2026-10-01T00:00:00Z"), result.windows.first().resetsAt)
        assertFalse(result.windows.any { it.usedPercent == 30.0 })
    }

    @Test fun onlyPoolsWithAPercentBecomeWindows() {
        val result = usage(
            """{"billingCycleEnd":"2026-10-01T00:00:00Z","membershipType":"express","individualUsage":{"plan":{"autoPercentUsed":42.5}}}""",
        )
        assertEquals(listOf("Cursor models"), result.windows.map { it.label })
        assertEquals(42.5, result.windows.single().usedPercent, 0.0)
        // Without a start the window length is unknown.
        assertNull(result.windows.single().windowSeconds)
        assertEquals("Start", result.plan)
    }

    @Test fun olderResponsesFallBackToTheIncludedAmount() {
        val result = usage("""{"billingCycleEnd":"2026-10-01T00:00:00Z","individualUsage":{"plan":{"used":500,"limit":2000}}}""")
        assertEquals(listOf("Included usage"), result.windows.map { it.label })
        assertEquals(25.0, result.windows.single().usedPercent, 0.0)
    }

    /** A changed 200 is an error, never a 0% gauge. */
    @Test fun missingCycleOrPlanUsageIsAnError() {
        listOf(
            "{}",
            """{"individualUsage":{"plan":{"autoPercentUsed":10}}}""",
            """{"billingCycleEnd":"2026-10-01T00:00:00Z","individualUsage":{}}""",
            """{"billingCycleEnd":"not-a-date","individualUsage":{"plan":{"autoPercentUsed":10}}}""",
        ).forEach { json ->
            assertThrows(json, UsageException.Decode::class.java) { usage(json) }
        }
    }

    @Test fun outOfRangePercentsAreClamped() {
        val result = usage(
            """{"billingCycleEnd":"2026-10-01T00:00:00Z","individualUsage":{"plan":{"autoPercentUsed":130,"apiPercentUsed":-5}}}""",
        )
        assertEquals(listOf(100.0, 0.0), result.windows.map { it.usedPercent })
    }

    @Test fun planNamesAreMadeReadable() {
        assertEquals("Pro Plus", CursorUsageMapper.planLabel("pro_plus"))
        assertEquals("Ultra", CursorUsageMapper.planLabel("ultra"))
        assertEquals("Hobby", CursorUsageMapper.planLabel("free"))
        assertEquals("Start", CursorUsageMapper.planLabel("express"))
        assertEquals("New Tier X", CursorUsageMapper.planLabel("new_tier_x"))
        assertNull(CursorUsageMapper.planLabel(null))
        assertNull(CursorUsageMapper.planLabel("  "))
    }

    /** The cookie pairs the user id with the token; no session or a redirect means signed out. */
    @Test fun requestsCarryTheSessionCookieAndTreatRedirectsAsSignedOut() {
        val token = "e30.${Base64.getUrlEncoder().withoutPadding().encodeToString("""{"sub":"auth0|user_1"}""".toByteArray())}.sig"
        val transport = RecordingTransport(
            jsonResponse("""{"billingCycleEnd":"2026-10-01T00:00:00Z","individualUsage":{"plan":{"autoPercentUsed":1}}}"""),
        )
        runSuspend { CursorUsageClient(transport).fetchUsage(OAuthTokens(token)) }
        assertEquals("WorkosCursorSessionToken=user_1%3A%3A$token", transport.lastRequest.header("Cookie"))

        for (status in listOf(302, 401, 403)) {
            transport.response = jsonResponse("{}", statusCode = status)
            assertThrows(UsageException.Unauthorized::class.java) {
                runSuspend { CursorUsageClient(transport).fetchUsage(OAuthTokens(token)) }
            }
        }
        assertThrows(UsageException.Unauthorized::class.java) {
            runSuspend { CursorUsageClient(transport).fetchUsage(OAuthTokens("not-a-jwt")) }
        }
    }
}
