package com.ScienceFiction.TokenWatchAndroid.analytics

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ScienceFiction.TokenWatchAndroid.domain.AgentProvider
import com.ScienceFiction.TokenWatchAndroid.domain.AgentSnapshot
import java.util.UUID
import kotlinx.coroutines.flow.first

/**
 * Its own small file: the shared `tokenwatch` settings file is rewritten whole on every change, and
 * this one changes with fetch results.
 */
private val Context.fetchOutcomeDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "tokenwatch_fetch_outcomes",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * Each account's last fetch outcome, kept on disk so `usage_fetch_error` and `usage_fetch_recover`
 * mark real transitions across process restarts instead of repeating in every new process (D021,
 * the counterpart of iOS `FetchOutcomeLedger`). The metric counts transitions per account, not
 * people.
 *
 * One entry per account, enum strings only: [OK], a [FetchErrorReason.wireId], or [PAUSED] (the
 * baseline is unknown). [record] checks that the result still counts, compares it with the stored
 * value and stores the new one in a single DataStore transaction; the event it returns is sent by
 * the caller afterwards, outside the transaction. A process that ends in between can drop that
 * event: exactly-once delivery is not a goal.
 *
 * A result that arrives while analytics is not actually collecting is neither compared nor stored:
 * the account is only marked [PAUSED], and its next result sets the baseline without an event, so a
 * recovery is never sent for an error that was never reported. An account with no result while
 * collection was off keeps its baseline. Entering demo mode marks every account [PAUSED] as well.
 */
class FetchOutcomeLedger(private val dataStore: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.fetchOutcomeDataStore)

    /**
     * Records one fetch result and returns the event it calls for, or null.
     *
     * [collecting] is whether analytics would actually send an event now. [isAccepted] runs inside
     * the transaction: false (the account was removed, or signed in again since the fetch began)
     * leaves everything untouched. Storage errors are thrown to the caller.
     */
    suspend fun record(
        provider: AgentProvider,
        agentId: UUID,
        snapshot: AgentSnapshot,
        collecting: Boolean,
        isAccepted: suspend () -> Boolean,
    ): AnalyticsEvent? {
        // An error laid over the last good graph (a 429, say) is still an error.
        val failure = snapshot.error?.let { snapshot.errorReason ?: FetchErrorReason.OTHER }
        var event: AnalyticsEvent? = null
        dataStore.edit { entries ->
            event = null
            if (!isAccepted()) return@edit
            val key = key(agentId)
            if (!collecting) {
                entries[key] = PAUSED
                return@edit
            }
            event = fetchOutcomeEvent(provider, entries[key], failure)
            entries[key] = failure?.wireId ?: OK
        }
        return event
    }

    /** The stored value of one account (tests and diagnostics). */
    suspend fun value(agentId: UUID): String? = dataStore.data.first()[key(agentId)]

    /** Removed accounts. */
    suspend fun forget(agentIds: Collection<UUID>) {
        if (agentIds.isEmpty()) return
        dataStore.edit { entries -> agentIds.forEach { entries.remove(key(it)) } }
    }

    /**
     * Entering demo mode: every account's baseline becomes unknown, so the same error is not sent
     * again once demo mode ends.
     */
    suspend fun pauseAll(agentIds: Collection<UUID>) {
        dataStore.edit { entries ->
            val stored = entries.asMap().keys.filter { it.name.startsWith(KEY_PREFIX) }.map { it.name }
            (stored + agentIds.map { key(it).name }).toSet()
                .forEach { entries[stringPreferencesKey(it)] = PAUSED }
        }
    }

    private fun key(agentId: UUID) = stringPreferencesKey("$KEY_PREFIX$agentId")

    companion object {
        /** The last fetch succeeded. */
        const val OK = "ok"

        /** The baseline is unknown (a result arrived while analytics was off, or demo mode began). */
        const val PAUSED = "paused"

        private const val KEY_PREFIX = "outcome."
    }
}
