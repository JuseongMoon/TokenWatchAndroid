package com.ScienceFiction.TokenWatchAndroid.data

import com.ScienceFiction.TokenWatchAndroid.localization.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsCodecTest {
    @Test
    fun defaultsMatchCleanIosBaseline() {
        assertEquals(
            AppSettings(
                refreshInterval = 60,
                keepScreenOn = false,
                hideUnusedWindows = false,
                gaugeCritter = true,
                heartbeatCursor = false,
                heartbeatTracking = false,
                heartbeatTargets = emptySet(),
                notifySessionResets = false,
                notifyWeeklyResets = true,
                language = AppLanguage.SYSTEM,
            ),
            AppSettingsCodec.decode(),
        )
    }

    @Test
    fun validStoredValuesAreRestored() {
        val settings = AppSettingsCodec.decode(
            refreshInterval = -1,
            keepScreenOn = true,
            hideUnusedWindows = true,
            gaugeCritter = false,
            heartbeatCursor = true,
            heartbeatTracking = true,
            heartbeatTargets = setOf("agent|weekly", "agent|session"),
            notifySessionResets = true,
            notifyWeeklyResets = false,
            languageWireId = "korean",
        )

        assertEquals(-1, settings.refreshInterval)
        assertTrue(settings.keepScreenOn)
        assertTrue(settings.hideUnusedWindows)
        assertFalse(settings.gaugeCritter)
        assertTrue(settings.heartbeatCursor)
        assertTrue(settings.heartbeatTracking)
        assertEquals(setOf("agent|weekly", "agent|session"), settings.heartbeatTargets)
        assertTrue(settings.notifySessionResets)
        assertFalse(settings.notifyWeeklyResets)
        assertEquals(AppLanguage.KOREAN, settings.language)
    }

    @Test
    fun invalidStoredValuesFallBackSafely() {
        val settings = AppSettingsCodec.decode(
            refreshInterval = 999,
            heartbeatTargets = setOf("", "   ", "valid|target"),
            languageWireId = "not-a-language",
        )

        assertEquals(60, settings.refreshInterval)
        assertEquals(setOf("valid|target"), settings.heartbeatTargets)
        assertEquals(AppLanguage.SYSTEM, settings.language)
    }

    @Test
    fun refreshOptionsMatchCleanBaseline() {
        assertEquals(setOf(-1, 0, 30, 60, 300), AppSettingsCodec.supportedRefreshIntervals)
    }
}
