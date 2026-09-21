package com.ScienceFiction.TokenWatchAndroid.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import com.ScienceFiction.TokenWatchAndroid.domain.ReviewPromptState
import kotlinx.coroutines.flow.first

/**
 * Review prompt counters: when the app was first launched, how many cold starts there have been,
 * and whether the one prompt has been spent. Kept out of [AppSettings] for the same reason as the
 * announcement state — nothing here appears on the settings screen.
 */
class ReviewPromptRepository(
    private val dataStore: DataStore<Preferences>,
) {
    constructor(context: Context) : this(context.applicationContext.tokenWatchDataStore)

    suspend fun load(): ReviewPromptState {
        val preferences = dataStore.safeData.first()
        return ReviewPromptState(
            firstLaunchAt = preferences.safeGet(firstLaunchKey)?.takeIf { it > 0 },
            launchCount = preferences.safeGet(launchCountKey) ?: 0,
            prompted = preferences.safeGet(promptedKey) ?: false,
        )
    }

    /** Records one cold start and returns the state it produced. */
    suspend fun recordLaunch(now: Long): ReviewPromptState {
        val next = load().recordingLaunch(now)
        dataStore.edit { preferences ->
            preferences[firstLaunchKey] = next.firstLaunchAt ?: now
            preferences[launchCountKey] = next.launchCount
        }
        return next
    }

    /** The prompt is spent once per install, so this is written before the flow is launched. */
    suspend fun markPrompted() {
        dataStore.edit { preferences -> preferences[promptedKey] = true }
    }

    private companion object {
        val firstLaunchKey = longPreferencesKey("tokenwatch.review.firstLaunchAt")
        val launchCountKey = intPreferencesKey("tokenwatch.review.launchCount")
        val promptedKey = booleanPreferencesKey("tokenwatch.review.prompted")
    }
}
