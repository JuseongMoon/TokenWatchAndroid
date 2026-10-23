package com.ScienceFiction.TokenWatchAndroid.domain.account

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NicknamePolicyTest {
    private fun check(raw: String): NicknameError? = NicknamePolicy.validate(NicknamePolicy.normalize(raw))

    @Test fun decomposedHangulIsRecomposedBeforeValidation() {
        // "토큰" typed as separate jamo (NFD), as macOS pastes it.
        val nfd = "토큰"
        assertEquals("토큰", NicknamePolicy.normalize(nfd))
        assertNull(check(nfd))
    }

    @Test fun surroundingWhitespaceIsTrimmed() {
        assertEquals("player_1", NicknamePolicy.normalize("  player_1 \n"))
        assertNull(check("  player_1 "))
    }

    @Test fun lengthBoundariesCountUtf16Units() {
        assertEquals(NicknameError.TOO_SHORT, check(""))
        assertEquals(NicknameError.TOO_SHORT, check("a"))
        assertNull(check("ab"))
        assertNull(check("a".repeat(12)))
        assertNull(check("가".repeat(12)))
        assertEquals(NicknameError.TOO_LONG, check("a".repeat(13)))
        // 11 letters + one emoji (2 UTF-16 units) is 13 units: too_long wins over invalid_chars.
        assertEquals(NicknameError.TOO_LONG, check("a".repeat(11) + "😀"))
    }

    @Test fun onlyAllowedCharactersPass() {
        assertNull(check("Ab_9-가힣"))
        assertEquals(NicknameError.INVALID_CHARS, check("😀ab"))
        assertEquals(NicknameError.INVALID_CHARS, check("a b"))
        assertEquals(NicknameError.INVALID_CHARS, check("a.b"))
        assertEquals(NicknameError.INVALID_CHARS, check("ㄱㄴ"))
        assertEquals(NicknameError.INVALID_CHARS, check("ａｂ"))
    }

    @Test fun lowerFoldsAsciiOnlyAndIgnoresTheDefaultLocale() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("ivy_가", NicknamePolicy.lower("IVY_가"))
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test fun bannedWordsMatchCaseInsensitivelyAfterNormalization() {
        assertEquals(NicknameError.BANNED_WORD, check("TheAdmin"))
        assertEquals(NicknameError.BANNED_WORD, check("xTokenWatchx"))
        assertEquals(NicknameError.BANNED_WORD, check("운영자님"))
        // NFD jamo for "관리자" recombine first, then match.
        assertEquals(NicknameError.BANNED_WORD, check("관리자"))
        // Words left out on purpose to avoid false positives.
        assertNull(check("glasses"))
        assertNull(check("essex"))
    }

    @Test fun bannedWordListMatchesTheContract() {
        assertEquals(37, NicknamePolicy.BANNED_WORDS.size)
        assertEquals(NicknamePolicy.BANNED_WORDS, NicknamePolicy.BANNED_WORDS.map { it.lowercase(Locale.ROOT) })
        assertEquals(listOf("admin", "moderator", "official", "tokenwatch"), NicknamePolicy.BANNED_WORDS.take(4))
        assertEquals("창녀", NicknamePolicy.BANNED_WORDS.last())
    }

    @Test fun cooldownAllowsAChangeFromExactlyThirtyDays() {
        val changed = 1_000_000L
        assertEquals(1L, NicknamePolicy.cooldownRemainingMs(changed, changed + NicknamePolicy.COOLDOWN_MS - 1))
        assertEquals(0L, NicknamePolicy.cooldownRemainingMs(changed, changed + NicknamePolicy.COOLDOWN_MS))
        assertEquals(0L, NicknamePolicy.cooldownRemainingMs(changed, changed + NicknamePolicy.COOLDOWN_MS + 5))
        assertEquals(NicknamePolicy.COOLDOWN_MS, NicknamePolicy.cooldownRemainingMs(changed, changed))
        assertEquals(2_592_000_000L, NicknamePolicy.COOLDOWN_MS)
    }

    @Test fun tagRules() {
        assertTrue(NicknamePolicy.isValidTag("0042"))
        assertFalse(NicknamePolicy.isValidTag("42"))
        assertFalse(NicknamePolicy.isValidTag("12a4"))
        assertFalse(NicknamePolicy.isValidTag("12345"))
        // Non-ASCII digits (Arabic-Indic, full-width) are not tags.
        assertFalse(NicknamePolicy.isValidTag("١٢٣٤"))
        assertFalse(NicknamePolicy.isValidTag("１２３４"))
    }

    @Test fun termsVersionIsTheMonsterHuntRevision() {
        // A new sign-up agrees to version 2 (the monster hunt) at once.
        assertEquals(2, PlayerProfile.CURRENT_TERMS_VERSION)
    }

    @Test fun nametagKeepsLeadingZeros() {
        val profile = PlayerProfile(
            nickname = "David",
            nicknameLower = "david",
            tag = "0304",
            nicknameChangedAtMs = 1,
            avatarId = "slime",
            termsAcceptedVersion = 1,
            createdAtMs = 1,
            updatedAtMs = 1,
        )
        // Display has one space before the tag.
        assertEquals("David #0304", profile.nametag)
    }

    @Test fun errorWireIdsMatchTheContract() {
        assertEquals(
            listOf("too_short", "too_long", "invalid_chars", "taken", "banned_word", "cooldown", "terms_required"),
            NicknameError.entries.map { it.wireId },
        )
    }

    @Test fun methodsMapToFirebaseProviderIds() {
        assertEquals(AccountMethod.APPLE, AccountMethod.fromFirebaseProviderId("apple.com"))
        assertEquals(AccountMethod.GOOGLE, AccountMethod.fromFirebaseProviderId("google.com"))
        assertNull(AccountMethod.fromFirebaseProviderId("firebase"))
        assertEquals(listOf("apple", "google"), AccountMethod.entries.map { it.wireId })
    }
}
