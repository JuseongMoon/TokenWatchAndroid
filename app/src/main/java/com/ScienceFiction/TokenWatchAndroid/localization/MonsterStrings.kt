package com.ScienceFiction.TokenWatchAndroid.localization

import com.ScienceFiction.TokenWatchAndroid.monster.LeaderboardScope

/*
 * Monster hunt and adventurer ranks (V2) copy. Terminal chrome (`RANKS`, `[ start ]`, `[ retry ]` …) stays
 * English and untranslated; only natural-language sentences live here. The text is the iOS original verbatim
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

// MARK: Profile bestiary

fun L10n.bestiarySummary(found: Int, total: Int, kills: Int): String =
    choose("발견 $found/$total · 총 처치 $kills", "Found $found/$total · $kills defeated")
val L10n.bestiaryHint: String
    get() = choose(
        "몬스터를 처음 잡으면 모습과 이름이 드러나요. 이 계정의 처치 기록이에요.",
        "Defeat a monster once to reveal it. These are this account's defeats.",
    )
val L10n.bestiaryLoading: String get() = choose("처치 기록을 불러오는 중…", "Loading your defeats…")
val L10n.bestiaryFailed: String get() = choose("처치 기록을 불러오지 못했어요.", "Couldn't load your defeats.")
val L10n.bestiaryUnknown: String get() = choose("아직 잡지 않은 몬스터", "Not defeated yet")
fun L10n.bestiaryA11y(name: String, kills: Int): String = choose("$name, ${kills}마리 처치", "$name, $kills defeated")

// MARK: Guild sign (top bar)

val L10n.guildBoardTitle: String get() = choose("모험가 순위", "Adventurer Ranks")
fun L10n.guildBoardGuestKills(n: Int): String = choose("이 기기에서 ${n}마리 처치", "$n defeated on this device")
val L10n.guildBoardConsent: String
    get() = choose("몬스터 사냥을 시작하려면 동의가 필요해요", "Agree to start the monster hunt")

// MARK: Leaderboard

fun L10n.leaderboardTab(scope: LeaderboardScope): String = when (scope) {
    LeaderboardScope.WEEK -> choose("이번 주", "This week")
    LeaderboardScope.ALL_TIME -> choose("전체", "All time")
}
fun L10n.leaderboardIntro(scope: LeaderboardScope): String = when (scope) {
    LeaderboardScope.WEEK -> choose(
        "이번 주에 AI 사용량 게이지에서 잡은 몬스터 점수 순위예요. 매주 월요일 0시(한국 시간)에 새로 시작하고, 동점이면 먼저 도달한 사람이 위예요.",
        "Points from monsters defeated on usage gauges this week. Starts over every Monday at 00:00 KST; ties go to whoever got there first.",
    )
    LeaderboardScope.ALL_TIME -> choose(
        "지금까지 AI 사용량 게이지에서 잡은 몬스터 점수 전체 순위예요. 동점이면 먼저 도달한 사람이 위예요.",
        "All-time points from monsters defeated on usage gauges. Ties go to whoever got there first.",
    )
}
val L10n.leaderboardLoading: String get() = choose("순위를 불러오는 중…", "loading the ranks…")
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

// MARK: Consent (terms version 2)

val L10n.consentTitle: String get() = choose("몬스터 사냥 시작하기", "Start the monster hunt")
val L10n.consentIntro: String
    get() = choose(
        "AI 사용량 게이지에 몬스터가 나타나고, 잡은 점수로 모험가 순위에 올라요. 몬스터 출현을 서버가 판정하기 때문에 아래 정보를 TokenWatch 서버로 보내요.",
        "Monsters appear on your usage gauges, and the points you earn put you in the adventurer ranks. The server decides when monsters appear, so the app sends the following to the TokenWatch server.",
    )
val L10n.consentSent: List<String>
    get() = if (lang == Lang.KO) {
        listOf(
            "AI 서비스 종류(예: Claude)와 사용량 창 이름(예: 주간)",
            "그 창의 리셋 시각과 주기",
            "몬스터 출현·처치 기록, 앱 버전과 플랫폼",
            "연결한 AI 계정마다 앱이 만든 무작위 식별값(계정 정보는 아님)",
        )
    } else {
        listOf(
            "Which AI service (e.g. Claude) and the usage window name (e.g. weekly)",
            "When that window resets and how long it is",
            "Monsters spawned and defeated, the app version and platform",
            "A random ID the app makes for each connected AI account (not the account itself)",
        )
    }
val L10n.consentNotSent: String
    get() = choose(
        "사용률 수치, AI 서비스 계정·이메일·토큰은 보내지 않아요. 닉네임 #번호, 아바타, 점수와 처치 수는 모험가 순위에서 다른 사람에게 보여요.",
        "Usage percentages and your AI service accounts, emails, and tokens are never sent. Your nickname #number, avatar, score, and defeat counts are shown to others in the adventurer ranks.",
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
