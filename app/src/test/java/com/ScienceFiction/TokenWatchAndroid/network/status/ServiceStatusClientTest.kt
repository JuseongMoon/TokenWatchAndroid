package com.ScienceFiction.TokenWatchAndroid.network.status

import com.ScienceFiction.TokenWatchAndroid.domain.ServiceHealth
import com.ScienceFiction.TokenWatchAndroid.domain.ServiceStatusSource
import com.ScienceFiction.TokenWatchAndroid.domain.StatusPlatform
import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkResponse
import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkTransport
import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServiceStatusClientTest {
    private val source = ServiceStatusSource(
        platform = StatusPlatform.ATLASSIAN,
        jsonUrl = "https://status.example.test/api/v2/status.json",
    )

    @Test
    fun fetchesPublicJsonWithCleanBaselineHeaders() = runBlocking {
        val transport = RecordingTransport(
            NetworkResponse(
                statusCode = 200,
                headers = emptyMap(),
                body = """{"status":{"indicator":"minor"}}""".toByteArray(),
            ),
        )

        assertEquals(ServiceHealth.DEGRADED, ServiceStatusClient(transport).fetch(source))
        assertEquals("application/json", transport.request.header("Accept"))
        assertEquals(ServiceStatusClient.USER_AGENT, transport.request.header("User-Agent"))
        assertNull(transport.request.header("Authorization"))
    }

    @Test
    fun returnsNullForHttpNetworkAndDecodeFailures() = runBlocking {
        assertNull(
            ServiceStatusClient(RecordingTransport(NetworkResponse(503, emptyMap(), ByteArray(0))))
                .fetch(source),
        )
        assertNull(ServiceStatusClient(NetworkTransport { throw IOException("offline") }).fetch(source))
        assertNull(
            ServiceStatusClient(
                RecordingTransport(NetworkResponse(200, emptyMap(), "not-json".toByteArray())),
            ).fetch(source),
        )
    }

    @Test
    fun parsedUnsupportedStatusRemainsUnknownRatherThanFetchFailure() = runBlocking {
        val transport = RecordingTransport(
            NetworkResponse(
                statusCode = 200,
                headers = emptyMap(),
                body = """{"status":{"indicator":"future-value"}}""".toByteArray(),
            ),
        )

        assertEquals(ServiceHealth.UNKNOWN, ServiceStatusClient(transport).fetch(source))
    }

    private class RecordingTransport(
        private val response: NetworkResponse,
    ) : NetworkTransport {
        lateinit var request: Request

        override suspend fun execute(request: Request): NetworkResponse {
            this.request = request
            return response
        }
    }
}
