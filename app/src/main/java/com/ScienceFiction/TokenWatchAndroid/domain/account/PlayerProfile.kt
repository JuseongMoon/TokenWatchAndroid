package com.ScienceFiction.TokenWatchAndroid.domain.account

import java.text.Normalizer

/**
 * The optional TokenWatch app account — separate from the provider accounts. This file holds the
 * server profile model (`players/{uid}`) and the nickname rules, mirroring iOS `PlayerProfile.swift`.
 * No email, provider data or token ever lives here. Rules, error codes and the banned-word list match
 * iOS and the server rule regex.
 */

/** App account sign-in method. Also the analytics `method` value. */
enum class AccountMethod(val wireId: String, val firebaseProviderId: String) {
    APPLE("apple", "apple.com"),
    GOOGLE("google", "google.com"),
    ;

    companion object {
        fun fromFirebaseProviderId(id: String): AccountMethod? = entries.firstOrNull { it.firebaseProviderId == id }
    }
}

/** The `players/{uid}` document. Times are epoch milliseconds, already corrected for server skew. */
data class PlayerProfile(
    val nickname: String,
    val nicknameLower: String,
    val nicknameChangedAtMs: Long,
    /** The id of `avatar: {kind: "preset", id}`. Any other kind decodes to the fallback. */
    val avatarId: String,
    val termsAcceptedVersion: Int,
    val createdAtMs: Long,
    val updatedAtMs: Long,
) {
    companion object {
        /** Terms and privacy consent version. Bump it when the terms change and ask again. */
        const val CURRENT_TERMS_VERSION = 1
    }
}

/** Profile save failure codes (server contract). The client picks its copy from these. */
enum class NicknameError(val wireId: String) {
    TOO_SHORT("too_short"),
    TOO_LONG("too_long"),
    INVALID_CHARS("invalid_chars"),
    TAKEN("taken"),
    BANNED_WORD("banned_word"),
    COOLDOWN("cooldown"),
    TERMS_REQUIRED("terms_required"),
}

/** Nickname normalization, validation and cooldown. Order: NFC → trim → length → characters → banned words. */
object NicknamePolicy {
    const val MIN_LENGTH = 2
    const val MAX_LENGTH = 12

    /** Once every 7 days; creation counts as a change. */
    const val COOLDOWN_MS: Long = 7L * 24 * 60 * 60 * 1000

    /** NFC first, so decomposed Hangul pasted from macOS (separate jamo) recombines into syllables. */
    fun normalize(raw: String): String = Normalizer.normalize(raw, Normalizer.Form.NFC).trim()

    /**
     * Validates a normalized nickname; null when it passes. Length is UTF-16 code units, the same
     * number iOS counts with `utf16.count` — every allowed character is a single unit.
     */
    fun validate(normalized: String): NicknameError? {
        if (normalized.length < MIN_LENGTH) return NicknameError.TOO_SHORT
        if (normalized.length > MAX_LENGTH) return NicknameError.TOO_LONG
        if (!normalized.all(::isAllowed)) return NicknameError.INVALID_CHARS
        if (containsBannedWord(lower(normalized))) return NicknameError.BANNED_WORD
        return null
    }

    /** Hangul syllables, ASCII letters, digits, `_` and `-` — the server rule `^[가-힣A-Za-z0-9_-]{2,12}$`. */
    fun isAllowed(c: Char): Boolean =
        c in '가'..'힣' || c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '_' || c == '-'

    /**
     * Duplicate-check key. Only ASCII letters among the allowed characters have case, so only A–Z is
     * folded — no locale is involved (a Turkish default locale cannot turn `I` into a dotless `ı`).
     */
    fun lower(normalized: String): String = buildString(normalized.length) {
        normalized.forEach { c -> append(if (c in 'A'..'Z') c + 32 else c) }
    }

    fun containsBannedWord(lower: String): Boolean = BANNED_WORDS.any { lower.contains(it) }

    /** Milliseconds until the next change; 0 means now (allowed from exactly 7 days on). */
    fun cooldownRemainingMs(lastChangedAtMs: Long, nowMs: Long): Long =
        maxOf(0L, lastChangedAtMs + COOLDOWN_MS - nowMs)

    /**
     * Banned words (lowercase, substring match). Nicknames are only visible to their owner for now, so
     * this is a client-side check. Same list as iOS.
     */
    val BANNED_WORDS: List<String> = listOf(
        // Impersonating the operator
        "admin", "moderator", "official", "tokenwatch",
        "운영자", "관리자", "운영팀", "공식", "토큰워치",
        // English profanity and hate
        "fuck", "shit", "bitch", "cunt", "nigger", "nigga", "faggot", "retard", "porn", "hitler", "nazi",
        // Korean profanity and hate
        "씨발", "시발", "씨빨", "좆", "존나", "병신", "븅신", "개새끼", "지랄",
        "느금", "니미", "애미", "애비", "섹스", "보지", "자지", "창녀",
    )
}

/**
 * Minimal cache for drawing the PLAYER box offline; the server is authoritative. Holds non-credential
 * data only (uid, nickname, avatar id) — never a token or an email. Key and JSON fields match iOS.
 */
data class PlayerProfileCache(
    val uid: String,
    val nickname: String,
    val avatarId: String,
    val nicknameChangedAtMs: Long,
) {
    companion object {
        const val STORAGE_KEY = "tokenwatch.player.profile.v1"
    }
}
