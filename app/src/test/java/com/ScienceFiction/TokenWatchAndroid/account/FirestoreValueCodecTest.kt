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
        tag = "0042",
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
        val (player, nametag) = writes(docs.create("uid1", profile))
        assertEquals("$root/players/uid1", player.obj("update")["name"])
        assertEquals(mapOf("exists" to false), player["currentDocument"])
        assertNull(player["updateMask"])
        val fields = player.obj("update").obj("fields")
        assertEquals(
            setOf(
                "nickname", "nicknameLower", "tag", "nicknameChangedAtMs", "avatar", "termsAcceptedVersion",
                "createdAtMs", "updatedAtMs",
            ),
            fields.keys,
        )
        assertEquals(mapOf("integerValue" to "1788652800000"), fields["createdAtMs"])
        // The tag is a string, so the leading zero survives.
        assertEquals(mapOf("stringValue" to "0042"), fields["tag"])
        assertEquals(
            mapOf("mapValue" to mapOf("fields" to mapOf("kind" to mapOf("stringValue" to "preset"), "id" to mapOf("stringValue" to "dragon")))),
            fields["avatar"],
        )
        // The commit body keeps the raw `#`; only request URLs percent-encode it.
        assertEquals("$root/nametags/tok#0042", nametag.obj("update")["name"])
        assertEquals(mapOf("exists" to false), nametag["currentDocument"])
        assertEquals(mapOf("uid" to mapOf("stringValue" to "uid1")), nametag.obj("update")["fields"])
    }

    @Test fun nicknameChangeMasksPlayersAndMovesTheReservation() {
        val result = writes(
            docs.changeNickname("uid1", oldKey = "tok#0042", nickname = "새이름", lower = "새이름", tag = "8571", nowMs = 5),
        )
        assertEquals(3, result.size)
        val player = result[0]
        assertEquals(
            mapOf("fieldPaths" to listOf("nickname", "nicknameLower", "tag", "nicknameChangedAtMs", "updatedAtMs")),
            player["updateMask"],
        )
        assertEquals(mapOf("exists" to true), player["currentDocument"])
        assertEquals(mapOf("stringValue" to "8571"), player.obj("update").obj("fields")["tag"])
        assertEquals("$root/nametags/새이름#8571", result[1].obj("update")["name"])
        assertEquals(mapOf("exists" to false), result[1]["currentDocument"])
        assertEquals(mapOf("delete" to "$root/nametags/tok#0042"), result[2])
    }

    @Test fun caseOnlyNicknameChangeStillMovesTheReservation() {
        // A new tag is drawn even for a case-only change, so the key always changes.
        val result = docs.changeNickname("uid1", oldKey = "tok#0042", nickname = "TOK", lower = "tok", tag = "0777", nowMs = 5)
        assertEquals(3, result.size)
        assertEquals(PlayerDocuments.NICKNAME_MASK, (result[0] as FirestoreWrite.Update).mask)
        assertEquals(docs.nametag("tok#0777"), (result[1] as FirestoreWrite.Update).name)
        assertEquals(FirestoreWrite.Delete(docs.nametag("tok#0042")), result[2])
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
            docs.changeNickname("u", "a#1234", "b", "b", "5678", 1),
            docs.changeNickname("u", "a#1234", "A", "a", "5678", 1),
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
            listOf(mapOf("delete" to "$root/nametags/tok#0042"), mapOf("delete" to "$root/players/uid1")),
            writes(docs.delete("uid1", "tok#0042")),
        )
    }

    @Test fun profileDecodingIgnoresUnknownFieldsAndFallsBackForOtherAvatarKinds() {
        val body = """{"name":"x","fields":{
            "nickname":{"stringValue":"Tok"},"nicknameLower":{"stringValue":"tok"},"tag":{"stringValue":"0042"},
            "nicknameChangedAtMs":{"integerValue":"10"},"createdAtMs":{"integerValue":"5"},
            "updatedAtMs":{"integerValue":"11"},"termsAcceptedVersion":{"integerValue":"1"},
            "stats":{"mapValue":{"fields":{"kills":{"integerValue":"3"}}}},
            "flag":{"booleanValue":true},
            "avatar":{"mapValue":{"fields":{"kind":{"stringValue":"photo"},"path":{"stringValue":"p"}}}}}}"""
        val decoded = PlayerDocuments.decodeProfile(body)!!
        assertEquals("slime", decoded.avatarId)
        assertEquals("0042", decoded.tag)
        assertEquals(10L, decoded.nicknameChangedAtMs)
        assertEquals(11L, decoded.updatedAtMs)
        assertEquals(1, decoded.termsAcceptedVersion)
    }

    @Test fun profileDecodingRequiresCoreFields() {
        assertNull(PlayerDocuments.decodeProfile("""{"fields":{"nickname":{"stringValue":"Tok"}}}"""))
        assertNull(PlayerDocuments.decodeProfile("not json"))
        fun body(tag: String?) = """{"fields":{"nickname":{"stringValue":"Tok"},"nicknameLower":{"stringValue":"tok"},
            ${tag?.let { "\"tag\":{\"stringValue\":\"$it\"}," } ?: ""}
            "nicknameChangedAtMs":{"integerValue":"10"},"createdAtMs":{"integerValue":"5"},
            "avatar":{"mapValue":{"fields":{"kind":{"stringValue":"preset"},"id":{"stringValue":"mimic"}}}}}}"""
        val decoded = PlayerDocuments.decodeProfile(body("9999"))!!
        assertEquals("mimic", decoded.avatarId)
        assertEquals("9999", decoded.tag)
        assertEquals(5L, decoded.updatedAtMs)
        assertEquals(0, decoded.termsAcceptedVersion)
        // A profile without a valid four-digit tag (the pre-nametag shape, or garbage) fails to decode.
        assertNull(PlayerDocuments.decodeProfile(body(null)))
        assertNull(PlayerDocuments.decodeProfile(body("42")))
        assertNull(PlayerDocuments.decodeProfile(body("12a4")))
    }

    @Test fun nametagOwnerIsRead() {
        assertEquals("uid9", PlayerDocuments.decodeNametagOwner("""{"fields":{"uid":{"stringValue":"uid9"}}}"""))
        assertNull(PlayerDocuments.decodeNametagOwner("""{"fields":{}}"""))
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
