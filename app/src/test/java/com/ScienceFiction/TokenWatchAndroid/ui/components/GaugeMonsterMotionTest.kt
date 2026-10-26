package com.ScienceFiction.TokenWatchAndroid.ui.components

import com.ScienceFiction.TokenWatchAndroid.monster.MonsterDraw
import com.ScienceFiction.TokenWatchAndroid.monster.MonsterEncounter
import com.ScienceFiction.TokenWatchAndroid.ui.components.GaugeMonsterMotion.Look
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.floor

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
        spawnedAtMs: Long = 0,
    ) = MonsterEncounter(
        key = "k", ownerUid = null, source = source, provider = "claude", windowLabel = "w", resetBucketMs = null,
        drawAtMs = null, monsterId = "goblin", maxHp = 3, hp = if (state == MonsterEncounter.State.ALIVE) 3 else 0,
        state = state, spawnedAtMs = spawnedAtMs, expiresAtMs = Long.MAX_VALUE, defeatedAtMs = defeatedAtMs, position = position,
    )

    private fun pose(e: MonsterEncounter, now: Long, lastHit: Long? = null, reduceMotion: Boolean = false) =
        GaugeMonsterMotion.pose(e, lastHit, now, bar, cell, columns, deathFrames = 4, tickMs = tick, reduceMotion = reduceMotion)

    // A random monster with key "k" walks at its own pace.
    private val randomPeriod = tick / MonsterDraw.marchSpeed("k")

    private fun randomMarchX(at: Long, spawnedAt: Long, position: Double) = GaugeMonsterMotion.marchX(
        floor(at / randomPeriod).toLong(), floor(spawnedAt / randomPeriod).toLong(),
        position * (bar - cell * columns), cell * 4, cell * columns, bar,
    )

    // hop 10, body 20, bar 100 → 12 steps a lap. The start 38 snaps to the hop grid at x = 40.
    private fun midX(tick: Long, start: Long, startX: Double = 38.0) =
        GaugeMonsterMotion.marchX(tick, start, startX, hop = 10.0, spriteWidth = 20.0, barWidth = 100.0)

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

    @Test fun aMidSpawnStartsAtItsSpot() {
        assertEquals(40.0, midX(1000, start = 1000), 0.0)
        assertEquals(40.0, midX(1001, start = 1001), 0.0)
    }

    @Test fun aMidSpawnKeepsTheJumpRhythm() {
        // Even start tick: a step on the next odd tick, none on the even one.
        assertEquals(50.0, midX(1001, start = 1000), 0.0)
        assertEquals(50.0, midX(1002, start = 1000), 0.0)
        assertEquals(60.0, midX(1003, start = 1000), 0.0)
        // Odd start tick: the next even tick is a landing, the odd one after it a step.
        assertEquals(40.0, midX(1002, start = 1001), 0.0)
        assertEquals(50.0, midX(1003, start = 1001), 0.0)
    }

    @Test fun aMidSpawnWrapsToTheLeftEdge() {
        assertEquals(90.0, midX(1010, start = 1000), 0.0) // the last step: the bar's right end
        assertEquals(-20.0, midX(1011, start = 1000), 0.0) // back in from the left
        assertEquals(-10.0, midX(1013, start = 1000), 0.0)
    }

    @Test fun aMidSpawnHoldsBeforeItsStartTick() {
        // A device clock behind the server's spawn time keeps it at the start.
        assertEquals(40.0, midX(990, start = 1000), 0.0)
    }

    @Test fun aMidSpawnStaysInsideTheBar() {
        for (t in 1000L..1300L) {
            val x = GaugeMonsterMotion.marchX(t, 1000, 180.0, hop = 7.0, spriteWidth = 15.4, barWidth = 250.0)
            assertTrue(x >= -15.4 && x < 250.0)
        }
        assertEquals(-20.0, GaugeMonsterMotion.marchX(5, 0, 30.0, hop = 0.0, spriteWidth = 20.0, barWidth = 100.0), 0.0)
        // A wall-clock tick outgrows an Int and still lands on the bar.
        val x = GaugeMonsterMotion.marchX(1_800_000_000_000L / tick, 1_799_999_000_000L / tick, 70.5, cell * 4, cell * columns, bar)
        assertTrue(x in -cell * columns..bar)
    }

    @Test fun aLivingMonsterMarchesAndARandomOneSetsOutFromItsSpot() {
        val now = 1_800_000_000_000L
        val march = pose(encounter(), now)!!
        assertEquals(Look.LIVING, march.look)
        assertEquals(GaugeMonsterMotion.marchX(now / tick, cell * 4, cell * columns, bar), march.x, 0.0)
        // At its spawn a random monster stands on its spot (0.25 × 282 = 70.5, snapped to the 8-wide grid).
        val random = encounter(source = MonsterEncounter.Source.RANDOM, position = 0.25, spawnedAtMs = now)
        assertEquals(70.0, pose(random, now)!!.x, 1e-9)
        // Later it has walked on at its own pace.
        val later = now + 60_000
        assertEquals(randomMarchX(later, spawnedAt = now, position = 0.25), pose(random, later)!!.x, 0.0)
        assertEquals((floor(later / randomPeriod).toLong() % 2L).toInt(), pose(random, later)!!.frame)
        // Motion reduced: both rest in place, on their landing frame.
        val still = pose(encounter(), now, reduceMotion = true)!!
        assertEquals(GaugeMonsterMotion.COMPLETION_REST * (bar - cell * columns), still.x, 1e-9)
        assertEquals(0, still.frame)
        val stillRandom = pose(random, later, reduceMotion = true)!!
        assertEquals(0.25 * (bar - cell * columns), stillRandom.x, 1e-9)
        assertEquals(0, stillRandom.frame)
    }

    @Test fun marchSpeedSpreadsRandomMonstersApart() {
        val now = 1_800_000_000_000L
        val spawned = now - 600_000
        fun x(key: String) = GaugeMonsterMotion.pose(
            encounter(source = MonsterEncounter.Source.RANDOM, spawnedAtMs = spawned).copy(key = key),
            null, now, bar, cell, columns, deathFrames = 4, tickMs = tick, reduceMotion = false,
        )!!.x
        // Same kind, same spot, same spawn: only the key's pace sets them apart.
        val spots = (0 until 8).map { x("k$it") }.toSet()
        assertTrue(spots.size > 1)
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

    @Test fun aRandomMonsterDiesWhereItMarchedTo() {
        val spawned = 1_800_000_000_000L
        val at = spawned + 45_000
        val e = encounter(
            source = MonsterEncounter.Source.RANDOM, state = MonsterEncounter.State.DEFEATED,
            defeatedAtMs = at, position = 0.25, spawnedAtMs = spawned,
        )
        val deathX = randomMarchX(at, spawnedAt = spawned, position = 0.25)
        assertEquals(deathX, pose(e, at + 100)!!.x, 0.0)
        assertEquals(deathX, pose(e, at + 899)!!.x, 0.0)
        assertEquals(deathX, pose(e, at + 900)!!.x, 0.0)
    }

    @Test fun aRandomGhostComesBackInFromTheLeft() {
        val at = 1_800_000_000_000L
        val e = encounter(
            source = MonsterEncounter.Source.RANDOM, state = MonsterEncounter.State.DEFEATED,
            defeatedAtMs = at, position = 0.9, spawnedAtMs = at,
        )
        // Body 18, hop 8, bar 300 → 40 steps a lap; the defeat at 0.9 of the bar snaps to x = 254 (step 34).
        val deathX = randomMarchX(at, spawnedAt = at, position = 0.9)
        assertEquals(254.0, deathX, 0.0)
        val ghostPeriod = randomPeriod / GaugeMonsterMotion.GHOST_SPEED_FACTOR
        fun ghostAt(tick: Int) = pose(e, at + 900 + (ghostPeriod * tick).toLong() + 1)!!
        assertEquals(deathX, ghostAt(0).x, 0.0)
        assertEquals(deathX + 8, ghostAt(1).x, 0.0)
        // Past the right edge it comes back in from the left instead of leaving (the store drops it after an hour).
        assertEquals(-cell * columns, ghostAt(12).x, 0.0)
        assertEquals(GaugeMonsterMotion.marchX(41, 0L, deathX, cell * 4, cell * columns, bar), ghostAt(41).x, 0.0)
    }

    @Test fun reducedMotionShowsAStillGhostAndNothingForExpired() {
        val e = encounter(state = MonsterEncounter.State.DEFEATED, defeatedAtMs = 5)
        val still = pose(e, 10, reduceMotion = true)!!
        assertEquals(Look.GHOST to 0, still.look to still.frame)
        assertTrue(still.halo)
        assertNull(pose(encounter(state = MonsterEncounter.State.EXPIRED), 10))
    }
}
