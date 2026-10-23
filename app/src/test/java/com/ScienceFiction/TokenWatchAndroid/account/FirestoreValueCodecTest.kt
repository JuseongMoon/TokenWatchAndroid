package com.ScienceFiction.TokenWatchAndroid.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FirestoreValueCodecTest {
    @Test fun integersArriveAsDecimalStringsOrNumbers() {
        assertEquals(FirestoreValue.IntegerValue(1_788_652_800_000), FirestoreValue.fromJson(mapOf("integerValue" to "1788652800000")))
        assertEquals(FirestoreValue.IntegerValue(7), FirestoreValue.fromJson(mapOf("integerValue" to 7.0)))
        assertEquals(FirestoreValue.StringValue("x"), FirestoreValue.fromJson(mapOf("stringValue" to "x")))
        // Unknown kinds are ignored.
        assertNull(FirestoreValue.fromJson(mapOf("booleanValue" to true)))
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
