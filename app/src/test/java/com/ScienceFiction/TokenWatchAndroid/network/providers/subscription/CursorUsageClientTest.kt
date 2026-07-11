package com.ScienceFiction.TokenWatchAndroid.network.providers.subscription

import com.ScienceFiction.TokenWatchAndroid.auth.OAuthTokens
import com.ScienceFiction.TokenWatchAndroid.network.core.UsageException
import java.time.Instant
import java.time.ZoneOffset
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class CursorUsageClientTest {
    @Test
    fun sendsCookieAndEncodedUserQueryAndMapsGpt4Bucket() {
        val transport = RecordingTransport(
            jsonResponse(
                """
                {
                  "startOfMonth":"2026-07-01T00:00:00.123Z",
                  "gpt-3.5-turbo":{"numRequests":1,"maxRequestUsage":10},
                  "gpt-4":{"numRequests":125,"maxRequestUsage":500},
                  "metadata":{"name":"ignored"}
                }
                """.trimIndent(),
            ),
        )
        val endpoint = "https://fixture.example/cursor-usage?old=value".toHttpUrl()
        val windows = runSuspend {
            CursorUsageClient(transport, endpoint, ZoneOffset.UTC).fetch(
                OAuthTokens(
                    accessToken = "user%3A%3Ajwt-token",
                    accountId = "user +/42",
                ),
            )
        }

        val request = transport.lastRequest
        assertEquals("https", request.url.scheme)
        assertEquals("fixture.example", request.url.host)
        assertEquals("/cursor-usage", request.url.encodedPath)
        assertEquals("value", request.url.queryParameter("old"))
        assertEquals("user +/42", request.url.queryParameter("user"))
        assertEquals(
            "WorkosCursorSessionToken=user%3A%3Ajwt-token",
            request.header("Cookie"),
        )
        assertEquals("application/json", request.header("Accept"))
        assertEquals("TokenWatch/1.0", request.header("User-Agent"))

        val window = windows.single()
        assertEquals("Fast requests", window.label)
        assertEquals(25.0, window.usedPercent, 0.0)
        assertEquals(Instant.parse("2026-08-01T00:00:00.123Z"), window.resetsAt)
        assertNull(window.windowSeconds)
    }

    @Test
    fun defensivelyIgnoresMalformedBucketsAndUsesFirstLimitedFallback() {
        val transport = RecordingTransport(
            jsonResponse(
                """
                {
                  "startOfMonth":"invalid",
                  "broken":{"numRequests":"five","maxRequestUsage":10},
                  "metadata":{"arbitrary":true},
                  "first-limited":{"numRequests":5,"maxRequestUsage":20},
                  "second-limited":{"numRequests":9,"maxRequestUsage":10}
                }
                """.trimIndent(),
            ),
        )
        val window = runSuspend {
            CursorUsageClient(transport, zoneId = ZoneOffset.UTC).fetch(
                OAuthTokens(accessToken = "cookie", accountId = "user-1"),
            )
        }.single()

        assertEquals(25.0, window.usedPercent, 0.0)
        assertNull(window.resetsAt)
    }

    @Test
    fun preferredGpt4WithoutPositiveLimitDoesNotFallBack() {
        val transport = RecordingTransport(
            jsonResponse(
                """{"gpt-4":{"numRequests":1,"maxRequestUsage":0},"other":{"numRequests":1,"maxRequestUsage":10}}""",
            ),
        )
        val windows = runSuspend {
            CursorUsageClient(transport).fetch(
                OAuthTokens(accessToken = "cookie", accountId = "user-1"),
            )
        }
        assertEquals(emptyList<Any>(), windows)
    }

    @Test
    fun missingAccountAndForbiddenResponseAreUnauthorized() {
        val missingAccount = RecordingTransport()
        assertThrows(UsageException.Unauthorized::class.java) {
            runSuspend {
                CursorUsageClient(missingAccount).fetch(OAuthTokens(accessToken = "cookie"))
            }
        }
        assertEquals(0, missingAccount.requests.size)

        val forbidden = RecordingTransport(jsonResponse("denied", statusCode = 403))
        assertThrows(UsageException.Unauthorized::class.java) {
            runSuspend {
                CursorUsageClient(forbidden).fetch(
                    OAuthTokens(accessToken = "cookie", accountId = "user-1"),
                )
            }
        }
    }

    @Test
    fun malformedRootIsDecodeError() {
        val transport = RecordingTransport(jsonResponse("[]"))
        assertThrows(UsageException.Decode::class.java) {
            runSuspend {
                CursorUsageClient(transport).fetch(
                    OAuthTokens(accessToken = "cookie", accountId = "user-1"),
                )
            }
        }
    }
}
