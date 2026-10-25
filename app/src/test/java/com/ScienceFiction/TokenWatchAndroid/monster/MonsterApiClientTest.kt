package com.ScienceFiction.TokenWatchAndroid.monster

import com.ScienceFiction.TokenWatchAndroid.account.ScriptedTransport
import com.ScienceFiction.TokenWatchAndroid.account.response
import com.squareup.moshi.Moshi
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The callable REST format and the leaderboard query, with the token kept to its header. */
class MonsterApiClientTest {
    private val tokens = mutableListOf<Boolean>()

    private fun client(transport: ScriptedTransport, token: String? = "id-token-secret") = MonsterApiClient(
        projectId = "tokenwatch-app",
        apiKey = "api-key",
        token = { force -> tokens += force; token },
        transport = transport,
        appVersion = "1.4.0",
    )

    private fun Request.bodyText(): String = Buffer().also { body!!.writeTo(it) }.readUtf8()

    private fun Request.json(): Map<*, *> = Moshi.Builder().build().adapter(Any::class.java).fromJson(bodyText()) as Map<*, *>

    @Test fun aCallableIsAPostWithABearerTokenAndADataEnvelope() = runBlocking {
        val transport = ScriptedTransport(response(200, """{"result":{"encounter":null}}"""))
        val result = client(transport).spawnCompletion("claude", "Current week (all models)", 604_800, 1_800_086_400_000, 1_800_086_412_345)

        assertEquals(MonsterApiClient.CallResult.Success(MonsterApiClient.SpawnResult.None), result)
        val request = transport.requests.single()
        assertEquals("POST", request.method)
        assertEquals("https://asia-northeast3-tokenwatch-app.cloudfunctions.net/spawnEncounter", request.url.toString())
        assertEquals("Bearer id-token-secret", request.header("Authorization"))
        assertTrue(request.body!!.contentType().toString().startsWith("application/json"))
        val data = request.json()["data"] as Map<*, *>
        assertEquals(
            mapOf(
                "source" to "completion", "provider" to "claude", "windowLabel" to "Current week (all models)",
                "periodSec" to 604_800.0, "resetBucketMs" to 1_800_086_400_000.0, "resetsAtMs" to 1_800_086_412_345.0,
                "platform" to "android", "appVersion" to "1.4.0", "spriteFormat" to 1.0,
            ),
            data,
        )
        // The token never leaves the header.
        assertFalse(request.url.toString().contains("id-token-secret"))
        assertFalse(request.bodyText().contains("id-token-secret"))
    }

    @Test fun aRandomSpawnSendsTheAgentKeyAndCandidates() = runBlocking {
        val transport = ScriptedTransport(response(200, """{"result":{"encounter":null,"nextDrawAtMs":1800001500000}}"""))
        val result = client(transport).spawnRandom("agent-key", listOf("codex" to "Additional limit"))
        val data = transport.requests.single().json()["data"] as Map<*, *>
        assertEquals(setOf("source", "agentKey", "candidates", "platform", "appVersion", "spriteFormat"), data.keys)
        assertEquals("agent-key", data["agentKey"])
        assertEquals(listOf(mapOf("provider" to "codex", "windowLabel" to "Additional limit")), data["candidates"])
        assertEquals(
            MonsterApiClient.CallResult.Success(MonsterApiClient.RandomDraw(encounter = null, nextDrawAtMs = 1_800_001_500_000)),
            result,
        )
    }

    @Test fun anEncounterAnswerIsDecoded() = runBlocking {
        val transport = ScriptedTransport(
            response(
                200,
                """{"result":{"encounter":{"key":"k","source":"random","provider":"kimi","windowLabel":"Current week","drawAtMs":"1800000000000","monsterId":"orc","maxHP":8,"state":"alive","spawnedAtMs":1,"expiresAtMs":2},"nextDrawAtMs":"1800001800000"}}""",
            ),
        )
        val result = client(transport).spawnRandom("agent-key", listOf("kimi" to "Current week"))
        val draw = (result as MonsterApiClient.CallResult.Success).value
        val dto = draw.encounter!!
        assertEquals("orc" to 8, dto.monsterId to dto.maxHp)
        assertEquals(1_800_000_000_000, dto.drawAtMs)
        assertEquals(1_800_001_800_000, draw.nextDrawAtMs)
        assertNull(dto.resetBucketMs)
    }

    @Test fun a401RefreshesTheTokenOnce() = runBlocking {
        val retried = ScriptedTransport(response(401), response(200, """{"result":{"merged":true}}"""))
        val result = client(retried).mergeGuestHistory(MonsterGuestProgress(kills = mapOf("slime" to 2), firstAtMs = 5))
        assertEquals(MonsterApiClient.CallResult.Success(true), result)
        assertEquals(listOf(false, true), tokens)
        val data = retried.requests[1].json()["data"] as Map<*, *>
        assertEquals(mapOf("kills" to mapOf("slime" to 2.0), "firstAtMs" to 5.0), data)
    }

    @Test fun theGuestHistoryIsCappedBeforeSending() = runBlocking {
        val transport = ScriptedTransport(response(200, """{"result":{"merged":true}}"""))
        client(transport).mergeGuestHistory(
            MonsterGuestProgress(kills = mapOf("slime" to 480, "goblin" to 30, "orc" to 2), firstAtMs = 1, lastAtMs = 9),
        )
        val data = transport.requests.single().json()["data"] as Map<*, *>
        assertEquals(mapOf("slime" to 480.0, "goblin" to 20.0), data["kills"])
        assertEquals(1.0 to 9.0, data["firstAtMs"] to data["lastAtMs"])
    }

    @Test fun anExpiredDefeatHasNoStats() = runBlocking {
        val transport = ScriptedTransport(
            response(
                200,
                """{"result":{"encounter":{"key":"k","source":"completion","provider":"claude","windowLabel":"w","monsterId":"orc","maxHP":8,"state":"expired","spawnedAtMs":1,"expiresAtMs":2}}}""",
            ),
        )
        val (dto, stats) = (client(transport).defeat("k", 5) as MonsterApiClient.CallResult.Success).value
        assertEquals("expired", dto?.state)
        assertNull(stats)
    }

    @Test fun setProfileSendsTheOpAndReadsTheServersProfile() = runBlocking {
        val profile = """{"result":{"profile":{"nickname":"Tok","nicknameLower":"tok","tag":"8571","nicknameChangedAtMs":5,"avatar":{"kind":"preset","id":"cat"},"termsAcceptedVersion":2,"createdAtMs":5,"updatedAtMs":6}}}"""
        val transport = ScriptedTransport(response(200, profile), response(200, profile), response(200, profile), response(200, "{\"result\":{}}"))
        val c = client(transport)
        val created = c.setProfile(MonsterApiClient.ProfileChange.Create("Tok", "cat", 2))
        assertEquals("8571", (created as MonsterApiClient.CallResult.Success).value.tag)
        c.setProfile(MonsterApiClient.ProfileChange.Nickname("Tok"))
        c.setProfile(MonsterApiClient.ProfileChange.Avatar("cat"))
        assertEquals("https://asia-northeast3-tokenwatch-app.cloudfunctions.net/setProfile", transport.requests[0].url.toString())
        assertEquals(
            listOf(
                mapOf("op" to "create", "nickname" to "Tok", "avatarId" to "cat", "termsAcceptedVersion" to 2.0),
                mapOf("op" to "nickname", "nickname" to "Tok"),
                mapOf("op" to "avatar", "avatarId" to "cat"),
            ),
            transport.requests.take(3).map { it.json()["data"] },
        )
        // An answer without a readable profile is not a save.
        assertEquals(
            MonsterApiClient.CallResult.Failure(MonsterApiClient.CallError.Transport),
            c.setProfile(MonsterApiClient.ProfileChange.Avatar("cat")),
        )
    }

    @Test fun deleteAccountIsAnEmptyCall() = runBlocking {
        val transport = ScriptedTransport(response(200, """{"result":{"deleted":true}}"""))
        assertEquals(MonsterApiClient.CallResult.Success(Unit), client(transport).deleteAccount())
        val request = transport.requests.single()
        assertEquals("https://asia-northeast3-tokenwatch-app.cloudfunctions.net/deleteAccount", request.url.toString())
        assertEquals(emptyMap<String, Any>(), request.json()["data"])
    }

    @Test fun signedOutCallsNothing() = runBlocking {
        val none = ScriptedTransport()
        assertEquals(
            MonsterApiClient.CallResult.Failure(MonsterApiClient.CallError.NotSignedIn),
            client(none, token = null).acceptTerms(2),
        )
        assertTrue(none.requests.isEmpty())
    }

    @Test fun offlineIsATransportFailure() = runBlocking {
        val offline = ScriptedTransport().apply { failNext = true }
        assertEquals(
            MonsterApiClient.CallResult.Failure(MonsterApiClient.CallError.Transport),
            client(offline).myRank(),
        )
    }

    @Test fun rankAndStats() = runBlocking {
        val transport = ScriptedTransport(
            response(200, """{"result":{"listed":true,"rank":3,"totalScore":420,"commonKills":7,"specialKills":2}}"""),
        )
        assertEquals(
            MonsterApiClient.CallResult.Success(MyRank(listed = true, rank = 3, stats = MonsterStats(420, 7, 2))),
            client(transport).myRank(),
        )
    }

    @Test fun theLeaderboardIsARunQueryForTheTopTen() = runBlocking {
        val transport = ScriptedTransport(response(200, "[]"))
        assertEquals(emptyList<LeaderboardEntry>(), client(transport).leaderboardTop(LeaderboardScope.ALL_TIME, "2026-10-19"))
        val request = transport.requests.single()
        assertEquals("POST", request.method)
        assertEquals(
            "https://firestore.googleapis.com/v1/projects/tokenwatch-app/databases/(default)/documents:runQuery?key=api-key",
            request.url.toString(),
        )
        val query = request.json()["structuredQuery"] as Map<*, *>
        assertEquals(listOf(mapOf("collectionId" to "leaderboard")), query["from"])
        assertEquals(
            listOf(
                mapOf("field" to mapOf("fieldPath" to "totalScore"), "direction" to "DESCENDING"),
                mapOf("field" to mapOf("fieldPath" to "scoreReachedAtMs"), "direction" to "ASCENDING"),
            ),
            query["orderBy"],
        )
        assertEquals(10.0, query["limit"])
        // Readable signed out: no token, no header.
        val signedOut = ScriptedTransport(response(200, "[]"))
        client(signedOut, token = null).leaderboardTop(LeaderboardScope.ALL_TIME, "2026-10-19")
        assertNull(signedOut.requests.single().header("Authorization"))
        // A failed read is null (the screen keeps the last list).
        assertNull(client(ScriptedTransport(response(403))).leaderboardTop(LeaderboardScope.ALL_TIME, "2026-10-19"))
        // The week tab sends the week filter.
        val week = ScriptedTransport(response(200, "[]"))
        client(week).leaderboardTop(LeaderboardScope.WEEK, "2026-10-19")
        val where = (week.requests.single().json()["structuredQuery"] as Map<*, *>)["where"] as Map<*, *>
        assertEquals("2026-10-19", ((where["fieldFilter"] as Map<*, *>)["value"] as Map<*, *>)["stringValue"])
    }

    @Test fun theBestiaryReadsOnlyTheTwoKillMapsOfTheOwnDocument() = runBlocking {
        val transport = ScriptedTransport(
            response(403),
            response(200, """{"name":"projects/tokenwatch-app/databases/(default)/documents/players/u1"}"""),
        )
        assertEquals(emptyMap<String, Int>(), client(transport).myKills("u1"))
        // A 403 (a claim the cached token predates) refreshes the token once.
        assertEquals(listOf(false, true), tokens)
        val request = transport.requests[1]
        assertEquals("GET", request.method)
        assertEquals(
            "https://firestore.googleapis.com/v1/projects/tokenwatch-app/databases/(default)/documents/players/u1" +
                "?mask.fieldPaths=stats.kills&mask.fieldPaths=guestHistory.kills",
            request.url.toString(),
        )
        assertEquals("Bearer id-token-secret", request.header("Authorization"))
        // A failed read, signed out or offline is null (the box keeps the last values or offers a retry).
        assertNull(client(ScriptedTransport(response(500))).myKills("u1"))
        assertNull(client(ScriptedTransport(), token = null).myKills("u1"))
        assertNull(client(ScriptedTransport().apply { failNext = true }).myKills("u1"))
    }

    @Test fun theConfigDocumentIsReadWithoutAuthentication() = runBlocking {
        val payload = """{"dailySpawnCap":5}""".replace("\"", "\\\"")
        val transport = ScriptedTransport(response(200, """{"fields":{"payload":{"stringValue":"$payload"}}}"""))
        val fetched = MonsterConfigClient(transport, "tokenwatch-app", "api-key").fetchPayload()
        assertEquals("""{"dailySpawnCap":5}""", fetched)
        val request = transport.requests.single()
        assertEquals(
            "https://firestore.googleapis.com/v1/projects/tokenwatch-app/databases/(default)/documents/config/monster?key=api-key",
            request.url.toString(),
        )
        assertNull(request.header("Authorization"))
        assertNull(MonsterConfigClient(ScriptedTransport(response(404)), "p", null).fetchPayload())
    }
}
