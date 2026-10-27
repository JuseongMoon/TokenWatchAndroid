package com.ScienceFiction.TokenWatchAndroid.monster

import com.ScienceFiction.TokenWatchAndroid.domain.AgentProvider
import com.ScienceFiction.TokenWatchAndroid.domain.UsageStyle
import com.ScienceFiction.TokenWatchAndroid.domain.UsageWindow
import com.ScienceFiction.TokenWatchAndroid.domain.WindowKind
import com.ScienceFiction.TokenWatchAndroid.localization.L10n
import com.ScienceFiction.TokenWatchAndroid.localization.Lang
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/**
 * What a monster spawn request is made of (reset bucket, period, window label, agent key), computed bit for
 * bit like iOS `MonsterSessionKey`. The server document key uses the same formula (SHA-256 hex), so the
 * local match reuses it. Pure — unit tested against the iOS constants.
 *
 * - Reset times round to 300-second buckets, so sub-second jitter keeps one session on one key.
 * - Copilot only reports a date: the key uses that date's UTC midnight, not the device-midnight
 *   `resetsAt` shown on screen.
 * - Codex's fallback label for an additional limit follows the app language, so the key swaps it for
 *   a language-free constant.
 * - The period (`periodSec`) is `windowSeconds`, then the monthly table, then the window kind default.
 */
object MonsterSessionKey {
    /** Reset time rounding unit (300 s). */
    const val BUCKET_MS = 300_000L

    /** Monthly window period (31 days, seconds). */
    const val MONTH_SECONDS = 31L * 86_400L

    /** Codex additional-limit label used in keys (display keeps the localized text). */
    const val CODEX_ADDITIONAL_LIMIT = "Additional limit"

    /** Every language's fallback label for a Codex additional limit. */
    private val codexFallbackLabels: Set<String> by lazy { Lang.entries.map { L10n(it).codexAdditionalLimit }.toSet() }

    /** The window label used in keys. */
    fun keyLabel(provider: AgentProvider, label: String): String =
        if (provider == AgentProvider.CODEX && label in codexFallbackLabels) CODEX_ADDITIONAL_LIMIT else label

    /** The reset time used in keys (epoch ms). Copilot uses the UTC midnight of the device-zone date. */
    fun keyResetsAtMs(provider: AgentProvider, resetsAt: Instant, zone: ZoneId = ZoneId.systemDefault()): Long {
        if (provider == AgentProvider.COPILOT) {
            return resetsAt.atZone(zone).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        }
        return epochMs(resetsAt)
    }

    /** Rounds to the nearest 300 s (positive epoch ms). */
    fun resetBucketMs(ms: Long): Long = (ms + BUCKET_MS / 2) / BUCKET_MS * BUCKET_MS

    /** The window period in seconds, sent for the server's consistency checks. */
    fun periodSec(provider: AgentProvider, label: String, windowSeconds: Double?, kind: WindowKind): Long {
        if (windowSeconds != null && windowSeconds > 0.0) return Math.round(windowSeconds)
        return when {
            provider == AgentProvider.COPILOT -> MONTH_SECONDS
            provider == AgentProvider.KIMI && (label == "Current month" || label == "Current month (Code)") ->
                MONTH_SECONDS
            provider == AgentProvider.ELEVENLABS && label == "Monthly characters" -> MONTH_SECONDS
            else -> kind.defaultSeconds.toLong()
        }
    }

    // MARK: Keys

    /** Device session ledger key, independent of the account. */
    fun completionLedgerKey(provider: AgentProvider, resetBucketMs: Long): String = "${provider.wireId}|$resetBucketMs"

    /** The same hash as the server encounter document key. Guests use `guest` for the uid (local only). */
    fun completionKey(uid: String, provider: AgentProvider, resetBucketMs: Long): String =
        sha256Hex("$uid|${provider.wireId}|$resetBucketMs")

    /** The random spawn key: the agent key and the draw's due time. Guests use `guest` and the agent token. */
    fun randomKey(uid: String, agentKey: String, drawAtMs: Long): String = sha256Hex("$uid|rand|$agentKey|$drawAtMs")

    /** An agent as the device ledger writes it (the lowercase UUID — iOS lowercases to match). */
    fun agentToken(id: UUID): String = id.toString()

    /** The agent key sent to the server: different per account, so accounts never link, and no AI account data. */
    fun agentKey(uid: String, agentToken: String): String = sha256Hex("$uid|agent|$agentToken")

    fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** Epoch milliseconds rounded to the nearest millisecond, as iOS rounds its `Date`. */
    fun epochMs(instant: Instant): Long = instant.epochSecond * 1_000L + (instant.nano + 500_000) / 1_000_000
}

/** Whether a window can carry a monster. Usage figures never leave the device. */
object MonsterEligibility {
    /** Completion threshold — the same value as the gauge critter. */
    const val COMPLETION_THRESHOLD = 0.995

    /** A subscription gauge with a future reset (credit gauges and balances are excluded). */
    fun isTarget(window: UsageWindow, now: Instant): Boolean {
        if (window.style != UsageStyle.GAUGE) return false
        val resetsAt = window.resetsAt ?: return false
        return resetsAt.isAfter(now)
    }

    fun isCompleted(window: UsageWindow, now: Instant): Boolean =
        isTarget(window, now) && window.usedPercent / 100.0 >= COMPLETION_THRESHOLD

    /**
     * Random spawn candidates. A window at 100% is one too, so draws go on until the reset even when the
     * usage ran out early. The caller ([MonsterStore]) sifts out bars with a living monster or a ghost from the
     * last hour.
     */
    fun isRandomCandidate(window: UsageWindow, minUsedPercent: Double, now: Instant): Boolean =
        isTarget(window, now) && window.usedPercent >= minUsedPercent

    /**
     * What marks agents that look like one AI account (used on the device only). The same account added twice
     * shows the same label, reset time and usage on every target window, so the random draw takes only one of
     * them; different accounts part on the numbers.
     */
    fun accountSignature(provider: AgentProvider, windows: List<UsageWindow>, now: Instant): String {
        val parts = windows.mapNotNull { window ->
            val resetsAt = window.resetsAt
            if (!isTarget(window, now) || resetsAt == null) return@mapNotNull null
            val reset = MonsterSessionKey.resetBucketMs(MonsterSessionKey.keyResetsAtMs(provider, resetsAt))
            "${window.label}|$reset|${Math.round(window.usedPercent)}"
        }
        return (listOf(provider.wireId) + parts.sorted()).joinToString("\n")
    }
}
