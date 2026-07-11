package com.ScienceFiction.TokenWatchAndroid.network.providers.session

import com.ScienceFiction.TokenWatchAndroid.auth.OAuthTokens
import com.ScienceFiction.TokenWatchAndroid.domain.UsageStyle
import com.ScienceFiction.TokenWatchAndroid.domain.WindowKind
import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkResponse
import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkTransport
import kotlinx.coroutines.runBlocking
import okio.Buffer
import okhttp3.Request
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class WindsurfUsageClientTest {
    @Test
    fun packedHeadersConnectRequestAndCamelCaseResponseMatchIosContract() = runBlocking {
        val transport = RecordingTransport(
            NetworkResponse(
                statusCode = 200,
                headers = emptyMap(),
                body = """{"dailyQuotaRemainingPercent":70,"weeklyQuotaRemainingPercent":40}"""
                    .toByteArray(),
            ),
        )
        val client = WindsurfUsageClient(transport, endpoint = FIXTURE_ENDPOINT)
        val packedHeaders =
            """{"x-auth-token":"AUTH","x-devin-account-id":"acc1","x-devin-primary-org-id":"org1"}"""

        val windows = client.fetch(OAuthTokens.session(packedHeaders))

        val request = transport.request
        assertEquals(FIXTURE_ENDPOINT, request.url.toString())
        assertEquals("POST", request.method)
        assertEquals("application/json", request.header("Content-Type"))
        assertEquals("application/json", request.header("Accept"))
        assertEquals("1", request.header("Connect-Protocol-Version"))
        assertEquals("TokenWatch/1.0", request.header("User-Agent"))
        assertEquals("AUTH", request.header("x-auth-token"))
        assertEquals("acc1", request.header("x-devin-account-id"))
        assertEquals("org1", request.header("x-devin-primary-org-id"))
        assertArrayEquals("{}".toByteArray(), request.bodyBytes())

        assertEquals(2, windows.size)
        val daily = windows.single { it.label == "Daily quota" }
        val weekly = windows.single { it.label == "Weekly quota" }
        assertEquals(30.0, daily.usedPercent, 0.0)
        assertEquals(WindowKind.SESSION, daily.kind)
        assertEquals(UsageStyle.GAUGE, daily.style)
        assertEquals(60.0, weekly.usedPercent, 0.0)
        assertEquals(WindowKind.WEEKLY, weekly.kind)
        assertEquals(UsageStyle.GAUGE, weekly.style)
    }

    @Test
    fun snakeCaseAndNumericStringResponseAreAccepted() = runBlocking {
        val transport = RecordingTransport(
            NetworkResponse(
                statusCode = 200,
                headers = emptyMap(),
                body = """{"daily_quota_remaining_percent":"90"}""".toByteArray(),
            ),
        )

        val windows = WindsurfUsageClient(transport, endpoint = FIXTURE_ENDPOINT)
            .fetch(OAuthTokens.session("{}"))

        assertEquals(1, windows.size)
        assertEquals("Daily quota", windows.single().label)
        assertEquals(10.0, windows.single().usedPercent, 0.0)
    }

    private class RecordingTransport(private val response: NetworkResponse) : NetworkTransport {
        lateinit var request: Request
        override suspend fun execute(request: Request): NetworkResponse {
            this.request = request
            return response
        }
    }

    private fun Request.bodyBytes(): ByteArray {
        val buffer = Buffer()
        body!!.writeTo(buffer)
        return buffer.readByteArray()
    }

    private companion object {
        const val FIXTURE_ENDPOINT = "https://fixture.example/windsurf-usage"
    }
}
