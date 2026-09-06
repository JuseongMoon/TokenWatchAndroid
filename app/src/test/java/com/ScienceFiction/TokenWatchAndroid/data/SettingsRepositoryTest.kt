package com.ScienceFiction.TokenWatchAndroid.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.ScienceFiction.TokenWatchAndroid.domain.WorkHoursSchedule
import com.ScienceFiction.TokenWatchAndroid.localization.AppLanguage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The work-hours flag is tri-state and this layer is the only place that can flatten it: writing
 * `false` for an absent flag would switch the feature off for existing users the first time any
 * unrelated setting is saved.
 */
class SettingsRepositoryTest {
    private class FakePreferencesDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences,
        ): Preferences = transform(state.value).also { state.value = it }
    }

    private fun repository() = SettingsRepository(FakePreferencesDataStore())

    private val painted = WorkHoursSchedule().settingRect(0, 9, 4, 17, true).encoded

    @Test
    fun unsetFlagSurvivesSavingUnrelatedSettings() = runBlocking {
        val repository = repository()
        repository.saveSettings(AppSettings(workHours = painted))
        assertNull(repository.settings.first().workHoursEnabled)

        repository.updateSettings { it.copy(language = AppLanguage.KOREAN) }

        val after = repository.settings.first()
        assertNull(after.workHoursEnabled)
        assertTrue(WorkHoursSchedule.isEnabled(after.workHours, after.workHoursEnabled))
        assertEquals(AppLanguage.KOREAN, after.language)
    }

    @Test
    fun explicitFlagRoundTripsInBothStates() = runBlocking {
        val repository = repository()
        repository.saveSettings(AppSettings(workHours = painted, workHoursEnabled = false))
        assertEquals(false, repository.settings.first().workHoursEnabled)

        repository.updateSettings { it.copy(keepScreenOn = true) }
        assertEquals(false, repository.settings.first().workHoursEnabled)

        repository.updateSettings { it.copy(workHoursEnabled = true) }
        assertEquals(true, repository.settings.first().workHoursEnabled)
    }

    @Test
    fun clearingTheFlagRestoresDerivedBehavior() = runBlocking {
        val repository = repository()
        repository.saveSettings(AppSettings(workHours = painted, workHoursEnabled = false))

        repository.updateSettings { it.copy(workHoursEnabled = null) }

        val after = repository.settings.first()
        assertNull(after.workHoursEnabled)
        assertTrue(WorkHoursSchedule.isEnabled(after.workHours, after.workHoursEnabled))
    }
}
