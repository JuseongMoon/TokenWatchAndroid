package com.ScienceFiction.TokenWatchAndroid.ui.screens

import com.ScienceFiction.TokenWatchAndroid.monster.LeaderboardScope
import com.ScienceFiction.TokenWatchAndroid.monster.MonsterStats
import com.ScienceFiction.TokenWatchAndroid.monster.MyRank
import org.junit.Assert.assertEquals
import org.junit.Test

/** The player's line in each ranks tab. */
class GuildScreenLogicTest {
    private val stats = MonsterStats(totalScore = 420, commonKills = 7, specialKills = 2)

    @Test fun eachTabShowsItsOwnRankScoreAndKills() {
        val rank = MyRank(true, 3, stats, MyRank.Week("2026-10-19", true, 1, 60, 1, 1))
        assertEquals(MineLine(true, 1, 60, 1, 1), mineLine(rank, stats, LeaderboardScope.WEEK))
        assertEquals(MineLine(true, 3, 420, 7, 2), mineLine(rank, stats, LeaderboardScope.ALL_TIME))
    }

    @Test fun unrankedThisWeekShowsNoKillsButAllTimeKeepsTheStats() {
        val rank = MyRank(false, null, stats)
        assertEquals(MineLine(false, null, 0, 0, 0), mineLine(rank, stats, LeaderboardScope.WEEK))
        assertEquals(MineLine(false, null, 0, 7, 2), mineLine(rank, stats, LeaderboardScope.ALL_TIME))
        // Before any rank was read, the defeat answer's stats stand in.
        assertEquals(MineLine(false, null, 0, 7, 2), mineLine(null, stats, LeaderboardScope.ALL_TIME))
        assertEquals(MineLine(false, null, 0, 0, 0), mineLine(null, null, LeaderboardScope.ALL_TIME))
    }
}
