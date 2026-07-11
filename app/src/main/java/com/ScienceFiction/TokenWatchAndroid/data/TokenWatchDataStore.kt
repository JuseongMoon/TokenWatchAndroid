package com.ScienceFiction.TokenWatchAndroid.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch

private const val DATA_STORE_NAME = "tokenwatch"

internal val Context.tokenWatchDataStore: DataStore<Preferences> by preferencesDataStore(
    name = DATA_STORE_NAME,
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

internal val DataStore<Preferences>.safeData: Flow<Preferences>
    get() = data.catch { exception ->
        if (exception is IOException) {
            emit(emptyPreferences())
        } else {
            throw exception
        }
    }

internal fun <T> Preferences.safeGet(key: Preferences.Key<T>): T? =
    runCatching { this[key] }.getOrNull()
