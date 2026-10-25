package com.ScienceFiction.TokenWatchAndroid.localization

import com.ScienceFiction.TokenWatchAndroid.domain.UsageWindow
import com.ScienceFiction.TokenWatchAndroid.domain.WindowKind
import com.ScienceFiction.TokenWatchAndroid.monster.LeaderboardScope
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
        assertEquals(
            "auto: 사용량이 빠르게 오르면 간격을 줄이고, 멈추면 늘립니다(10초~5분). 현재 60s",
            ko.settingsRefreshAutoHelp("60s"),
        )
        assertEquals(
            "auto: shortens the interval while usage climbs and relaxes it when idle (10s–5m). now 60s",
            en.settingsRefreshAutoHelp("60s"),
        )
        assertEquals("58% 남음", ko.a11yRemaining(58))
        assertEquals("58% left", en.a11yRemaining(58))
        assertEquals("총액은 관측된 최고 잔액 기준 추정", ko.creditApproxNote)
        assertEquals("total estimated from highest observed balance", en.creditApproxNote)
        assertEquals("[재설정]", ko.creditResetButton)
        assertEquals("[reset]", en.creditResetButton)
        assertEquals("게이지 기준 재설정", ko.creditResetTitle)
        assertEquals("Reset gauge scale", en.creditResetTitle)
        assertEquals("재설정", ko.creditResetConfirm)
        assertEquals("Reset", en.creditResetConfirm)
        assertTrue(ko.creditResetMessage.contains("100%(가득)"))
        assertTrue(en.creditResetMessage.contains("100% (full)"))
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

    @Test
    fun resetNotificationsAreLocalizedAndAccountAware() {
        val ko = L10n(Lang.KO)
        val en = L10n(Lang.EN)
        assertEquals("Codex · dev@example.com", ko.notifResetTitle("Codex", "dev@example.com"))
        assertEquals("Codex", ko.notifResetTitle("Codex", null))
        assertEquals("세션 한도가 리셋되었습니다. 다시 사용할 수 있어요.", ko.notifResetBody(true, false))
        assertEquals(
            "Your weekly limit has reset — you're good to go.",
            en.notifResetBody(false, true),
        )
        assertTrue(ko.settingsNotifOpenSettings.contains("Android"))
    }

    @Test
    fun accountCopyExistsInBothLanguages() {
        val ko = L10n(Lang.KO)
        val en = L10n(Lang.EN)
        com.ScienceFiction.TokenWatchAndroid.domain.account.NicknameError.entries.forEach { error ->
            assertTrue(ko.nicknameError(error).isNotBlank())
            assertTrue(en.nicknameError(error).isNotBlank())
            assertNotEquals(ko.nicknameError(error), en.nicknameError(error))
        }
        val notices = listOf(
            com.ScienceFiction.TokenWatchAndroid.account.AccountNotice.LoginFailed,
            com.ScienceFiction.TokenWatchAndroid.account.AccountNotice.GoogleUnavailable,
            com.ScienceFiction.TokenWatchAndroid.account.AccountNotice.ExistsWithDifferentMethod,
            com.ScienceFiction.TokenWatchAndroid.account.AccountNotice.AlreadyInUse,
            com.ScienceFiction.TokenWatchAndroid.account.AccountNotice.LinkFailed,
            com.ScienceFiction.TokenWatchAndroid.account.AccountNotice.Linked(
                com.ScienceFiction.TokenWatchAndroid.domain.account.AccountMethod.GOOGLE,
            ),
            com.ScienceFiction.TokenWatchAndroid.account.AccountNotice.SaveFailed,
            com.ScienceFiction.TokenWatchAndroid.account.AccountNotice.DeleteFailed,
            com.ScienceFiction.TokenWatchAndroid.account.AccountNotice.RevokeFailed,
        )
        notices.forEach { notice ->
            assertTrue(ko.accountNotice(notice).isNotBlank())
            assertNotEquals(ko.accountNotice(notice), en.accountNotice(notice))
        }
        // Verbatim iOS copy (the two platforms read the same).
        assertEquals("이 이메일은 다른 방법으로 가입돼 있어요. 그 방법으로 로그인한 뒤 연결하세요.", ko.accountNotice(notices[2]))
        assertEquals(
            "This sign-in is tied to another TokenWatch account. Delete that account to link it here.",
            en.accountNotice(notices[3]),
        )
        assertEquals("Google 로그인을 연결했어요.", ko.accountNotice(notices[5]))
        assertEquals("다음 변경까지 2일 3시간", ko.nicknameCooldownRemaining(2, 3))
        assertEquals("next change in 1h", en.nicknameCooldownRemaining(0, 0))
    }

    @Test
    fun nametagCopyMatchesIos() {
        val ko = L10n(Lang.KO)
        val en = L10n(Lang.EN)
        val taken = com.ScienceFiction.TokenWatchAndroid.domain.account.NicknameError.TAKEN
        val cooldown = com.ScienceFiction.TokenWatchAndroid.domain.account.NicknameError.COOLDOWN
        assertEquals("이 닉네임은 쓸 수 있는 #번호가 남지 않았어요. 다른 닉네임을 써 주세요.", ko.nicknameError(taken))
        assertEquals("You can change your nickname once every 30 days.", en.nicknameError(cooldown))
        assertEquals(
            "다른 사용자에게 보일 닉네임과 아바타를 골라 주세요. 닉네임 뒤에는 구분용 #번호(예: #8571)가 붙어요. " +
                "아바타는 언제든, 닉네임은 30일에 한 번 바꿀 수 있어요.",
            ko.profileSetupIntro,
        )
        assertEquals("닉네임을 바꾸면 #번호도 새로 정해져요. 30일에 한 번 바꿀 수 있어요.", ko.nicknameTagHint)
        assertEquals(
            "Operator accounts always appear with the operator avatar, whatever you pick here.",
            en.operatorAvatarNote,
        )
        assertNotEquals(ko.operatorAvatarNote, en.operatorAvatarNote)
        assertNotEquals(ko.nicknameTagHint, en.nicknameTagHint)
    }

    @Test
    fun accountSwitchCopyMatchesIos() {
        val ko = L10n(Lang.KO)
        val en = L10n(Lang.EN)
        assertEquals(
            "몬스터 사냥을 위해 AI 서비스 종류·사용량 창 이름·리셋 시각과 처치 기록을 서버로 보내요. 사용률 수치와 AI 서비스 계정 정보는 보내지 않아요. " +
                "닉네임 #번호, 아바타, 점수와 처치 수는 모험가 순위에 공개돼요.",
            ko.termsMonsterNote,
        )
        assertEquals(
            "For the monster hunt, the app sends the AI service type, usage window name, reset time, and defeat records to the server. " +
                "Usage percentages and AI service account details are never sent. Your nickname #number, avatar, score, and defeat counts appear in the adventurer ranks.",
            en.termsMonsterNote,
        )
        assertEquals(
            "닉네임·아바타·몬스터 기록과 모험가 순위·로그인 연결이 서버에서 지워지고 되돌릴 수 없어요. 확인을 위해 한 번 더 로그인해요. 위 ACCOUNTS의 AI 서비스 계정은 영향을 받지 않아요.",
            ko.accountDeleteMessage,
        )
        assertEquals(
            "Your nickname, avatar, monster records, adventurer rank, and sign-in links will be erased from the server. This can't be undone. " +
                "You'll sign in once more to confirm. Your AI service accounts under ACCOUNTS are not affected.",
            en.accountDeleteMessage,
        )
    }

    @Test
    fun monsterCopyMatchesIos() {
        val ko = L10n(Lang.KO)
        val en = L10n(Lang.EN)
        assertEquals("모험가 순위" to "Adventurer Ranks", ko.guildBoardTitle to en.guildBoardTitle)
        assertEquals("이번 주" to "All time", ko.leaderboardTab(LeaderboardScope.WEEK) to en.leaderboardTab(LeaderboardScope.ALL_TIME))
        assertEquals(
            "이번 주에 AI 사용량 게이지에서 잡은 몬스터 점수 순위예요. 매주 월요일 0시(한국 시간)에 새로 시작하고, 동점이면 먼저 도달한 사람이 위예요.",
            ko.leaderboardIntro(LeaderboardScope.WEEK),
        )
        assertEquals(
            "All-time points from monsters defeated on usage gauges. Ties go to whoever got there first.",
            en.leaderboardIntro(LeaderboardScope.ALL_TIME),
        )
        assertEquals("loading the ranks…", en.leaderboardLoading)
        assertEquals(
            "Monsters appear on your usage gauges, and the points you earn put you in the adventurer ranks. " +
                "The server decides when monsters appear, so the app sends the following to the TokenWatch server.",
            en.consentIntro,
        )
        assertEquals("이 기기에서 3마리 처치", ko.guildBoardGuestKills(3))
        assertEquals("고블린, 남은 체력 2/3", ko.monsterA11yValue(ko.monsterName("goblin"), 2, 3))
        assertEquals("Goblin, 2 of 3 HP left", en.monsterA11yValue(en.monsterName("goblin"), 2, 3))
        assertEquals("pumpkin", en.monsterName("pumpkin"))
        assertEquals("공격" to "Attack", ko.monsterAttack to en.monsterAttack)
        assertEquals("슬라임 7 · 특수 2", ko.leaderboardMine(7, 2))
        assertEquals(4, ko.consentSent.size)
        assertEquals("그 창의 리셋 시각과 주기", ko.consentSent[1])
        // The random draw sends a per-account hash of each AI account's on-device ID, so the list names it.
        assertEquals("연결한 AI 계정마다 앱이 만든 무작위 식별값(계정 정보는 아님)", ko.consentSent[3])
        assertEquals("A random ID the app makes for each connected AI account (not the account itself)", en.consentSent[3])
        assertEquals("발견 2/5 · 총 처치 17" to "Found 2/5 · 17 defeated", ko.bestiarySummary(2, 5, 17) to en.bestiarySummary(2, 5, 17))
        assertEquals("슬라임, 4마리 처치" to "Slime, 4 defeated", ko.bestiaryA11y("슬라임", 4) to en.bestiaryA11y("Slime", 4))
        // The public part names the defeat counts too: the leaderboard document carries them.
        assertEquals(
            "사용률 수치, AI 서비스 계정·이메일·토큰은 보내지 않아요. 닉네임 #번호, 아바타, 점수와 처치 수는 모험가 순위에서 다른 사람에게 보여요.",
            ko.consentNotSent,
        )
        assertEquals(
            "Usage percentages and your AI service accounts, emails, and tokens are never sent. " +
                "Your nickname #number, avatar, score, and defeat counts are shown to others in the adventurer ranks.",
            en.consentNotSent,
        )
        assertEquals(
            "Monsters appear on gauges that hit 100%, and now and then on gauges in use. Tap the gauge row to fight them. " +
                "Turning this off also stops monsters from appearing.",
            en.settingsGaugeCritterHelp,
        )
        assertNotEquals(ko.consentIntro, en.consentIntro)
    }
}
