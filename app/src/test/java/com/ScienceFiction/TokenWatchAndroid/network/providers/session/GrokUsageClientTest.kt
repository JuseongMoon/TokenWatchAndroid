package com.ScienceFiction.TokenWatchAndroid.network.providers.session

import com.ScienceFiction.TokenWatchAndroid.auth.OAuthTokens
import com.ScienceFiction.TokenWatchAndroid.domain.UsageStyle
import com.ScienceFiction.TokenWatchAndroid.domain.WindowKind
import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkResponse
import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkTransport
import java.time.Instant
import kotlinx.coroutines.runBlocking
import okio.Buffer
import okhttp3.Request
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GrokUsageClientTest {
    @Test
    fun grpcWebRequestAndSchemaLessResponseMatchIosContract() = runBlocking {
        val transport = RecordingTransport(
            NetworkResponse(
                statusCode = 200,
                headers = mapOf("grpc-status" to listOf("0")),
                body = grpcFrame(sampleMessage()),
            ),
        )
        val client = GrokUsageClient(transport, endpoint = FIXTURE_ENDPOINT)

        val windows = client.fetch(OAuthTokens.session("sso=sessiontoken; grok_device_id=device"))

        val request = transport.request
        assertEquals(FIXTURE_ENDPOINT, request.url.toString())
        assertEquals("POST", request.method)
        assertEquals("application/grpc-web+proto", request.header("Content-Type"))
        assertEquals("application/grpc-web+proto", request.header("Accept"))
        assertEquals("sso=sessiontoken; grok_device_id=device", request.header("Cookie"))
        assertEquals("TokenWatch/1.0", request.header("User-Agent"))
        assertEquals("1", request.header("x-grpc-web"))
        assertArrayEquals(byteArrayOf(0, 0, 0, 0, 0), request.bodyBytes())

        assertEquals(1, windows.size)
        assertEquals("Credits", windows.single().label)
        assertEquals(12.5, windows.single().usedPercent, 0.0)
        assertEquals(Instant.ofEpochSecond(1_700_000_000), windows.single().resetsAt)
        assertEquals(WindowKind.WEEKLY, windows.single().kind)
        assertEquals(UsageStyle.GAUGE, windows.single().style)
    }

    @Test
    fun mapReturnsEmptyWhenNoNumericSignal() {
        assertTrue(GrokUsageClient.map(ByteArray(0)).isEmpty())
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

    private fun sampleMessage(): ByteArray {
        val nested = tag(1, 0) + varint(1_700_000_000uL)
        return tag(1, 0) + varint(42uL) +
            tag(2, 1) + fixed64LittleEndian(12.5.toBits().toULong()) +
            tag(3, 2) + varint(nested.size.toULong()) + nested
    }

    private fun grpcFrame(payload: ByteArray): ByteArray {
        val length = payload.size
        return byteArrayOf(
            0,
            ((length shr 24) and 0xff).toByte(),
            ((length shr 16) and 0xff).toByte(),
            ((length shr 8) and 0xff).toByte(),
            (length and 0xff).toByte(),
        ) + payload + byteArrayOf(0x80.toByte(), 0, 0, 0, 0)
    }

    private fun tag(field: Int, wireType: Int) = varint(((field shl 3) or wireType).toULong())

    private fun varint(input: ULong): ByteArray {
        var value = input
        val output = mutableListOf<Byte>()
        do {
            var byte = (value and 0x7fuL).toInt()
            value = value shr 7
            if (value != 0uL) byte = byte or 0x80
            output += byte.toByte()
        } while (value != 0uL)
        return output.toByteArray()
    }

    private fun fixed64LittleEndian(value: ULong) =
        ByteArray(8) { offset -> ((value shr (8 * offset)) and 0xffuL).toByte() }

    private companion object {
        const val FIXTURE_ENDPOINT = "https://fixture.example/grok-usage"
    }
}
