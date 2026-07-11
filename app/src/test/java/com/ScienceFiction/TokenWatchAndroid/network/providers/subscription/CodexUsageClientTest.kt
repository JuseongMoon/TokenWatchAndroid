package com.ScienceFiction.TokenWatchAndroid.network.providers.subscription

import com.ScienceFiction.TokenWatchAndroid.auth.OAuthTokens
import com.ScienceFiction.TokenWatchAndroid.domain.WindowKind
import com.ScienceFiction.TokenWatchAndroid.network.core.UsageException
import java.time.Instant
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class CodexUsageClientTest {
    @Test
    fun sendsAccountHeaderAndMapsSwappedAndAdditionalWindows() {
        val transport = RecordingTransport(
            jsonResponse(
                """
                {
                  "rate_limit": {
                    "primary_window":{"used_percent":70,"reset_at":1700000000,"limit_window_seconds":604800},
                    "secondary_window":{"used_percent":20,"reset_at":1700000100,"limit_window_seconds":18000}
                  },
                  "additional_rate_limits": [
                    {"limit_name":"Burst","rate_limit":{"primary_window":{"used_percent":130,"reset_at":0,"limit_window_seconds":3600}}},
                    {"limit_name":"Current week","rate_limit":{"primary_window":{"used_percent":42,"limit_window_seconds":604800}}},
                    {"metered_feature":"Model pool","rate_limit":{"secondary_window":{"used_percent":33}}},
                    {"rate_limit":{"primary_window":{"used_percent":9,"limit_window_seconds":18000}}}
                  ]
                }
                """.trimIndent(),
            ),
        )
        val endpoint = "https://fixture.example/wham".toHttpUrl()
        val client = CodexUsageClient(
            transport = transport,
            endpoint = endpoint,
            additionalLimitLabel = { "추가 한도" },
        )
        val windows = runSuspend {
            client.fetch(OAuthTokens(accessToken = "codex-token", accountId = "account-42"))
        }

        val request = transport.lastRequest
        assertEquals(endpoint, request.url)
        assertEquals("Bearer codex-token", request.header("Authorization"))
        assertEquals("account-42", request.header("ChatGPT-Account-Id"))
        assertEquals("application/json", request.header("Accept"))
        assertEquals("TokenWatch/1.0", request.header("User-Agent"))

        assertEquals(
            listOf("Current session", "Current week", "Burst", "Model pool", "추가 한도"),
            windows.map { it.label },
        )
        assertEquals(listOf(20.0, 70.0, 100.0, 33.0, 9.0), windows.map { it.usedPercent })
        assertEquals(WindowKind.SESSION, windows[0].kind)
        assertEquals(18_000.0, windows[0].windowSeconds!!, 0.0)
        assertEquals(WindowKind.WEEKLY, windows[1].kind)
        assertEquals(604_800.0, windows[1].windowSeconds!!, 0.0)
        assertEquals(Instant.ofEpochSecond(1_700_000_100L), windows[0].resetsAt)
        assertNull(windows[2].resetsAt)
        assertEquals(3_600.0, windows[2].windowSeconds!!, 0.0)
        assertEquals(604_800.0, windows[3].windowSeconds!!, 0.0)
    }

    @Test
    fun omitsEmptyAccountHeaderAndTreats403AsUnauthorized() {
        val success = RecordingTransport(jsonResponse("{}"))
        runSuspend {
            CodexUsageClient(success).fetch(OAuthTokens(accessToken = "token", accountId = ""))
        }
        assertNull(success.lastRequest.header("ChatGPT-Account-Id"))

        val forbidden = RecordingTransport(jsonResponse("denied", statusCode = 403))
        assertThrows(UsageException.Unauthorized::class.java) {
            runSuspend { CodexUsageClient(forbidden).fetch(OAuthTokens(accessToken = "token")) }
        }
    }

    @Test
    fun malformedRequiredWindowFieldIsDecodeError() {
        val transport = RecordingTransport(
            jsonResponse("""{"rate_limit":{"primary_window":{"reset_at":1700000000}}}"""),
        )
        assertThrows(UsageException.Decode::class.java) {
            runSuspend { CodexUsageClient(transport).fetch(OAuthTokens(accessToken = "token")) }
        }
    }
}
