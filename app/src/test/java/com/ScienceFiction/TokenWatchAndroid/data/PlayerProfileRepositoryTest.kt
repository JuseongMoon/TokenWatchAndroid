package com.ScienceFiction.TokenWatchAndroid.data

import com.ScienceFiction.TokenWatchAndroid.domain.account.PlayerProfileCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerProfileRepositoryTest {
    @Test fun cacheRoundTripsWithTheIosFieldNames() {
        val cache = PlayerProfileCache("uid1", "토큰", "0042", "slime-sky", 1_788_652_800_123)
        val json = PlayerProfileRepository.encode(cache)
        listOf("\"uid\"", "\"nickname\"", "\"tag\"", "\"avatarId\"", "\"nicknameChangedAtMs\"").forEach {
            assert(json.contains(it)) { json }
        }
        // The tag stays a string, leading zero included.
        assert(json.contains("\"tag\":\"0042\"")) { json }
        assertEquals(cache, PlayerProfileRepository.decode(json))
        assertEquals("tokenwatch.player.profile.v1", PlayerProfileCache.STORAGE_KEY)
    }

    @Test fun theTermsVersionIsAnOptionalKey() {
        val consented = PlayerProfileCache("uid1", "토큰", "0042", "cat", 1, termsAcceptedVersion = 2)
        val json = PlayerProfileRepository.encode(consented)
        assert(json.contains("\"termsAcceptedVersion\":2")) { json }
        assertEquals(consented, PlayerProfileRepository.decode(json))
        // Written before the key existed: still read, without it.
        val legacy = """{"uid":"u","nickname":"David","tag":"0042","avatarId":"slime","nicknameChangedAtMs":1}"""
        assertEquals(null, PlayerProfileRepository.decode(legacy)?.termsAcceptedVersion)
        assertEquals("0042", PlayerProfileRepository.decode(legacy)?.tag)
        assertFalse(PlayerProfileRepository.encode(consented.copy(termsAcceptedVersion = null)).contains("termsAcceptedVersion"))
    }

    @Test fun theCacheHoldsNoCredentials() {
        val json = PlayerProfileRepository.encode(PlayerProfileCache("u", "n", "1234", "cat", 1))
        assertFalse(json.contains("token", ignoreCase = true))
        assertFalse(json.contains("email", ignoreCase = true))
    }

    @Test fun brokenCacheReadsAsMissing() {
        assertNull(PlayerProfileRepository.decode("not json"))
        assertNull(PlayerProfileRepository.decode("""{"uid":"u"}"""))
    }

    @Test fun aCacheFromBeforeNametagsReadsAsMissing() {
        // Written by the first account build: no tag, so the profile is re-read from the server.
        val old = """{"uid":"u","nickname":"David","avatarId":"slime","nicknameChangedAtMs":1}"""
        assertNull(PlayerProfileRepository.decode(old))
    }
}
