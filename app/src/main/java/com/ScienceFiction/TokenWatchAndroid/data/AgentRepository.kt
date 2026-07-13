package com.ScienceFiction.TokenWatchAndroid.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.ScienceFiction.TokenWatchAndroid.domain.Agent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class AgentRepository(
    private val dataStore: DataStore<Preferences>,
) {
    constructor(context: Context) : this(context.applicationContext.tokenWatchDataStore)

    val agents: Flow<List<Agent>> = dataStore.safeData
        .map { preferences -> AgentJsonCodec.decode(preferences.safeGet(agentsKey)) }
        .distinctUntilChanged()

    val creditPeaks: Flow<Map<String, Double>> = dataStore.safeData
        .map { preferences -> CreditPeaksCodec.decode(preferences.safeGet(creditPeaksKey)) }
        .distinctUntilChanged()

    suspend fun saveAgents(agents: List<Agent>) {
        dataStore.edit { preferences ->
            preferences[agentsKey] = AgentJsonCodec.encode(agents)
        }
    }

    suspend fun updateAgents(transform: (List<Agent>) -> List<Agent>) {
        dataStore.edit { preferences ->
            val current = AgentJsonCodec.decode(preferences.safeGet(agentsKey))
            preferences[agentsKey] = AgentJsonCodec.encode(transform(current))
        }
    }

    suspend fun saveCreditPeaks(peaks: Map<String, Double>) {
        dataStore.edit { preferences ->
            preferences[creditPeaksKey] = CreditPeaksCodec.encode(peaks)
        }
    }

    suspend fun updateCreditPeaks(transform: (Map<String, Double>) -> Map<String, Double>) {
        dataStore.edit { preferences ->
            val current = CreditPeaksCodec.decode(preferences.safeGet(creditPeaksKey))
            preferences[creditPeaksKey] = CreditPeaksCodec.encode(transform(current))
        }
    }

    private companion object {
        val agentsKey = stringPreferencesKey("tokenwatch.agents.v1")
        val creditPeaksKey = stringPreferencesKey("tokenwatch.creditPeaks.v1")
    }
}
