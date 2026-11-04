package com.ScienceFiction.TokenWatchAndroid.auth.device

import com.ScienceFiction.TokenWatchAndroid.analytics.LoginFailureCode
import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkResponse
import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkTransport
import java.time.Instant
import java.util.ArrayDeque
import kotlinx.coroutines.async
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
            """{"id":1}""",
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
            """{"id":1}""",
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

    @Test
    fun theTokenCarriesTheStableGitHubUserId() = runBlocking {
        var clock = Instant.EPOCH
        val requests = mutableListOf<Request>()
        val transport = NetworkTransport { request ->
            requests += request
            val body = when (request.url.encodedPath) {
                "/login/oauth/access_token" -> """{"access_token":"github-token"}"""
                "/user" -> """{"login":"renamed-later","id":583231,"email":null}"""
                else -> error("unexpected ${request.url}")
            }
            NetworkResponse(200, emptyMap(), body.toByteArray())
        }
        val tokens = CopilotDeviceFlow(
            transport = transport,
            now = { clock },
            sleeper = DeviceFlowSleeper { seconds -> clock = clock.plusSeconds(seconds.toLong()) },
        ).pollForToken(DeviceCode("d", "u", "https://example.com", 1, 30))

        assertEquals("github-token", tokens.accessToken)
        assertEquals("583231", tokens.accountId)
        // The display login and email are not used as the identity.
        assertEquals(null, tokens.accountEmail)
        val userRequest = requests.last()
        assertEquals("https://api.github.com/user", userRequest.url.toString())
        assertEquals("token github-token", userRequest.header("Authorization"))
    }

    @Test
    fun aFailedUserLookupFailsTheSignInWithATableCode() = runBlocking {
        val cases = listOf<Pair<() -> NetworkResponse, String>>(
            { NetworkResponse(401, emptyMap(), "{}".toByteArray()) } to "http_401",
            { NetworkResponse(503, emptyMap(), "".toByteArray()) } to "http_503",
            { NetworkResponse(200, emptyMap(), "not json".toByteArray()) } to "parse",
            { NetworkResponse(200, emptyMap(), """{"id":"583231"}""".toByteArray()) } to "parse",
            { NetworkResponse(200, emptyMap(), """{"id":0}""".toByteArray()) } to "parse",
            { throw java.net.UnknownHostException("offline") } to "network_offline",
            { throw java.net.SocketTimeoutException("slow") } to "network_timeout",
        )
        for ((userResponse, expectedCode) in cases) {
            var clock = Instant.EPOCH
            val transport = NetworkTransport { request ->
                if (request.url.encodedPath == "/user") {
                    userResponse()
                } else {
                    NetworkResponse(200, emptyMap(), """{"access_token":"github-token"}""".toByteArray())
                }
            }
            val failure = runCatching {
                CopilotDeviceFlow(
                    transport = transport,
                    now = { clock },
                    sleeper = DeviceFlowSleeper { seconds -> clock = clock.plusSeconds(seconds.toLong()) },
                ).pollForToken(DeviceCode("d", "u", "https://example.com", 1, 30))
            }.exceptionOrNull()

            assertTrue(expectedCode, failure is DeviceFlowException.AccountLookupFailed)
            val code = LoginFailureCode.from(failure!!)
            assertEquals(expectedCode, code)
            assertEquals(code, LoginFailureCode.sanitized(code))
        }
        // The legacy-card resolver reports "cannot tell" instead of failing.
        val offline = CopilotDeviceFlow(NetworkTransport { throw java.io.IOException("offline") })
        assertEquals(null, offline.accountId("github-token"))
    }

    // region Background network loss while the approval tab covers the app

    private fun offlineHost() = java.net.UnknownHostException("Unable to resolve host \"github.com\"")

    @Test
    fun networkFailuresWhilePollingAreRetriedUntilApproval() = runBlocking {
        var clock = Instant.EPOCH
        var tokenCalls = 0
        val transport = NetworkTransport { request ->
            when (request.url.encodedPath) {
                "/login/oauth/access_token" -> {
                    tokenCalls += 1
                    if (tokenCalls <= 3) throw offlineHost()
                    NetworkResponse(200, emptyMap(), """{"access_token":"github-token"}""".toByteArray())
                }
                "/user" -> NetworkResponse(200, emptyMap(), """{"id":7}""".toByteArray())
                else -> error("unexpected ${request.url}")
            }
        }
        val sleeps = mutableListOf<Int>()
        val tokens = CopilotDeviceFlow(
            transport = transport,
            now = { clock },
            sleeper = DeviceFlowSleeper { seconds ->
                sleeps += seconds
                clock = clock.plusSeconds(seconds.toLong())
            },
        ).pollForToken(DeviceCode("d", "u", "https://example.com", 5, 900))

        assertEquals("github-token", tokens.accessToken)
        assertEquals("7", tokens.accountId)
        assertEquals(4, tokenCalls)
        // The interval is unchanged by network failures (only slow_down raises it).
        assertEquals(listOf(5, 5, 5, 5), sleeps)
    }

    @Test(expected = DeviceFlowException.Expired::class)
    fun aNetworkThatNeverComesBackEndsWhenTheCodeExpires(): Unit = runBlocking {
        var clock = Instant.EPOCH
        CopilotDeviceFlow(
            transport = NetworkTransport { throw offlineHost() },
            now = { clock },
            sleeper = DeviceFlowSleeper { seconds -> clock = clock.plusSeconds(seconds.toLong()) },
        ).pollForToken(DeviceCode("d", "u", "https://example.com", 5, 60))
        Unit
    }

    @Test
    fun returningToTheAppPollsAtOnceInsteadOfAfterTheInterval() = runBlocking {
        val transport = NetworkTransport { request ->
            val body = if (request.url.encodedPath == "/user") """{"id":7}""" else """{"access_token":"github-token"}"""
            NetworkResponse(200, emptyMap(), body.toByteArray())
        }
        val signal = com.ScienceFiction.TokenWatchAndroid.auth.ForegroundSignal()
        signal.onBackground()
        val flow = CopilotDeviceFlow(
            transport = transport,
            // A real, long interval: only the return to the app can end it within the test.
            sleeper = DeviceFlowSleeper { seconds -> kotlinx.coroutines.delay(seconds * 1_000L) },
        )

        val started = System.nanoTime()
        val poll = async { flow.pollForToken(DeviceCode("d", "u", "https://example.com", 60, 900), signal) }
        kotlinx.coroutines.delay(100)
        assertTrue(!poll.isCompleted)
        signal.onForeground()
        val tokens = kotlinx.coroutines.withTimeout(5_000) { poll.await() }

        assertEquals("github-token", tokens.accessToken)
        assertTrue(System.nanoTime() - started < 5_000_000_000L)
    }

    @Test
    fun aResumeWhileAlreadyInFrontDoesNotShortenTheInterval() = runBlocking {
        val signal = com.ScienceFiction.TokenWatchAndroid.auth.ForegroundSignal()
        signal.onForeground()
        val woke = kotlinx.coroutines.withTimeoutOrNull(300) {
            signal.pause { kotlinx.coroutines.delay(10_000) }
            true
        }
        assertEquals(null, woke)
    }

    @Test
    fun anAccountLookupCutOffInTheBackgroundIsRetriedOnReturn() = runBlocking {
        var clock = Instant.EPOCH
        val signal = com.ScienceFiction.TokenWatchAndroid.auth.ForegroundSignal()
        signal.onBackground()
        var userCalls = 0
        val transport = NetworkTransport { request ->
            if (request.url.encodedPath == "/user") {
                userCalls += 1
                if (!signal.isForeground) throw offlineHost()
                NetworkResponse(200, emptyMap(), """{"id":42}""".toByteArray())
            } else {
                NetworkResponse(200, emptyMap(), """{"access_token":"github-token"}""".toByteArray())
            }
        }
        val poll = async {
            CopilotDeviceFlow(
                transport = transport,
                now = { clock },
                sleeper = DeviceFlowSleeper { seconds -> clock = clock.plusSeconds(seconds.toLong()) },
            ).pollForToken(DeviceCode("d", "u", "https://example.com", 1, 900), signal)
        }
        kotlinx.coroutines.delay(200)
        assertTrue("waits for the app instead of failing", !poll.isCompleted)
        signal.onForeground()

        assertEquals("42", kotlinx.coroutines.withTimeout(5_000) { poll.await() }.accountId)
        assertEquals(2, userCalls)
    }

    @Test
    fun anAccountLookupThatKeepsFailingInFrontFailsTheSignIn() = runBlocking {
        var clock = Instant.EPOCH
        var userCalls = 0
        val transport = NetworkTransport { request ->
            if (request.url.encodedPath == "/user") {
                userCalls += 1
                throw offlineHost()
            }
            NetworkResponse(200, emptyMap(), """{"access_token":"github-token"}""".toByteArray())
        }
        val failure = runCatching {
            CopilotDeviceFlow(
                transport = transport,
                now = { clock },
                sleeper = DeviceFlowSleeper { seconds -> clock = clock.plusSeconds(seconds.toLong()) },
            ).pollForToken(DeviceCode("d", "u", "https://example.com", 1, 900))
        }.exceptionOrNull()

        assertTrue(failure is DeviceFlowException.AccountLookupFailed)
        assertEquals("network_offline", LoginFailureCode.from(failure!!))
        assertEquals(3, userCalls)
    }

    // endregion

    private class QueueTransport(vararg bodies: String) : NetworkTransport {
        private val responses = ArrayDeque(bodies.toList())
        val requests = mutableListOf<Request>()

        override suspend fun execute(request: Request): NetworkResponse {
            requests += request
            return NetworkResponse(200, emptyMap(), responses.removeFirst().toByteArray())
        }
    }
}
