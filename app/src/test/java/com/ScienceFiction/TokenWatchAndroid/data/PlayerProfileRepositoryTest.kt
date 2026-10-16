package com.ScienceFiction.TokenWatchAndroid.data

import com.ScienceFiction.TokenWatchAndroid.domain.account.PlayerProfileCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerProfileRepositoryTest {
    @Test fun cacheRoundTripsWithTheIosFieldNames() {
        val cache = PlayerProfileCache("uid1", "토큰", "slime-sky", 1_788_652_800_123)
        val json = PlayerProfileRepository.encode(cache)
        listOf("\"uid\"", "\"nickname\"", "\"avatarId\"", "\"nicknameChangedAtMs\"").forEach {
            assert(json.contains(it)) { json }
        }
        assertEquals(cache, PlayerProfileRepository.decode(json))
        assertEquals("tokenwatch.player.profile.v1", PlayerProfileCache.STORAGE_KEY)
    }

    @Test fun theCacheHoldsNoCredentials() {
        val json = PlayerProfileRepository.encode(PlayerProfileCache("u", "n", "cat", 1))
        assertFalse(json.contains("token", ignoreCase = true))
        assertFalse(json.contains("email", ignoreCase = true))
    }

    @Test fun brokenCacheReadsAsMissing() {
        assertNull(PlayerProfileRepository.decode("not json"))
        assertNull(PlayerProfileRepository.decode("""{"uid":"u"}"""))
    }
}
