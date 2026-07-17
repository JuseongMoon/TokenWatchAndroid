package com.ScienceFiction.TokenWatchAndroid.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.ScienceFiction.TokenWatchAndroid.localization.APP_LANGUAGE_STORAGE_KEY
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class SettingsRepository(
    private val dataStore: DataStore<Preferences>,
) {
    constructor(context: Context) : this(context.applicationContext.tokenWatchDataStore)

    val settings: Flow<AppSettings> = dataStore.safeData
        .map { preferences -> preferences.toAppSettings() }
        .distinctUntilChanged()

    suspend fun saveSettings(settings: AppSettings) {
        dataStore.edit { preferences -> preferences.write(AppSettingsCodec.normalize(settings)) }
    }

    suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
        dataStore.edit { preferences ->
            preferences.write(AppSettingsCodec.normalize(transform(preferences.toAppSettings())))
        }
    }

    private fun Preferences.toAppSettings(): AppSettings = AppSettingsCodec.decode(
        refreshInterval = safeGet(Keys.refreshInterval),
        keepScreenOn = safeGet(Keys.keepScreenOn),
        hideUnusedWindows = safeGet(Keys.hideUnusedWindows),
        gaugeCritter = safeGet(Keys.gaugeCritter),
        heartbeatCursor = safeGet(Keys.heartbeatCursor),
        heartbeatTracking = safeGet(Keys.heartbeatTracking),
        heartbeatTargets = safeGet(Keys.heartbeatTargets),
        notifySessionResets = safeGet(Keys.notifySessionResets),
        notifyWeeklyResets = safeGet(Keys.notifyWeeklyResets),
        languageWireId = safeGet(Keys.language),
    )

    private fun MutablePreferences.write(settings: AppSettings) {
        this[Keys.refreshInterval] = settings.refreshInterval
        this[Keys.keepScreenOn] = settings.keepScreenOn
        this[Keys.hideUnusedWindows] = settings.hideUnusedWindows
        this[Keys.gaugeCritter] = settings.gaugeCritter
        this[Keys.heartbeatCursor] = settings.heartbeatCursor
        this[Keys.heartbeatTracking] = settings.heartbeatTracking
        this[Keys.heartbeatTargets] = settings.heartbeatTargets.toSet()
        this[Keys.notifySessionResets] = settings.notifySessionResets
        this[Keys.notifyWeeklyResets] = settings.notifyWeeklyResets
        this[Keys.language] = settings.language.wireId
    }

    private object Keys {
        val refreshInterval = intPreferencesKey("tokenwatch.refreshInterval")
        val keepScreenOn = booleanPreferencesKey("tokenwatch.keepScreenOn")
        val hideUnusedWindows = booleanPreferencesKey("tokenwatch.hideUnusedWindows")
        val gaugeCritter = booleanPreferencesKey("tokenwatch.gaugeCritter")
        val heartbeatCursor = booleanPreferencesKey("tokenwatch.heartbeatCursor")
        val heartbeatTracking = booleanPreferencesKey("tokenwatch.heartbeatTracking")
        val heartbeatTargets = stringSetPreferencesKey("tokenwatch.heartbeatTargets")
        val notifySessionResets = booleanPreferencesKey("tokenwatch.notifySession")
        val notifyWeeklyResets = booleanPreferencesKey("tokenwatch.notifyWeekly")
        val language = stringPreferencesKey(APP_LANGUAGE_STORAGE_KEY)
    }
}
