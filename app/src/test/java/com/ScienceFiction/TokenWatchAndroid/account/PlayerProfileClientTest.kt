package com.ScienceFiction.TokenWatchAndroid.account

import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkResponse
import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkTransport
import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okio.Buffer
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
    "nickname":{"stringValue":"Tok"},"nicknameLower":{"stringValue":"tok"},
    "nicknameChangedAtMs":{"integerValue":"1000"},"createdAtMs":{"integerValue":"1000"},
    "updatedAtMs":{"integerValue":"1000"},"termsAcceptedVersion":{"integerValue":"1"},
    "avatar":{"mapValue":{"fields":{"kind":{"stringValue":"preset"},"id":{"stringValue":"cat"}}}}}}"""

internal fun ownerBody(uid: String) = """{"fields":{"uid":{"stringValue":"$uid"}}}"""

class PlayerProfileClientTest {
    private val tokens = mutableListOf<Boolean>()

    private fun client(transport: NetworkTransport, token: String? = "id-token-secret", localNow: Long = 0L) =
        PlayerProfileClient(
            projectId = "tokenwatch-app",
            transport = transport,
            token = { force -> tokens += force; token },
            clock = ServerClock { localNow },
        )

    private fun Request.bodyText(): String = Buffer().also { body!!.writeTo(it) }.readUtf8()

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
        assertEquals(PlayerProfileClient.FetchResult.Failed, client(ScriptedTransport(response(403))).fetchProfile("u"))
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

    @Test fun nicknameAvailabilityTreatsTheOwnReservationAsFree() = runBlocking {
        assertEquals(
            PlayerProfileClient.Availability.AVAILABLE,
            client(ScriptedTransport(response(404))).nicknameAvailability("tok", "me"),
        )
        assertEquals(
            PlayerProfileClient.Availability.AVAILABLE,
            client(ScriptedTransport(response(200, ownerBody("me")))).nicknameAvailability("tok", "me"),
        )
        assertEquals(
            PlayerProfileClient.Availability.TAKEN,
            client(ScriptedTransport(response(200, ownerBody("other")))).nicknameAvailability("tok", "me"),
        )
        assertEquals(
            PlayerProfileClient.Availability.FAILED,
            client(ScriptedTransport(response(500))).nicknameAvailability("tok", "me"),
        )
    }

    @Test fun koreanDocumentIdsArePercentEncodedInThePath() = runBlocking {
        val transport = ScriptedTransport(response(404))
        client(transport).nicknameAvailability("토큰", "me")
        val url = transport.requests.single().url
        assertTrue(url.encodedPath.endsWith("/documents/nicknames/%ED%86%A0%ED%81%B0"))
        assertEquals("토큰", url.pathSegments.last())
    }

    @Test fun commitPostsTheBatchToTheCommitEndpoint() = runBlocking {
        val transport = ScriptedTransport(response(200))
        val c = client(transport)
        val result = c.commit(c.documents.changeAvatar("uid1", "cat", 5), "uid1")

        assertEquals(PlayerProfileClient.CommitResult.OK, result)
        val request = transport.requests.single()
        assertEquals("POST", request.method)
        assertEquals(
            "https://firestore.googleapis.com/v1/projects/tokenwatch-app/databases/(default)/documents:commit",
            request.url.toString(),
        )
        assertTrue(request.bodyText().contains("\"updateMask\""))
        // The body names documents by their raw path, not the encoded URL form.
        assertTrue(request.bodyText().contains("projects/tokenwatch-app/databases/(default)/documents/players/uid1"))
    }

    @Test fun aFailedCommitIsTakenOnlyWhenAnotherUidHoldsTheNickname() = runBlocking {
        val taken = ScriptedTransport(response(400), response(200, ownerBody("other")))
        val c = client(taken)
        assertEquals(PlayerProfileClient.CommitResult.TAKEN, c.commit(emptyList(), "me", nicknameLower = "tok"))
        assertEquals(2, taken.requests.size)

        val mine = ScriptedTransport(response(409), response(200, ownerBody("me")))
        assertEquals(PlayerProfileClient.CommitResult.FAILED, client(mine).commit(emptyList(), "me", nicknameLower = "tok"))

        val noNickname = ScriptedTransport(response(403))
        assertEquals(PlayerProfileClient.CommitResult.FAILED, client(noNickname).commit(emptyList(), "me"))
        assertEquals(1, noNickname.requests.size)
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
        val transport = ScriptedTransport(response(200))
        val c = client(transport)
        c.commit(c.documents.create("uid1", com.ScienceFiction.TokenWatchAndroid.domain.account.PlayerProfile(
            "Tok", "tok", 1, "cat", 1, 1, 1,
        )), "uid1")
        val request = transport.requests.single()
        assertFalse(request.url.toString().contains("id-token-secret"))
        assertFalse(request.bodyText().contains("id-token-secret"))
    }
}
