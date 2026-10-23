package com.ScienceFiction.TokenWatchAndroid.account

import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkResponse
import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkTransport
import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Answers requests from a script, one response per call; records every request. */
internal class ScriptedTransport(vararg responses: NetworkResponse) : NetworkTransport {
    val queue = ArrayDeque(responses.toList())
    val requests = mutableListOf<Request>()
    var failNext = false

    override suspend fun execute(request: Request): NetworkResponse {
        requests += request
        if (failNext) {
            failNext = false
            throw IOException("offline")
        }
        return queue.removeFirstOrNull() ?: response(500)
    }
}

internal fun response(status: Int, body: String = "{}", date: String? = null) = NetworkResponse(
    statusCode = status,
    headers = date?.let { mapOf("Date" to listOf(it)) } ?: emptyMap(),
    body = body.toByteArray(),
)

internal const val PROFILE_BODY = """{"fields":{
    "nickname":{"stringValue":"Tok"},"nicknameLower":{"stringValue":"tok"},"tag":{"stringValue":"0042"},
    "nicknameChangedAtMs":{"integerValue":"1000"},"createdAtMs":{"integerValue":"1000"},
    "updatedAtMs":{"integerValue":"1000"},"termsAcceptedVersion":{"integerValue":"1"},
    "avatar":{"mapValue":{"fields":{"kind":{"stringValue":"preset"},"id":{"stringValue":"cat"}}}}}}"""

class PlayerProfileClientTest {
    private val tokens = mutableListOf<Boolean>()

    private fun client(transport: NetworkTransport, token: String? = "id-token-secret", localNow: Long = 0L) =
        PlayerProfileClient(
            projectId = "tokenwatch-app",
            transport = transport,
            token = { force -> tokens += force; token },
            clock = ServerClock { localNow },
        )

    @Test fun getsTheProfileWithABearerTokenAndNoApiKey() = runBlocking {
        val transport = ScriptedTransport(response(200, PROFILE_BODY))
        val result = client(transport).fetchProfile("uid1")

        assertEquals("cat", (result as PlayerProfileClient.FetchResult.Found).profile.avatarId)
        val request = transport.requests.single()
        assertEquals(
            "https://firestore.googleapis.com/v1/projects/tokenwatch-app/databases/(default)/documents/players/uid1",
            request.url.toString(),
        )
        assertNull(request.url.queryParameter("key"))
        assertEquals("Bearer id-token-secret", request.header("Authorization"))
        assertEquals("GET", request.method)
        assertEquals(listOf(false), tokens)
    }

    @Test fun mapsMissingAndFailedReads() = runBlocking {
        assertEquals(PlayerProfileClient.FetchResult.NotFound, client(ScriptedTransport(response(404))).fetchProfile("u"))
        assertEquals(PlayerProfileClient.FetchResult.Failed, client(ScriptedTransport(response(500))).fetchProfile("u"))
        assertEquals(PlayerProfileClient.FetchResult.Failed, client(ScriptedTransport(response(200, "{}"))).fetchProfile("u"))
        val offline = ScriptedTransport().apply { failNext = true }
        assertEquals(PlayerProfileClient.FetchResult.Failed, client(offline).fetchProfile("u"))
        // Signed out: no token, no request.
        val none = ScriptedTransport()
        assertEquals(PlayerProfileClient.FetchResult.Failed, client(none, token = null).fetchProfile("u"))
        assertTrue(none.requests.isEmpty())
    }

    @Test fun a401RefreshesTheTokenOnceThenGivesUp() = runBlocking {
        val retried = ScriptedTransport(response(401), response(200, PROFILE_BODY))
        assertTrue(client(retried).fetchProfile("u") is PlayerProfileClient.FetchResult.Found)
        assertEquals(listOf(false, true), tokens)
        assertEquals(2, retried.requests.size)

        tokens.clear()
        val twice = ScriptedTransport(response(401), response(401))
        assertEquals(PlayerProfileClient.FetchResult.Failed, client(twice).fetchProfile("u"))
        assertEquals(2, twice.requests.size)
        assertEquals(listOf(false, true), tokens)
    }

    @Test fun a403AlsoRefreshesTheTokenOnce() = runBlocking {
        // An operator claim set after the cached token was issued: the rules refuse until a new token.
        val retried = ScriptedTransport(response(403), response(200, PROFILE_BODY))
        assertTrue(client(retried).fetchProfile("u") is PlayerProfileClient.FetchResult.Found)
        assertEquals(listOf(false, true), tokens)
        assertEquals(2, retried.requests.size)

        tokens.clear()
        val twice = ScriptedTransport(response(403), response(403))
        assertEquals(PlayerProfileClient.FetchResult.Failed, client(twice).fetchProfile("u"))
        assertEquals(2, twice.requests.size)
        assertEquals(listOf(false, true), tokens)
    }

    @Test fun theDateHeaderCorrectsTheClock() = runBlocking {
        // Sun, 06 Nov 1994 08:49:37 GMT = 784111777000; the device is 77 s behind.
        val transport = ScriptedTransport(response(404, date = "Sun, 06 Nov 1994 08:49:37 GMT"))
        val c = client(transport, localNow = 784_111_700_000)
        assertFalse(c.knowsServerTime)
        c.fetchProfile("u")
        assertTrue(c.knowsServerTime)
        assertEquals(784_111_777_000, c.nowMs)
    }

    @Test fun ensureServerTimeReadsFirstOnlyWhenTheSkewIsUnknown() = runBlocking {
        val transport = ScriptedTransport(response(404, date = "Sun, 06 Nov 1994 08:49:37 GMT"))
        val c = client(transport)
        c.ensureServerTime("uid1")
        assertEquals("GET", transport.requests.single().method)
        c.ensureServerTime("uid1")
        assertEquals(1, transport.requests.size)
    }

    @Test fun theTokenNeverLeavesTheAuthorizationHeader() = runBlocking {
        val transport = ScriptedTransport(response(200, PROFILE_BODY))
        client(transport).fetchProfile("uid1")
        val request = transport.requests.single()
        assertFalse(request.url.toString().contains("id-token-secret"))
        assertNull(request.body)
    }
}
