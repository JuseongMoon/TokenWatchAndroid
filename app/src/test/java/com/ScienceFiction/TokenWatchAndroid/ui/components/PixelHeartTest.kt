package com.ScienceFiction.TokenWatchAndroid.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PixelHeartTest {
    @Test
    fun remainingPercentMapsToTenHalfHeartSlots() {
        assertEquals(10, remainingHalfHearts(0.0))
        assertEquals(10, remainingHalfHearts(9.99))
        assertEquals(9, remainingHalfHearts(10.0))
        assertEquals(5, remainingHalfHearts(50.0))
        assertEquals(1, remainingHalfHearts(99.9))
        assertEquals(0, remainingHalfHearts(100.0))
    }

    @Test
    fun remainingHalfHeartsClampsOutOfRangeInput() {
        assertEquals(10, remainingHalfHearts(-50.0))
        assertEquals(0, remainingHalfHearts(150.0))
    }

    @Test
    fun heartBitmapMatchesIosGridAndPalette() {
        assertEquals(10, PixelHeartData.rows)
        assertEquals(11, PixelHeartData.columns)
        assertEquals(setOf(0, 1, 2, 3), PixelHeartData.bitmap.flatten().toSet())
        assertEquals(setOf(1, 2, 3), PixelHeartData.palette.keys)
    }

    @Test
    fun edgeDetectionKeepsOutlineAndDropsInterior() {
        assertTrue(PixelHeartData.isEdge(row = 0, column = 2))
        assertFalse(PixelHeartData.isEdge(row = 3, column = 5))
    }
}
