package com.ScienceFiction.TokenWatchAndroid.network.providers.subscription

import com.ScienceFiction.TokenWatchAndroid.auth.OAuthTokens
import com.ScienceFiction.TokenWatchAndroid.network.core.UsageException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class CopilotUsageClientTest {
    @Test
    fun sendsRequiredEditorHeadersAndMapsQuotaSnapshots() {
        val transport = RecordingTransport(
            jsonResponse(
                """
                {
                  "copilot_plan":"individual_pro",
                  "quota_reset_date":"2026-08-01",
                  "quota_snapshots": {
                    "zeta_pool":{"percent_remaining":10},
                    "premium_interactions":{"percent_remaining":70},
                    "chat":{"unlimited":true},
                    "completions":{"entitlement":100,"remaining":25},
                    "alpha_extra":{}
                  }
                }
                """.trimIndent(),
            ),
        )
        val endpoint = "https://fixture.example/copilot-user".toHttpUrl()
        val windows = runSuspend {
            CopilotUsageClient(transport, endpoint).fetch(OAuthTokens(accessToken = "github-token"))
        }

        val request = transport.lastRequest
        assertEquals(endpoint, request.url)
        assertEquals("token github-token", request.header("Authorization"))
        assertEquals("application/json", request.header("Accept"))
        assertEquals("vscode/1.96.2", request.header("Editor-Version"))
        assertEquals("copilot-chat/0.26.7", request.header("Editor-Plugin-Version"))
        assertEquals("GitHubCopilotChat/0.26.7", request.header("User-Agent"))
        assertEquals("2025-04-01", request.header("X-Github-Api-Version"))

        // `alpha_extra` has nothing measurable, so it is left out instead of shown as 0% used.
        assertEquals(
            listOf("Premium requests", "Completions", "Zeta Pool"),
            windows.map { it.label },
        )
        assertEquals(listOf(30.0, 75.0, 90.0), windows.map { it.usedPercent })
        windows.forEach { window ->
            assertEquals(LocalDate.parse("2026-08-01").atStartOfDay(ZoneId.systemDefault()).toInstant(), window.resetsAt)
            assertNull(window.windowSeconds)
        }
    }

    @Test
    fun missingOrInvalidResetDateLeavesResetUnknown() {
        val transport = RecordingTransport(
            jsonResponse(
                """{"quota_reset_date":"not-a-date","quota_snapshots":{"premium_interactions":{"percent_remaining":120}}}""",
            ),
        )
        val windows = runSuspend {
            CopilotUsageClient(transport).fetch(OAuthTokens(accessToken = "token"))
        }
        assertEquals(0.0, windows.single().usedPercent, 0.0)
        assertNull(windows.single().resetsAt)
    }

    private fun windowsFor(json: String) = runSuspend {
        CopilotUsageClient(RecordingTransport(jsonResponse(json))).fetch(OAuthTokens(accessToken = "token"))
    }

    @Test
    fun copilotFreeLimitedAndMonthlyQuotasBecomeChatAndCompletions() {
        val windows = windowsFor(
            """
            {
              "copilot_plan": "free",
              "limited_user_reset_date": "2026-08-01",
              "monthly_quotas": {"chat": "500", "completions": 300},
              "limited_user_quotas": {"chat": 125, "completions": "75"}
            }
            """.trimIndent(),
        )

        assertEquals(listOf("Chat", "Completions"), windows.map { it.label })
        assertEquals(listOf(75.0, 75.0), windows.map { it.usedPercent })
        assertEquals(
            LocalDate.parse("2026-08-01").atStartOfDay(ZoneId.systemDefault()).toInstant(),
            windows.first().resetsAt,
        )
    }

    @Test
    fun aFreeLaneWithoutItsRemainingAmountIsNotAssumedFull() {
        assertEquals(
            emptyList<String>(),
            windowsFor("""{"copilot_plan":"free","monthly_quotas":{"chat":500,"completions":300}}""").map { it.label },
        )
        val partial = windowsFor(
            """{"monthly_quotas":{"chat":500,"completions":300},"limited_user_quotas":{"completions":60}}""",
        )
        assertEquals(listOf("Completions"), partial.map { it.label })
        assertEquals(80.0, partial.single().usedPercent, 1e-9)
        // A zero allowance cannot be turned into a percentage.
        assertEquals(
            emptyList<String>(),
            windowsFor("""{"monthly_quotas":{"chat":0},"limited_user_quotas":{"chat":0}}""").map { it.label },
        )
    }

    @Test
    fun directSnapshotsWinAndFreeQuotasFillOnlyMissingLanes() {
        val windows = windowsFor(
            """
            {
              "quota_snapshots": {
                "chat": {"entitlement": 200, "remaining": 75, "percent_remaining": 37.5},
                "completions": {"unlimited": true}
              },
              "monthly_quotas": {"chat": 500, "completions": 300},
              "limited_user_quotas": {"chat": 125, "completions": 60}
            }
            """.trimIndent(),
        )

        // Chat comes from quota_snapshots; unlimited completions stay hidden.
        assertEquals(listOf("Chat"), windows.map { it.label })
        assertEquals(62.5, windows.single().usedPercent, 1e-9)
    }

    @Test
    fun unlimitedSentinelAndZeroEntitlementAreHidden() {
        val windows = windowsFor(
            """
            {"quota_snapshots": {
              "premium_interactions": {"entitlement": -1, "remaining": -1},
              "chat": {"entitlement": 0, "remaining": 0},
              "completions": {"entitlement": 100, "remaining": 40}
            }}
            """.trimIndent(),
        )
        assertEquals(listOf("Completions"), windows.map { it.label })
        assertEquals(60.0, windows.single().usedPercent, 1e-9)
    }

    @Test
    fun a200WithoutAnyQuotaYieldsNoWindows() {
        assertEquals(emptyList<String>(), windowsFor("""{"copilot_plan":"individual"}""").map { it.label })
        assertEquals(emptyList<String>(), windowsFor("""{"quota_snapshots":{}}""").map { it.label })
    }

    @Test
    fun forbiddenIsUnauthorizedAndMalformedSnapshotIsDecodeError() {
        val forbidden = RecordingTransport(jsonResponse("denied", statusCode = 403))
        assertThrows(UsageException.Unauthorized::class.java) {
            runSuspend { CopilotUsageClient(forbidden).fetch(OAuthTokens(accessToken = "token")) }
        }

        val malformed = RecordingTransport(
            jsonResponse("""{"quota_snapshots":{"premium_interactions":{"percent_remaining":"70"}}}"""),
        )
        assertThrows(UsageException.Decode::class.java) {
            runSuspend { CopilotUsageClient(malformed).fetch(OAuthTokens(accessToken = "token")) }
        }
    }
}
