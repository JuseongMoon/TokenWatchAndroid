package com.ScienceFiction.TokenWatchAndroid.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.ScienceFiction.TokenWatchAndroid.account.PlayerProfileCacheStore
import com.ScienceFiction.TokenWatchAndroid.domain.account.PlayerProfileCache
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.flow.first

/**
 * The app account's offline profile cache (`tokenwatch.player.profile.v1`, same key and JSON fields
 * as iOS). Only uid, nickname, tag, avatar id and the nickname change time — never a token or an email.
 * The server is authoritative; this only lets the PLAYER box draw before the profile is re-read. A cache
 * written before nametags existed has no tag and reads as absent, so the profile is re-read instead.
 */
class PlayerProfileRepository(
    private val dataStore: DataStore<Preferences>,
) : PlayerProfileCacheStore {
    constructor(context: Context) : this(context.applicationContext.tokenWatchDataStore)

    override suspend fun load(): PlayerProfileCache? =
        dataStore.safeData.first().safeGet(key)?.let(::decode)

    override suspend fun save(cache: PlayerProfileCache) {
        dataStore.edit { preferences -> preferences[key] = encode(cache) }
    }

    override suspend fun clear() {
        dataStore.edit { preferences -> preferences.remove(key) }
    }

    internal companion object {
        private val key = stringPreferencesKey(PlayerProfileCache.STORAGE_KEY)
        private val adapter = Moshi.Builder().build().adapter<Map<String, Any>>(
            Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java),
        )

        fun encode(cache: PlayerProfileCache): String = adapter.toJson(
            mapOf(
                "uid" to cache.uid,
                "nickname" to cache.nickname,
                "tag" to cache.tag,
                "avatarId" to cache.avatarId,
                "nicknameChangedAtMs" to cache.nicknameChangedAtMs,
            ),
        )

        fun decode(json: String): PlayerProfileCache? {
            val map = runCatching { adapter.fromJson(json) }.getOrNull() ?: return null
            return PlayerProfileCache(
                uid = map["uid"] as? String ?: return null,
                nickname = map["nickname"] as? String ?: return null,
                tag = map["tag"] as? String ?: return null,
                avatarId = map["avatarId"] as? String ?: return null,
                nicknameChangedAtMs = (map["nicknameChangedAtMs"] as? Number)?.toLong() ?: return null,
            )
        }
    }
}
