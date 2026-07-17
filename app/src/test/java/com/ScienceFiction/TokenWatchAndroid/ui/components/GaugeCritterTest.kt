package com.ScienceFiction.TokenWatchAndroid.ui.components

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GaugeCritterTest {
    @Test
    fun spinnerUsesTheSixFrameFourLitDotRotation() {
        assertEquals(listOf("⠶", "⠧", "⠏", "⠛", "⠹", "⠼"), TerminalSpinnerFrames)
    }

    @Test
    fun terminalGaugeFillDirectionMatchesUsageStyle() {
        assertEquals(0.0, TerminalGaugeMath.fillFraction(used = 0.0, fillsRemaining = false), 0.0)
        assertEquals(0.25, TerminalGaugeMath.fillFraction(used = 0.25, fillsRemaining = false), 0.0)
        assertEquals(1.0, TerminalGaugeMath.fillFraction(used = 1.0, fillsRemaining = false), 0.0)

        assertEquals(1.0, TerminalGaugeMath.fillFraction(used = 0.0, fillsRemaining = true), 0.0)
        assertEquals(0.75, TerminalGaugeMath.fillFraction(used = 0.25, fillsRemaining = true), 0.0)
        assertEquals(0.0, TerminalGaugeMath.fillFraction(used = 1.0, fillsRemaining = true), 0.0)
    }

    @Test
    fun terminalGaugeFillClampsOutOfRangeConsumption() {
        assertEquals(0.0, TerminalGaugeMath.fillFraction(used = -1.0, fillsRemaining = false), 0.0)
        assertEquals(1.0, TerminalGaugeMath.fillFraction(used = 2.0, fillsRemaining = false), 0.0)
        assertEquals(1.0, TerminalGaugeMath.fillFraction(used = -1.0, fillsRemaining = true), 0.0)
        assertEquals(0.0, TerminalGaugeMath.fillFraction(used = 2.0, fillsRemaining = true), 0.0)
    }

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
    fun thresholdAndVariantTimingMatchIos() {
        assertEquals(0.995, GaugeCritterMath.Threshold, 0.0)
        assertEquals(285L, GaugeCritterVariant.WEEKLY.baseTickMillis)
        assertEquals(219L, GaugeCritterVariant.SESSION.baseTickMillis)
        assertTrue(
            GaugeCritterVariant.WEEKLY.baseTickMillis.toDouble() /
                GaugeCritterVariant.SESSION.baseTickMillis > 1.3,
        )
        assertEquals(4, GaugeCritterMath.HopCells)
        assertEquals(8, GaugeCritterMath.OpacitySteps)
        assertEquals(600L, GaugeCritterMath.FadeDurationMillis)
        assertTrue(0.994 < GaugeCritterMath.Threshold)
        assertTrue(1.0 >= GaugeCritterMath.Threshold)
    }

    @Test
    fun steppedOpacityQuantizesToEighthsAndClamps() {
        assertEquals(0.0, GaugeCritterMath.steppedOpacity(-0.5), 0.0)
        assertEquals(0.0, GaugeCritterMath.steppedOpacity(0.1), 0.0)
        assertEquals(0.125, GaugeCritterMath.steppedOpacity(0.2), 0.0)
        assertEquals(0.5, GaugeCritterMath.steppedOpacity(0.5), 0.0)
        assertEquals(0.875, GaugeCritterMath.steppedOpacity(0.99), 0.0)
        assertEquals(1.0, GaugeCritterMath.steppedOpacity(1.5), 0.0)
    }

    @Test
    fun steppedOpacityWalksOnlyEightSteps() {
        val levels = (0..100)
            .map { GaugeCritterMath.steppedOpacity(it / 100.0) }
            .toSet()

        assertEquals(9, levels.size)
        assertTrue(0.0 in levels)
        assertTrue(1.0 in levels)
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
        assertEquals(Color(0xFF29A64A), slime.palette[3])
    }

    @Test
    fun sessionSlimeReusesFramesWithSkyPalette() {
        assertEquals(PixelSprite.Slime.frames, PixelSprite.SlimeSky.frames)
        assertEquals(Color(0xFF4FBCFA), PixelSprite.SlimeSky.palette[1])
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
