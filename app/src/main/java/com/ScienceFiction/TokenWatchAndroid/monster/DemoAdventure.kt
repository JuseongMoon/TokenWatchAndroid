package com.ScienceFiction.TokenWatchAndroid.monster

import com.ScienceFiction.TokenWatchAndroid.domain.account.AccountMethod
import com.ScienceFiction.TokenWatchAndroid.domain.account.NicknamePolicy
import com.ScienceFiction.TokenWatchAndroid.domain.account.PlayerProfile

/*
 * Demo mode's sample adventurer (the PLAYER box, the account screen, the adventurer ranks and the bestiary),
 * mirroring iOS `DemoAdventure`. Constants in memory only, so nothing is asked of the server or stored. A
 * signed-in account stays out of sight during the demo (demo isolation). The values are the same as iOS.
 */
object DemoAdventure {
    const val UID = "demo"
    val METHODS: List<AccountMethod> = listOf(AccountMethod.APPLE)

    fun profile(korean: Boolean): PlayerProfile {
        val nickname = if (korean) "토큰사냥꾼" else "TokenHunter"
        return PlayerProfile(
            nickname = nickname,
            nicknameLower = NicknamePolicy.lower(nickname),
            tag = "0427",
            nicknameChangedAtMs = 0,
            avatarId = "mage",
            termsAcceptedVersion = PlayerProfile.CURRENT_TERMS_VERSION,
            createdAtMs = 0,
            updatedAtMs = 0,
        )
    }

    /**
     * Bestiary defeats: caught up to the goblin, so the skeleton, the orc and the mimic show as silhouettes. Their
     * points add up to the all-time score, 780.
     */
    val KILLS: Map<String, Int> = mapOf("slime" to 48, "goblin" to 6)

    /** The player's line in a tab. */
    data class Line(val rank: Int, val score: Int, val commonKills: Int, val specialKills: Int)

    /** This week #4, all time #6. */
    fun mine(scope: LeaderboardScope): Line = when (scope) {
        LeaderboardScope.WEEK -> Line(rank = 4, score = 300, commonKills = 15, specialKills = 3)
        LeaderboardScope.ALL_TIME -> Line(rank = 6, score = 780, commonKills = 48, specialKills = 6)
    }

    private class Person(val ko: String, val en: String, val tag: String, val avatar: String, val week: Int, val total: Int)

    /** A tab's top 10. Of the 11 sample adventurers (the player included), one per tab falls outside it. */
    fun leaderboard(scope: LeaderboardScope, korean: Boolean): List<LeaderboardEntry> {
        val me = profile(korean)
        val people = listOf(
            Person("야간당직", "nightshift", "3141", "rogue", 486, 905),
            Person("커피세잔", "triple_shot", "7720", "knight", 412, 1240),
            Person("버그잡이", "bugcatcher", "0815", "hacker", 365, 1105),
            Person(me.nickname, me.nickname, me.tag, me.avatarId, mine(LeaderboardScope.WEEK).score, mine(LeaderboardScope.ALL_TIME).score),
            Person("슬라임킬러", "slimeslayer", "5562", "warrior", 251, 980),
            Person("리셋대기", "resetwatch", "2048", "cleric", 207, 840),
            Person("pixel_bear", "pixel_bear", "9013", "cat", 168, 610),
            Person("금요배포", "deploy_fri", "4404", "robot", 132, 532),
            Person("모닝커밋", "morning_git", "1206", "bard", 96, 455),
            Person("고양이개발", "catdev", "6378", "archer", 71, 390),
            Person("한도초과", "over_quota", "8291", "dragon", 40, 352),
        )
        return people.mapIndexed { index, p ->
            val isMe = p.tag == me.tag
            val score = if (scope == LeaderboardScope.WEEK) p.week else p.total
            val special = if (isMe) mine(scope).specialKills else score / 200
            LeaderboardEntry(
                uid = if (isMe) UID else "demo-$index",
                nickname = if (korean) p.ko else p.en,
                tag = p.tag,
                avatarId = p.avatar,
                score = score,
                reachedAtMs = index.toLong(),
                commonKills = if (isMe) mine(scope).commonKills else maxOf(0, (score - special * 75) / 10),
                specialKills = special,
            )
        }.sortedByDescending { it.score }.take(MonsterApiClient.LEADERBOARD_SIZE)
    }
}
