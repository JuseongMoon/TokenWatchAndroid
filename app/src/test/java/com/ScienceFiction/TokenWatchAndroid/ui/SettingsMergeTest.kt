package com.ScienceFiction.TokenWatchAndroid.ui

import com.ScienceFiction.TokenWatchAndroid.data.AppSettings
import com.ScienceFiction.TokenWatchAndroid.localization.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsMergeTest {
    @Test
    fun rapidIndependentEventsDoNotRevertEarlierChanges() {
        val base = AppSettings()
        val afterScreenToggle = mergeSettingsChange(
            base = base,
            proposed = base.copy(keepScreenOn = true),
            current = base,
        )
        val afterLanguageTapFromStaleComposition = mergeSettingsChange(
            base = base,
            proposed = base.copy(language = AppLanguage.KOREAN),
            current = afterScreenToggle,
        )

        assertTrue(afterLanguageTapFromStaleComposition.keepScreenOn)
        assertEquals(AppLanguage.KOREAN, afterLanguageTapFromStaleComposition.language)
    }

    @Test
    fun multiFieldHeartbeatEventIsAppliedAtomically() {
        val base = AppSettings(keepScreenOn = true)
        val proposed = base.copy(
            heartbeatTracking = true,
            heartbeatTargets = setOf("agent|window"),
        )
        val current = base.copy(hideUnusedWindows = true)

        val merged = mergeSettingsChange(base, proposed, current)

        assertTrue(merged.heartbeatTracking)
        assertEquals(setOf("agent|window"), merged.heartbeatTargets)
        assertTrue(merged.hideUnusedWindows)
        assertTrue(merged.keepScreenOn)
        assertTrue(merged.gaugeCritter)
    }
}
