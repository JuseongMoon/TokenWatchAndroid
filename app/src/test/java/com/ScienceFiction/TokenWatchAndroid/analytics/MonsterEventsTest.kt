package com.ScienceFiction.TokenWatchAndroid.analytics

import com.ScienceFiction.TokenWatchAndroid.monster.MonsterEncounter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MonsterEventsTest {
    @Test fun namesAndParametersMatchTheContract() {
        val spawn = AnalyticsEvent.MonsterSpawn("goblin", MonsterEncounter.Source.RANDOM)
        val kill = AnalyticsEvent.MonsterKill("mimic", hp = 12, score = 350, source = MonsterEncounter.Source.COMPLETION)
        assertEquals(listOf("monster_spawn", "monster_kill"), listOf(spawn, kill).map { it.eventName })
        assertEquals(mapOf("monster_id" to "goblin", "source" to "random"), spawn.parameters)
        assertEquals(mapOf("monster_id" to "mimic", "hp" to "12", "score" to "350", "source" to "completion"), kill.parameters)
        assertEquals(listOf("completion", "random", "event"), MonsterEncounter.Source.entries.map { it.wireId })
    }

    @Test fun monsterIdsAreCappedAndEventsStayOutOfDemoMode() {
        val long = AnalyticsEvent.MonsterSpawn("x".repeat(60), MonsterEncounter.Source.EVENT)
        assertEquals(40, long.parameters.getValue("monster_id").length)
        // Provider-scoped events are dropped entirely in demo mode.
        listOf(
            long,
            AnalyticsEvent.MonsterKill("slime", 1, 10, MonsterEncounter.Source.RANDOM),
        ).forEach { assertTrue(it.isProviderScoped) }
    }

    @Test fun theTwoScreensCarryAScreenClass() {
        assertEquals("leaderboard" to "LeaderboardScreen", ScreenName.LEADERBOARD.let { it.wireId to it.screenClass })
        assertEquals("monster_consent" to "MonsterConsentScreen", ScreenName.MONSTER_CONSENT.let { it.wireId to it.screenClass })
    }
}
