package com.ScienceFiction.TokenWatchAndroid.account

import com.ScienceFiction.TokenWatchAndroid.domain.account.PlayerProfile
import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FirestoreValueCodecTest {
    private val docs = PlayerDocuments("tokenwatch-app")
    private val root = "projects/tokenwatch-app/databases/(default)/documents"
    private val profile = PlayerProfile(
        nickname = "Tok",
        nicknameLower = "tok",
        nicknameChangedAtMs = 1_788_652_800_000,
        avatarId = "dragon",
        termsAcceptedVersion = 1,
        createdAtMs = 1_788_652_800_000,
        updatedAtMs = 1_788_652_800_000,
    )

    @Suppress("UNCHECKED_CAST")
    private fun writes(list: List<FirestoreWrite>): List<Map<String, Any?>> {
        val json = PlayerDocuments.commitBody(list)
        val parsed = Moshi.Builder().build().adapter(Any::class.java).fromJson(json) as Map<String, Any?>
        return parsed["writes"] as List<Map<String, Any?>>
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.obj(key: String) = this[key] as Map<String, Any?>

    @Test fun integersTravelAsDecimalStrings() {
        assertEquals(mapOf("integerValue" to "1788652800000"), FirestoreValue.IntegerValue(1_788_652_800_000).toJson())
        assertEquals(mapOf("stringValue" to "x"), FirestoreValue.StringValue("x").toJson())
    }

    @Test fun creationWritesBothDocumentsAsNewOnly() {
        val (player, nickname) = writes(docs.create("uid1", profile))
        assertEquals("$root/players/uid1", player.obj("update")["name"])
        assertEquals(mapOf("exists" to false), player["currentDocument"])
        assertNull(player["updateMask"])
        val fields = player.obj("update").obj("fields")
        assertEquals(
            setOf("nickname", "nicknameLower", "nicknameChangedAtMs", "avatar", "termsAcceptedVersion", "createdAtMs", "updatedAtMs"),
            fields.keys,
        )
        assertEquals(mapOf("integerValue" to "1788652800000"), fields["createdAtMs"])
        assertEquals(
            mapOf("mapValue" to mapOf("fields" to mapOf("kind" to mapOf("stringValue" to "preset"), "id" to mapOf("stringValue" to "dragon")))),
            fields["avatar"],
        )
        assertEquals("$root/nicknames/tok", nickname.obj("update")["name"])
        assertEquals(mapOf("exists" to false), nickname["currentDocument"])
        assertEquals(mapOf("uid" to mapOf("stringValue" to "uid1")), nickname.obj("update")["fields"])
    }

    @Test fun nicknameChangeMasksPlayersAndMovesTheReservation() {
        val result = writes(docs.changeNickname("uid1", oldLower = "tok", nickname = "새이름", lower = "새이름", nowMs = 5))
        assertEquals(3, result.size)
        val player = result[0]
        assertEquals(
            mapOf("fieldPaths" to listOf("nickname", "nicknameLower", "nicknameChangedAtMs", "updatedAtMs")),
            player["updateMask"],
        )
        assertEquals(mapOf("exists" to true), player["currentDocument"])
        assertEquals("$root/nicknames/새이름", result[1].obj("update")["name"])
        assertEquals(mapOf("exists" to false), result[1]["currentDocument"])
        assertEquals(mapOf("delete" to "$root/nicknames/tok"), result[2])
    }

    @Test fun caseOnlyNicknameChangeLeavesTheReservationAlone() {
        val result = docs.changeNickname("uid1", oldLower = "tok", nickname = "TOK", lower = "tok", nowMs = 5)
        assertEquals(1, result.size)
        assertEquals(PlayerDocuments.NICKNAME_MASK, (result.single() as FirestoreWrite.Update).mask)
    }

    @Test fun avatarAndTermsUpdatesAreMasked() {
        val avatar = writes(docs.changeAvatar("uid1", "cat", 9)).single()
        assertEquals(mapOf("fieldPaths" to listOf("avatar", "updatedAtMs")), avatar["updateMask"])
        assertEquals(mapOf("exists" to true), avatar["currentDocument"])
        val terms = writes(docs.acceptTerms("uid1", 2, 9)).single()
        assertEquals(mapOf("fieldPaths" to listOf("termsAcceptedVersion", "updatedAtMs")), terms["updateMask"])
        assertEquals(mapOf("exists" to true), terms["currentDocument"])
    }

    @Test fun everyWriteToAnExistingPlayerCarriesAMask() {
        val batches = listOf(
            docs.changeNickname("u", "a", "b", "b", 1),
            docs.changeNickname("u", "a", "A", "a", 1),
            docs.changeAvatar("u", "cat", 1),
            docs.acceptTerms("u", 1, 1),
        )
        batches.flatten().filterIsInstance<FirestoreWrite.Update>()
            .filter { it.name == docs.player("u") }
            .forEach {
                assertTrue(it.mask != null)
                assertEquals(true, it.exists)
            }
    }

    @Test fun deletionRemovesReservationAndProfile() {
        assertEquals(
            listOf(mapOf("delete" to "$root/nicknames/tok"), mapOf("delete" to "$root/players/uid1")),
            writes(docs.delete("uid1", "tok")),
        )
    }

    @Test fun profileDecodingIgnoresUnknownFieldsAndFallsBackForOtherAvatarKinds() {
        val body = """{"name":"x","fields":{
            "nickname":{"stringValue":"Tok"},"nicknameLower":{"stringValue":"tok"},
            "nicknameChangedAtMs":{"integerValue":"10"},"createdAtMs":{"integerValue":"5"},
            "updatedAtMs":{"integerValue":"11"},"termsAcceptedVersion":{"integerValue":"1"},
            "stats":{"mapValue":{"fields":{"kills":{"integerValue":"3"}}}},
            "flag":{"booleanValue":true},
            "avatar":{"mapValue":{"fields":{"kind":{"stringValue":"photo"},"path":{"stringValue":"p"}}}}}}"""
        val decoded = PlayerDocuments.decodeProfile(body)!!
        assertEquals("slime", decoded.avatarId)
        assertEquals(10L, decoded.nicknameChangedAtMs)
        assertEquals(11L, decoded.updatedAtMs)
        assertEquals(1, decoded.termsAcceptedVersion)
    }

    @Test fun profileDecodingRequiresCoreFields() {
        assertNull(PlayerDocuments.decodeProfile("""{"fields":{"nickname":{"stringValue":"Tok"}}}"""))
        assertNull(PlayerDocuments.decodeProfile("not json"))
        val preset = """{"fields":{"nickname":{"stringValue":"Tok"},"nicknameLower":{"stringValue":"tok"},
            "nicknameChangedAtMs":{"integerValue":"10"},"createdAtMs":{"integerValue":"5"},
            "avatar":{"mapValue":{"fields":{"kind":{"stringValue":"preset"},"id":{"stringValue":"mimic"}}}}}}"""
        val decoded = PlayerDocuments.decodeProfile(preset)!!
        assertEquals("mimic", decoded.avatarId)
        assertEquals(5L, decoded.updatedAtMs)
        assertEquals(0, decoded.termsAcceptedVersion)
    }

    @Test fun nicknameOwnerIsRead() {
        assertEquals("uid9", PlayerDocuments.decodeNicknameOwner("""{"fields":{"uid":{"stringValue":"uid9"}}}"""))
        assertNull(PlayerDocuments.decodeNicknameOwner("""{"fields":{}}"""))
    }

    @Test fun serverClockUsesTheDateHeader() {
        var local = 1_000_000L
        val clock = ServerClock { local }
        assertNull(clock.offsetMs)
        assertEquals(1_000_000L, clock.nowMs())
        // Sun, 06 Nov 1994 08:49:37 GMT = 784111777000 ms.
        clock.record("Sun, 06 Nov 1994 08:49:37 GMT", receivedAtMs = 784_111_700_000)
        assertEquals(77_000L, clock.offsetMs)
        local = 2_000L
        assertEquals(79_000L, clock.nowMs())
        clock.record("garbage")
        assertEquals(77_000L, clock.offsetMs)
    }
}
