package com.ScienceFiction.TokenWatchAndroid.localization

import com.ScienceFiction.TokenWatchAndroid.domain.UsageWindow
import com.ScienceFiction.TokenWatchAndroid.domain.WindowKind
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalizationTest {
    private val now = Instant.ofEpochSecond(1_000_000)

    @Test
    fun appLanguageResolves() {
        assertEquals(Lang.KO, AppLanguage.KOREAN.resolved("en-US"))
        assertEquals(Lang.EN, AppLanguage.ENGLISH.resolved("ko-KR"))
        assertEquals(Lang.KO, AppLanguage.SYSTEM.resolved("ko-KR"))
        assertEquals(Lang.EN, AppLanguage.SYSTEM.resolved("ja-JP"))
    }

    @Test
    fun catalogDiffersByLanguage() {
        val ko = L10n(Lang.KO)
        val en = L10n(Lang.EN)
        assertEquals("로그아웃", ko.logout)
        assertEquals("Log out", en.logout)
        assertNotEquals(ko.settingsLanguageHelp, en.settingsLanguageHelp)
        assertEquals("↑ 시간 대비 12%p 빠름", ko.paceAhead(12))
        assertEquals("↑ 12%p ahead of pace", en.paceAhead(12))
        assertEquals("사용량 조회 실패 (HTTP 429).", ko.errHttp(429))
        assertEquals("Failed to fetch usage (HTTP 429).", en.errHttp(429))
        assertEquals(
            "요청이 많아 대기 중입니다. 약 4분 후 재시도 · 아래 그래프는 갱신되지 않습니다.",
            ko.errRateLimitedRetryStale(4),
        )
        assertEquals(
            "Too many requests. Retrying in ~4 min · usage below isn't updating.",
            en.errRateLimitedRetryStale(4),
        )
        assertEquals("42% 사용", ko.a11yUsed(42))
        assertEquals("42% used", en.a11yUsed(42))
    }

    @Test
    fun depletionEtaLocalized() {
        val ko = L10n(Lang.KO)
        val en = L10n(Lang.EN)
        assertEquals("약 1일 2시간 후", ko.depletionEta(1, 2, 3))
        assertEquals("in ~1d 2h", en.depletionEta(1, 2, 3))
        assertEquals("약 1분 후", ko.depletionEta(0, 0, 0))
        assertEquals("in ~1m", en.depletionEta(0, 0, 0))
    }

    @Test
    fun resetSummaryLocalized() {
        val window = UsageWindow(
            label = "s",
            usedPercent = 20.0,
            resetsAt = now.plusSeconds(2 * 3_600),
            kind = WindowKind.SESSION,
            windowSeconds = 5.0 * 3_600,
        )
        val ko = window.resetSummary(L10n(Lang.KO), now, ZoneOffset.UTC)
        val en = window.resetSummary(L10n(Lang.EN), now, ZoneOffset.UTC)
        assertTrue(ko.startsWith("리셋"))
        assertTrue(ko.contains("남음"))
        assertTrue(en.startsWith("resets"))
        assertTrue(en.contains("left"))

        val past = window.copy(resetsAt = now.minusSeconds(100))
        assertEquals("리셋됨", past.resetSummary(L10n(Lang.KO), now, ZoneOffset.UTC))
        assertEquals("reset", past.resetSummary(L10n(Lang.EN), now, ZoneOffset.UTC))
    }

    @Test
    fun storedLanguageFallsBackToSystem() {
        assertEquals(AppLanguage.KOREAN, AppLanguage.fromWireId("korean"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromWireId("english"))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromWireId("unknown"))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromWireId(null))
    }
}
