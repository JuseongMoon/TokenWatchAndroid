package com.ScienceFiction.TokenWatchAndroid.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GaugeCritterTest {
    @Test
    fun framesAlternatePerTick() {
        assertEquals(0, GaugeCritterMath.frameIndex(0))
        assertEquals(1, GaugeCritterMath.frameIndex(1))
        assertEquals(0, GaugeCritterMath.frameIndex(2))
        assertEquals(1, GaugeCritterMath.frameIndex(3))
        assertEquals(1, GaugeCritterMath.frameIndex(-3))
    }

    @Test
    fun startsFullyHiddenAtLeftEdge() {
        assertEquals(
            -20.0,
            GaugeCritterMath.offsetX(tick = 0, hop = 10.0, spriteWidth = 20.0, barWidth = 100.0),
            0.0,
        )
    }

    @Test
    fun advancesOnJumpTickAndRestsOnLandTick() {
        val jump = GaugeCritterMath.offsetX(1, 10.0, 20.0, 100.0)
        val land = GaugeCritterMath.offsetX(2, 10.0, 20.0, 100.0)
        val next = GaugeCritterMath.offsetX(3, 10.0, 20.0, 100.0)
        assertEquals(-10.0, jump, 0.0)
        assertEquals(jump, land, 0.0)
        assertEquals(0.0, next, 0.0)
    }

    @Test
    fun wrapsAroundAfterCrossingBar() {
        val lastVisible = GaugeCritterMath.offsetX(22, 10.0, 20.0, 100.0)
        val wrapped = GaugeCritterMath.offsetX(23, 10.0, 20.0, 100.0)
        assertEquals(90.0, lastVisible, 0.0)
        assertEquals(-20.0, wrapped, 0.0)
    }

    @Test
    fun staysInsideBarForFullCycle() {
        for (tick in 0..200) {
            val x = GaugeCritterMath.offsetX(tick, 7.0, 15.4, 250.0)
            assertTrue(x >= -15.4)
            assertTrue(x < 250.0)
        }
    }

    @Test
    fun degenerateSizesFallBackToHidden() {
        assertEquals(-20.0, GaugeCritterMath.offsetX(5, 0.0, 20.0, 100.0), 0.0)
        assertEquals(-20.0, GaugeCritterMath.offsetX(5, 10.0, 20.0, 0.0), 0.0)
    }

    @Test
    fun thresholdAndTimingMatchIos() {
        assertEquals(0.995, GaugeCritterMath.Threshold, 0.0)
        assertEquals(250L, GaugeCritterMath.TickMillis)
        assertEquals(4, GaugeCritterMath.HopCells)
        assertTrue(0.994 < GaugeCritterMath.Threshold)
        assertTrue(1.0 >= GaugeCritterMath.Threshold)
    }

    @Test
    fun slimeHasTwoFramesOfSameGridSize() {
        val slime = PixelSprite.Slime
        assertEquals(2, slime.frames.size)
        assertEquals(7, slime.rows)
        assertEquals(9, slime.columns)
        slime.frames.forEach { frame ->
            assertEquals(slime.rows, frame.size)
            frame.forEach { row -> assertEquals(slime.columns, row.size) }
        }
    }

    @Test
    fun slimeUsesOnlyPaletteValues() {
        val slime = PixelSprite.Slime
        val allowed = slime.palette.keys + 0
        slime.frames.forEach { frame ->
            frame.forEach { row ->
                row.forEach { value -> assertTrue(value in allowed) }
            }
        }
    }

    @Test
    fun slimeFramesAreBottomAligned() {
        val slime = PixelSprite.Slime
        slime.frames.forEach { frame ->
            assertTrue(frame.last().any { it != 0 })
            assertTrue(frame.first().isNotEmpty())
        }
    }
}
