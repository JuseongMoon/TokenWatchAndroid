package com.ScienceFiction.TokenWatchAndroid.ui.components

import com.ScienceFiction.TokenWatchAndroid.monster.MonsterEncounter
import com.ScienceFiction.TokenWatchAndroid.ui.components.GaugeMonsterMotion.Look
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The gauge monster's pose over time: march, sword, death animation, ghost. */
class GaugeMonsterMotionTest {
    private val tick = 285L
    private val bar = 300.0
    private val cell = 2.0
    private val columns = 9

    private fun encounter(
        source: MonsterEncounter.Source = MonsterEncounter.Source.COMPLETION,
        state: MonsterEncounter.State = MonsterEncounter.State.ALIVE,
        defeatedAtMs: Long? = null,
        position: Double = 0.5,
    ) = MonsterEncounter(
        key = "k", ownerUid = null, source = source, provider = "claude", windowLabel = "w", resetBucketMs = null,
        slotStartMs = null, monsterId = "goblin", maxHp = 3, hp = if (state == MonsterEncounter.State.ALIVE) 3 else 0,
        state = state, spawnedAtMs = 0, expiresAtMs = Long.MAX_VALUE, defeatedAtMs = defeatedAtMs, position = position,
    )

    private fun pose(e: MonsterEncounter, now: Long, lastHit: Long? = null, reduceMotion: Boolean = false) =
        GaugeMonsterMotion.pose(e, lastHit, now, bar, cell, columns, deathFrames = 4, tickMs = tick, reduceMotion = reduceMotion)

    @Test fun theMarchIsTheSlimeCritters() {
        for (t in 0 until 200) {
            assertEquals(
                GaugeCritterMath.offsetX(t, cell * 4, cell * columns, bar),
                GaugeMonsterMotion.marchX(t.toLong(), cell * 4, cell * columns, bar),
                0.0,
            )
        }
        // A wall-clock tick outgrows an Int and still lands on the bar.
        val x = GaugeMonsterMotion.marchX(1_800_000_000_000L / tick, cell * 4, cell * columns, bar)
        assertTrue(x in -cell * columns..bar)
    }

    @Test fun aLivingCompletionMonsterMarchesAndARandomOneStays() {
        val now = 1_800_000_000_000L
        val march = pose(encounter(), now)!!
        assertEquals(Look.LIVING, march.look)
        assertEquals(GaugeMonsterMotion.marchX(now / tick, cell * 4, cell * columns, bar), march.x, 0.0)
        val random = pose(encounter(source = MonsterEncounter.Source.RANDOM, position = 0.25), now)!!
        assertEquals(0.25 * (bar - cell * columns), random.x, 1e-9)
        // Motion reduced: a completion monster rests in place, on its landing frame.
        val still = pose(encounter(), now, reduceMotion = true)!!
        assertEquals(GaugeMonsterMotion.COMPLETION_REST * (bar - cell * columns), still.x, 1e-9)
        assertEquals(0, still.frame)
    }

    @Test fun aHitShowsTheSwordBriefly() {
        val now = 1_800_000_000_000L
        assertTrue(pose(encounter(), now, lastHit = now - 299)!!.sword)
        assertFalse(pose(encounter(), now, lastHit = now - 300)!!.sword)
        assertFalse(pose(encounter(), now)!!.sword)
    }

    @Test fun aDefeatHoldsTheSwordThenDiesThenWalksAsAGhost() {
        val at = 1_800_000_000_123L
        val e = encounter(state = MonsterEncounter.State.DEFEATED, defeatedAtMs = at)
        val deathX = GaugeMonsterMotion.marchX(at / tick, cell * 4, cell * columns, bar)

        val held = pose(e, at + 100)!!
        assertEquals(Look.LIVING to true, held.look to held.sword)
        assertEquals(deathX, held.x, 0.0) // where it was hit

        assertEquals(Look.DEATH to 0, pose(e, at + 300)!!.let { it.look to it.frame })
        assertEquals(Look.DEATH to 3, pose(e, at + 899)!!.let { it.look to it.frame })
        assertEquals(deathX, pose(e, at + 899)!!.x, 0.0)

        val ghost = pose(e, at + 900)!!
        assertEquals(Look.GHOST, ghost.look)
        assertTrue(ghost.halo)
        // A completion ghost marches from the bar's left edge at half speed.
        assertEquals(-cell * columns, ghost.x, 0.0)
        assertEquals(GaugeMonsterMotion.marchX(1, cell * 4, cell * columns, bar), pose(e, at + 900 + 570)!!.x, 0.0)
    }

    @Test fun aRandomGhostWalksOffTheRightEdgeForGood() {
        val at = 1_800_000_000_000L
        val e = encounter(source = MonsterEncounter.Source.RANDOM, state = MonsterEncounter.State.DEFEATED, defeatedAtMs = at, position = 0.9)
        val restX = 0.9 * (bar - cell * columns)
        assertEquals(restX, pose(e, at + 900)!!.x, 1e-9)
        assertTrue(pose(e, at + 900 + 570 * 2)!!.x > restX)
        assertNull(pose(e, at + 900 + 570L * 40))
        assertNull(pose(e, at + 180_000))
    }

    @Test fun reducedMotionShowsAStillGhostAndNothingForExpired() {
        val e = encounter(state = MonsterEncounter.State.DEFEATED, defeatedAtMs = 5)
        val still = pose(e, 10, reduceMotion = true)!!
        assertEquals(Look.GHOST to 0, still.look to still.frame)
        assertTrue(still.halo)
        assertNull(pose(encounter(state = MonsterEncounter.State.EXPIRED), 10))
    }
}
