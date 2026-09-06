package com.ScienceFiction.TokenWatchAndroid.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.ScienceFiction.TokenWatchAndroid.domain.AnnouncementFeed
import kotlinx.coroutines.flow.first

/**
 * Announcement state that is not a user setting: what has been dismissed or read, the cached feed
 * and when the feed was last fetched successfully. Kept out of [AppSettings] so the settings model
 * stays a one-to-one mirror of the settings screen.
 */
class AnnouncementRepository(
    private val dataStore: DataStore<Preferences>,
) {
    constructor(context: Context) : this(context.applicationContext.tokenWatchDataStore)

    suspend fun loadDismissed(): List<String> = AnnouncementCodec.decodeIds(read(dismissedKey))

    suspend fun saveDismissed(ids: List<String>) = write(dismissedKey, AnnouncementCodec.encodeIds(ids))

    suspend fun loadSeen(): List<String> = AnnouncementCodec.decodeIds(read(seenKey))

    suspend fun saveSeen(ids: List<String>) = write(seenKey, AnnouncementCodec.encodeIds(ids))

    suspend fun loadCachedFeed(): AnnouncementFeed? = AnnouncementCodec.decode(read(cachedFeedKey))

    suspend fun saveCachedFeed(feed: AnnouncementFeed?) {
        dataStore.edit { preferences ->
            if (feed == null) {
                preferences.remove(cachedFeedKey)
            } else {
                preferences[cachedFeedKey] = AnnouncementCodec.encode(feed)
            }
        }
    }

    suspend fun loadLastSuccessAt(): Long? =
        dataStore.safeData.first().safeGet(lastSuccessKey)?.takeIf { it > 0 }

    suspend fun saveLastSuccessAt(epochMillis: Long) {
        dataStore.edit { preferences -> preferences[lastSuccessKey] = epochMillis }
    }

    private suspend fun read(key: Preferences.Key<String>): String? =
        dataStore.safeData.first().safeGet(key)

    private suspend fun write(key: Preferences.Key<String>, value: String) {
        dataStore.edit { preferences -> preferences[key] = value }
    }

    private companion object {
        val dismissedKey = stringPreferencesKey("tokenwatch.announcements.dismissed.v1")
        val seenKey = stringPreferencesKey("tokenwatch.announcements.seen.v1")
        val cachedFeedKey = stringPreferencesKey("tokenwatch.announcements.cachedFeed.v1")
        val lastSuccessKey = longPreferencesKey("tokenwatch.announcements.lastSuccessAt")
    }
}
