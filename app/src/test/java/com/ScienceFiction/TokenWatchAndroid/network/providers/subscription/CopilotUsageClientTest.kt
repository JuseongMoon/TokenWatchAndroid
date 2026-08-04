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

        assertEquals(
            listOf("Premium requests", "Completions", "Alpha Extra", "Zeta Pool"),
            windows.map { it.label },
        )
        assertEquals(listOf(30.0, 75.0, 0.0, 90.0), windows.map { it.usedPercent })
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
