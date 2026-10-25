package com.ScienceFiction.TokenWatchAndroid.monster

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ScienceFiction.TokenWatchAndroid.data.safeData
import com.ScienceFiction.TokenWatchAndroid.data.safeGet
import com.google.android.gms.auth.blockstore.Blockstore
import com.google.android.gms.auth.blockstore.DeleteBytesRequest
import com.google.android.gms.auth.blockstore.RetrieveBytesRequest
import com.google.android.gms.auth.blockstore.StoreBytesData
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await

/**
 * The monster files' own DataStore. The shared `tokenwatch` settings file is rewritten whole on every
 * change, so the ledger never goes there.
 */
private val Context.monsterDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "tokenwatch_monsters",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * Everything the monster store keeps on disk except Block Store: the ledger, the config cache and the
 * DataStore copy of the device session ledger ([sessionLedgerCopy]).
 */
class MonsterDataStore(private val dataStore: DataStore<Preferences>) :
    MonsterLedgerStorage, MonsterPreferences {
    constructor(context: Context) : this(context.applicationContext.monsterDataStore)

    override suspend fun load(): MonsterLedgerState? =
        dataStore.safeData.first().safeGet(LEDGER)?.let(MonsterLedgerCodec::decode)

    override suspend fun save(state: MonsterLedgerState) {
        val json = MonsterLedgerCodec.encode(state)
        dataStore.edit { it[LEDGER] = json }
    }

    override suspend fun loadPreferences(): MonsterPreferenceState {
        val preferences = dataStore.safeData.first()
        return MonsterPreferenceState(
            configPayload = preferences.safeGet(CONFIG),
            configFetchedAtMs = preferences.safeGet(CONFIG_FETCHED_AT),
        )
    }

    override suspend fun saveConfig(payload: String) {
        dataStore.edit { it[CONFIG] = payload }
    }

    override suspend fun saveConfigFetchedAt(fetchedAtMs: Long) {
        dataStore.edit { it[CONFIG_FETCHED_AT] = fetchedAtMs }
    }

    /** The session ledger copy kept for when Block Store is unavailable. */
    val sessionLedgerCopy: DeviceSessionLedgerStorage = object : DeviceSessionLedgerStorage {
        override suspend fun load(): DeviceSessionLedgerState =
            dataStore.safeData.first().safeGet(SESSION_LEDGER)?.let(SessionLedgerJson::decode) ?: DeviceSessionLedgerState()

        override suspend fun save(state: DeviceSessionLedgerState) {
            val json = SessionLedgerJson.encode(state)
            dataStore.edit { it[SESSION_LEDGER] = json }
        }
    }

    private companion object {
        val LEDGER = stringPreferencesKey("tokenwatch.monster.encounters.v1")
        val CONFIG = stringPreferencesKey("tokenwatch.monster.config.v1")
        val CONFIG_FETCHED_AT = longPreferencesKey("tokenwatch.monster.configFetchedAt")
        val SESSION_LEDGER = stringPreferencesKey("tokenwatch.monster.sessionLedger.v1")
    }
}

/**
 * The device session ledger in Block Store: device-only (never backed up to the cloud), split into
 * 4 KB entries in [SessionLedgerCodec]'s compact form. Block Store scopes it to package + signing key,
 * so a debug build and the Play build keep separate ledgers. Calls throw when Play services is missing;
 * [CombinedSessionLedger] then falls back to the DataStore copy.
 */
class BlockStoreSessionLedger(context: Context) : DeviceSessionLedgerStorage {
    private val client = Blockstore.getClient(context.applicationContext)

    /** How many entries hold the ledger now, when known (to delete the leftovers of a longer one). */
    @Volatile
    private var storedChunks: Int? = null

    override suspend fun load(): DeviceSessionLedgerState {
        val request = RetrieveBytesRequest.Builder().setKeys(KEYS).build()
        val entries = client.retrieveBytes(request).await().blockstoreDataMap
        val bytes = ByteArrayOutputStream()
        var count = 0
        for (key in KEYS) {
            val chunk = entries[key]?.bytes ?: break
            bytes.write(chunk)
            count += 1
        }
        storedChunks = count
        return SessionLedgerCodec.decode(bytes.toByteArray())
    }

    override suspend fun save(state: DeviceSessionLedgerState) {
        val chunks = SessionLedgerCodec.chunks(SessionLedgerCodec.encode(state))
        chunks.forEachIndexed { index, chunk ->
            val data = StoreBytesData.Builder()
                .setKey(KEYS[index])
                .setBytes(chunk)
                // Device scope: the ledger is about this device, and app data stays out of backups.
                .setShouldBackupToCloud(false)
                .build()
            client.storeBytes(data).await()
        }
        val previous = storedChunks ?: KEYS.size
        if (previous > chunks.size) {
            client.deleteBytes(DeleteBytesRequest.Builder().setKeys(KEYS.subList(chunks.size, previous)).build()).await()
        }
        storedChunks = chunks.size
    }

    private companion object {
        val KEYS: List<String> = List(SessionLedgerCodec.MAX_CHUNKS) { "tokenwatch.monster.sessionLedger.v1.$it" }
    }
}
