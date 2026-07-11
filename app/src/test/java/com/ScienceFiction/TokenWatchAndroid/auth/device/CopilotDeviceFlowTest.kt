package com.ScienceFiction.TokenWatchAndroid.auth.device

import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkResponse
import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkTransport
import java.time.Instant
import java.util.ArrayDeque
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CopilotDeviceFlowTest {
    @Test
    fun requestsCodeAndPollsUntilAuthorized() = runBlocking {
        var clock = Instant.parse("2026-07-11T00:00:00Z")
        val transport = QueueTransport(
            """{"device_code":"device","user_code":"ABCD-EFGH","verification_uri":"https://github.com/login/device","interval":2,"expires_in":60}""",
            """{"error":"authorization_pending"}""",
            """{"access_token":"github-token"}""",
        )
        val sleeps = mutableListOf<Int>()
        val flow = CopilotDeviceFlow(
            transport = transport,
            now = { clock },
            sleeper = DeviceFlowSleeper { seconds ->
                sleeps += seconds
                clock = clock.plusSeconds(seconds.toLong())
            },
        )

        val device = flow.requestDeviceCode()
        assertEquals("ABCD-EFGH", device.userCode)
        val tokens = flow.pollForToken(device)
        assertEquals("github-token", tokens.accessToken)
        assertEquals(listOf(2, 2), sleeps)
        assertTrue(transport.requests.first().url.toString().contains("/login/device/code"))
    }

    @Test
    fun slowDownAddsFiveSeconds() = runBlocking {
        var clock = Instant.parse("2026-07-11T00:00:00Z")
        val transport = QueueTransport(
            """{"error":"slow_down"}""",
            """{"access_token":"token"}""",
        )
        val sleeps = mutableListOf<Int>()
        val flow = CopilotDeviceFlow(
            transport = transport,
            now = { clock },
            sleeper = DeviceFlowSleeper { seconds ->
                sleeps += seconds
                clock = clock.plusSeconds(seconds.toLong())
            },
        )
        flow.pollForToken(DeviceCode("d", "u", "https://example.com", 3, 30))
        assertEquals(listOf(3, 8), sleeps)
    }

    @Test(expected = DeviceFlowException.Denied::class)
    fun deniedResponseThrows(): Unit = runBlocking {
        var clock = Instant.EPOCH
        CopilotDeviceFlow(
            transport = QueueTransport("""{"error":"access_denied"}"""),
            now = { clock },
            sleeper = DeviceFlowSleeper { seconds -> clock = clock.plusSeconds(seconds.toLong()) },
        ).pollForToken(DeviceCode("d", "u", "https://example.com", 1, 30))
        Unit
    }

    private class QueueTransport(vararg bodies: String) : NetworkTransport {
        private val responses = ArrayDeque(bodies.toList())
        val requests = mutableListOf<Request>()

        override suspend fun execute(request: Request): NetworkResponse {
            requests += request
            return NetworkResponse(200, emptyMap(), responses.removeFirst().toByteArray())
        }
    }
}
