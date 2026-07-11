package com.ScienceFiction.TokenWatchAndroid.network.providers.subscription

import com.ScienceFiction.TokenWatchAndroid.auth.OAuthTokens
import com.ScienceFiction.TokenWatchAndroid.domain.WindowKind
import com.ScienceFiction.TokenWatchAndroid.network.core.UsageException
import java.time.Instant
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ClaudeUsageClientTest {
    @Test
    fun sendsCommittedIosHeadersAndMapsDynamicLimits() {
        val transport = RecordingTransport(
            jsonResponse(
                """
                {
                  "limits": [
                    {"kind":"session","group":"session","percent":58,"resets_at":"2026-07-09T14:49:59.739913Z","scope":null},
                    {"kind":"weekly_all","group":"weekly","percent":136,"resets_at":"2026-07-14T22:59:59Z","scope":null},
                    {"kind":"weekly_scoped","group":"weekly","percent":0,"resets_at":null,"scope":{"model":{"display_name":"Fable"}}}
                  ]
                }
                """.trimIndent(),
            ),
        )
        val endpoint = "https://fixture.example/claude-usage".toHttpUrl()
        val windows = runSuspend {
            ClaudeUsageClient(transport, endpoint).fetch(OAuthTokens(accessToken = "claude-token"))
        }

        val request = transport.lastRequest
        assertEquals(endpoint, request.url)
        assertEquals("GET", request.method)
        assertEquals("Bearer claude-token", request.header("Authorization"))
        assertEquals("application/json", request.header("Accept"))
        assertEquals("application/json", request.header("Content-Type"))
        assertEquals("oauth-2025-04-20", request.header("anthropic-beta"))
        assertEquals("claude-code/2.1.0", request.header("User-Agent"))

        assertEquals(listOf("Current session", "Current week (all models)", "Current week (Fable)"), windows.map { it.label })
        assertEquals(58.0, windows[0].usedPercent, 0.0)
        assertEquals(100.0, windows[1].usedPercent, 0.0)
        assertEquals(WindowKind.SESSION, windows[0].kind)
        assertEquals(18_000.0, windows[0].windowSeconds!!, 0.0)
        assertEquals(WindowKind.WEEKLY, windows[1].kind)
        assertEquals(604_800.0, windows[1].windowSeconds!!, 0.0)
        assertEquals(Instant.parse("2026-07-09T14:49:59.739913Z"), windows[0].resetsAt)
    }

    @Test
    fun fallsBackToLegacyDynamicKeysAndNumericStrings() {
        val transport = RecordingTransport(
            jsonResponse(
                """
                {
                  "five_hour":{"utilization":"58.5","resets_at":"2026-07-09T14:49:59Z"},
                  "seven_day":{"resets_at":"2026-07-14T22:59:59Z"},
                  "seven_day_fable":{"utilization":-5},
                  "seven_day_opus":{"utilization":150},
                  "seven_day_sonnet":{"utilization":"not-a-number"},
                  "extra_usage":{"is_enabled":false,"utilization":77},
                  "limits":[]
                }
                """.trimIndent(),
            ),
        )
        val windows = runSuspend {
            ClaudeUsageClient(transport).fetch(OAuthTokens(accessToken = "token"))
        }

        assertEquals(
            listOf(
                "Current session",
                "Current week (all models)",
                "Current week (Fable)",
                "Current week (Opus)",
            ),
            windows.map { it.label },
        )
        assertEquals(listOf(58.5, 0.0, 0.0, 100.0), windows.map { it.usedPercent })
    }

    @Test
    fun malformedJsonIsDecodeErrorAndClaude403RemainsHttpError() {
        val malformed = RecordingTransport(jsonResponse("not-json"))
        assertThrows(UsageException.Decode::class.java) {
            runSuspend { ClaudeUsageClient(malformed).fetch(OAuthTokens(accessToken = "token")) }
        }

        val forbidden = RecordingTransport(jsonResponse("denied", statusCode = 403))
        val error = assertThrows(UsageException.Http::class.java) {
            runSuspend { ClaudeUsageClient(forbidden).fetch(OAuthTokens(accessToken = "token")) }
        }
        assertEquals(403, error.statusCode)
    }
}
