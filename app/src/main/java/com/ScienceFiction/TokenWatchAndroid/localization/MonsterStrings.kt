package com.ScienceFiction.TokenWatchAndroid.localization

import com.ScienceFiction.TokenWatchAndroid.monster.MonsterApiClient

/*
 * Monster hunt and guild (V2) copy. Terminal chrome (`GUILD`, `[ start ]`, `[ report ]` …) stays English
 * and untranslated; only natural-language sentences live here. The text is the iOS original verbatim
 * (`L10n+Monster.swift`), so both platforms read the same.
 */

// MARK: Monster names (catalog id → display name; an unknown id shows as itself)

fun L10n.monsterName(id: String): String = when (id) {
    "slime" -> choose("슬라임", "Slime")
    "goblin" -> choose("고블린", "Goblin")
    "skeleton" -> choose("스켈레톤", "Skeleton")
    "orc" -> choose("오크", "Orc")
    "mimic" -> choose("미믹", "Mimic")
    else -> id
}

// MARK: Gauge combat (accessibility)

val L10n.monsterAttack: String get() = choose("공격", "Attack")
fun L10n.monsterA11yValue(name: String, hp: Int, maxHp: Int): String =
    choose("$name, 남은 체력 $hp/$maxHp", "$name, $hp of $maxHp HP left")
fun L10n.monsterA11yDefeated(name: String): String = choose("$name 처치됨", "$name defeated")

// MARK: Guild sign (top bar)

val L10n.guildBoardTitle: String get() = choose("모험가 길드 순위", "Adventurers' Guild")
fun L10n.guildBoardGuestKills(n: Int): String = choose("이 기기에서 ${n}마리 처치", "$n defeated on this device")
val L10n.guildBoardConsent: String
    get() = choose("몬스터 사냥을 시작하려면 동의가 필요해요", "Agree to start the monster hunt")

// MARK: Leaderboard

val L10n.leaderboardIntro: String
    get() = choose(
        "AI 사용량 게이지에 나타난 몬스터를 잡은 점수 순위예요. 동점이면 먼저 도달한 사람이 위예요.",
        "Ranked by points from monsters defeated on usage gauges. Ties go to whoever got there first.",
    )
val L10n.leaderboardLoading: String get() = choose("순위를 불러오는 중…", "loading the guild ranks…")
val L10n.leaderboardFailed: String
    get() = choose(
        "순위를 불러오지 못했어요. 잠시 뒤 다시 시도해 주세요.",
        "Couldn't load the ranks. Try again in a moment.",
    )
val L10n.leaderboardEmpty: String get() = choose("아직 순위에 오른 모험가가 없어요.", "No adventurers ranked yet.")
val L10n.leaderboardGuestHint: String
    get() = choose(
        "TokenWatch 계정으로 로그인하고 몬스터 사냥에 동의하면 이 순위에 올라요. 로그인 전에 잡은 몬스터는 순위 없이 기록으로만 합쳐져요.",
        "Sign in to a TokenWatch account and agree to the monster hunt to appear here. Monsters defeated before signing in are kept as unranked history.",
    )
fun L10n.leaderboardMine(common: Int, special: Int): String =
    choose("슬라임 $common · 특수 $special", "slimes $common · special $special")
val L10n.leaderboardNotListed: String get() = choose("아직 순위에 없어요", "not ranked yet")
val L10n.leaderboardBlockedNote: String
    get() = choose("차단한 모험가는 이 기기에서 보이지 않아요.", "Blocked adventurers are hidden on this device.")

// MARK: Report and block

fun L10n.reportTitle(nametag: String): String = choose("$nametag 신고", "Report $nametag")
val L10n.reportIntro: String
    get() = choose(
        "신고 사유를 고르세요. 여러 사람이 신고하면 순위에서 숨겨지고 운영자가 검토해요.",
        "Pick a reason. When several people report someone, they're hidden from the ranks until an operator reviews it.",
    )
fun L10n.reportReason(reason: MonsterApiClient.ReportReason): String = when (reason) {
    MonsterApiClient.ReportReason.NICKNAME -> choose("부적절한 닉네임", "Inappropriate nickname")
    MonsterApiClient.ReportReason.AVATAR -> choose("부적절한 아바타", "Inappropriate avatar")
    MonsterApiClient.ReportReason.CHEATING -> choose("부정행위 의심", "Suspected cheating")
    MonsterApiClient.ReportReason.OTHER -> choose("기타", "Other")
}
val L10n.reportSent: String get() = choose("신고했어요. 검토할게요.", "Report sent. We'll take a look.")
val L10n.reportFailed: String get() = choose("신고를 보내지 못했어요. 다시 시도해 주세요.", "Couldn't send the report. Try again.")
val L10n.reportNeedsAccount: String get() = choose("신고는 로그인한 계정만 할 수 있어요.", "Sign in to report.")
fun L10n.blockTitle(nametag: String): String = choose("$nametag 차단", "Block $nametag")
val L10n.blockMessage: String
    get() = choose(
        "이 기기의 순위 목록에서 이 모험가를 숨겨요. 언제든 다시 보이게 할 수 있어요.",
        "Hide this adventurer from the ranks on this device. You can undo it anytime.",
    )

// MARK: Consent (terms version 2)

val L10n.consentTitle: String get() = choose("몬스터 사냥 시작하기", "Start the monster hunt")
val L10n.consentIntro: String
    get() = choose(
        "AI 사용량 게이지에 몬스터가 나타나고, 잡은 점수로 길드 순위에 올라요. 몬스터 출현을 서버가 판정하기 때문에 아래 정보를 TokenWatch 서버로 보내요.",
        "Monsters appear on your usage gauges, and the points you earn rank you in the guild. The server decides when monsters appear, so the app sends the following to the TokenWatch server.",
    )
val L10n.consentSent: List<String>
    get() = if (lang == Lang.KO) {
        listOf(
            "AI 서비스 종류(예: Claude)와 사용량 창 이름(예: 주간)",
            "그 창의 리셋 시각과 주기",
            "몬스터 출현·처치 기록, 앱 버전과 플랫폼",
        )
    } else {
        listOf(
            "Which AI service (e.g. Claude) and the usage window name (e.g. weekly)",
            "When that window resets and how long it is",
            "Monsters spawned and defeated, the app version and platform",
        )
    }
val L10n.consentNotSent: String
    get() = choose(
        "사용률 수치, AI 서비스 계정·이메일·토큰은 보내지 않아요. 닉네임 #번호와 아바타, 점수는 길드 순위에서 다른 사람에게 보여요.",
        "Usage percentages and your AI service accounts, emails, and tokens are never sent. Your nickname #number, avatar, and score are shown to others in the guild ranks.",
    )
val L10n.consentAgree: String
    get() = choose(
        "변경된 이용약관과 개인정보 처리방침에 동의합니다.",
        "I agree to the updated Terms of Service and Privacy Policy.",
    )
val L10n.consentFailed: String
    get() = choose(
        "동의를 저장하지 못했어요. 네트워크를 확인하고 다시 시도해 주세요.",
        "Couldn't save your agreement. Check your connection and try again.",
    )
val L10n.consentLater: String
    get() = choose(
        "동의하지 않아도 사용량 기능은 그대로 쓸 수 있고, 몬스터는 이 기기에서만 순위 없이 기록돼요.",
        "You can keep using every usage feature without agreeing; monsters are then kept only on this device, unranked.",
    )
