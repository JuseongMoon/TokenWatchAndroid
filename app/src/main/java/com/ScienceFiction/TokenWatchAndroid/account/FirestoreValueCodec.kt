package com.ScienceFiction.TokenWatchAndroid.account

import com.ScienceFiction.TokenWatchAndroid.domain.account.AvatarCatalog
import com.ScienceFiction.TokenWatchAndroid.domain.account.PlayerProfile
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Firestore REST pieces for the app account profile (pure, unit tested): value encoding and decoding,
 * `documents:commit` write batches, and server clock skew from the `Date` header. No Firestore SDK —
 * the same "HTTPS + JSON" surface as the announcement feed, mirroring iOS `FirestoreREST.swift`.
 */

/** The three Firestore value kinds the profile uses. Integers travel as decimal strings. */
sealed class FirestoreValue {
    data class StringValue(val value: String) : FirestoreValue()
    data class IntegerValue(val value: Long) : FirestoreValue()
    data class MapValue(val fields: Map<String, FirestoreValue>) : FirestoreValue()

    fun toJson(): Map<String, Any> = when (this) {
        is StringValue -> mapOf("stringValue" to value)
        is IntegerValue -> mapOf("integerValue" to value.toString())
        is MapValue -> mapOf("mapValue" to mapOf("fields" to fields.mapValues { it.value.toJson() }))
    }

    val stringValue: String? get() = (this as? StringValue)?.value
    val integerValue: Long? get() = (this as? IntegerValue)?.value
    val mapValue: Map<String, FirestoreValue>? get() = (this as? MapValue)?.fields

    companion object {
        /** Unknown kinds are null (ignored). `integerValue` accepts a string or a number. */
        fun fromJson(json: Any?): FirestoreValue? {
            val map = json as? Map<*, *> ?: return null
            (map["stringValue"] as? String)?.let { return StringValue(it) }
            map["integerValue"]?.let { raw ->
                return when (raw) {
                    is String -> raw.toLongOrNull()?.let(::IntegerValue)
                    is Number -> IntegerValue(raw.toLong())
                    else -> null
                }
            }
            (map["mapValue"] as? Map<*, *>)?.let { mapValue ->
                val fields = mapValue["fields"] as? Map<*, *> ?: emptyMap<String, Any?>()
                return MapValue(decodeFields(fields))
            }
            return null
        }

        internal fun decodeFields(fields: Map<*, *>): Map<String, FirestoreValue> = buildMap {
            fields.forEach { (key, value) ->
                val name = key as? String ?: return@forEach
                fromJson(value)?.let { put(name, it) }
            }
        }
    }
}

/** One write in a `documents:commit` batch. */
sealed class FirestoreWrite {
    /**
     * [exists]: null = no precondition, false = new documents only, true = existing documents only.
     * [mask]: null replaces the whole document — **an existing document always gets a mask** so
     * server-only fields survive.
     */
    data class Update(
        val name: String,
        val fields: Map<String, FirestoreValue>,
        val mask: List<String>?,
        val exists: Boolean?,
    ) : FirestoreWrite()

    data class Delete(val name: String) : FirestoreWrite()

    fun toJson(): Map<String, Any> = when (this) {
        is Update -> buildMap {
            put("update", mapOf("name" to name, "fields" to fields.mapValues { it.value.toJson() }))
            mask?.let { put("updateMask", mapOf("fieldPaths" to it)) }
            exists?.let { put("currentDocument", mapOf("exists" to it)) }
        }
        is Delete -> mapOf("delete" to name)
    }
}

/** Profile document paths and write batches. Creation and nickname changes commit players and nicknames together. */
class PlayerDocuments(val projectId: String) {
    val root: String get() = "projects/$projectId/databases/(default)/documents"

    fun player(uid: String): String = "$root/players/$uid"

    fun nickname(lower: String): String = "$root/nicknames/$lower"

    /** Creation: players (new) + nickname reservation (new). */
    fun create(uid: String, profile: PlayerProfile): List<FirestoreWrite> = listOf(
        FirestoreWrite.Update(
            name = player(uid),
            fields = mapOf(
                "nickname" to FirestoreValue.StringValue(profile.nickname),
                "nicknameLower" to FirestoreValue.StringValue(profile.nicknameLower),
                "nicknameChangedAtMs" to FirestoreValue.IntegerValue(profile.nicknameChangedAtMs),
                "avatar" to avatarValue(profile.avatarId),
                "termsAcceptedVersion" to FirestoreValue.IntegerValue(profile.termsAcceptedVersion.toLong()),
                "createdAtMs" to FirestoreValue.IntegerValue(profile.createdAtMs),
                "updatedAtMs" to FirestoreValue.IntegerValue(profile.updatedAtMs),
            ),
            mask = null,
            exists = false,
        ),
        FirestoreWrite.Update(
            name = nickname(profile.nicknameLower),
            fields = mapOf("uid" to FirestoreValue.StringValue(uid)),
            mask = null,
            exists = false,
        ),
    )

    /**
     * Nickname change: players (masked) + new reservation + old reservation deleted. A case-only change
     * keeps the same reservation key, so only players is written.
     */
    fun changeNickname(
        uid: String,
        oldLower: String,
        nickname: String,
        lower: String,
        nowMs: Long,
    ): List<FirestoreWrite> = buildList {
        add(
            FirestoreWrite.Update(
                name = player(uid),
                fields = mapOf(
                    "nickname" to FirestoreValue.StringValue(nickname),
                    "nicknameLower" to FirestoreValue.StringValue(lower),
                    "nicknameChangedAtMs" to FirestoreValue.IntegerValue(nowMs),
                    "updatedAtMs" to FirestoreValue.IntegerValue(nowMs),
                ),
                mask = NICKNAME_MASK,
                exists = true,
            ),
        )
        if (lower != oldLower) {
            add(
                FirestoreWrite.Update(
                    name = nickname(lower),
                    fields = mapOf("uid" to FirestoreValue.StringValue(uid)),
                    mask = null,
                    exists = false,
                ),
            )
            add(FirestoreWrite.Delete(nickname(oldLower)))
        }
    }

    fun changeAvatar(uid: String, avatarId: String, nowMs: Long): List<FirestoreWrite> = listOf(
        FirestoreWrite.Update(
            name = player(uid),
            fields = mapOf("avatar" to avatarValue(avatarId), "updatedAtMs" to FirestoreValue.IntegerValue(nowMs)),
            mask = AVATAR_MASK,
            exists = true,
        ),
    )

    fun acceptTerms(uid: String, version: Int, nowMs: Long): List<FirestoreWrite> = listOf(
        FirestoreWrite.Update(
            name = player(uid),
            fields = mapOf(
                "termsAcceptedVersion" to FirestoreValue.IntegerValue(version.toLong()),
                "updatedAtMs" to FirestoreValue.IntegerValue(nowMs),
            ),
            mask = TERMS_MASK,
            exists = true,
        ),
    )

    /** Account deletion: the nickname reservation and the profile go together. */
    fun delete(uid: String, nicknameLower: String): List<FirestoreWrite> = listOf(
        FirestoreWrite.Delete(nickname(nicknameLower)),
        FirestoreWrite.Delete(player(uid)),
    )

    companion object {
        val NICKNAME_MASK = listOf("nickname", "nicknameLower", "nicknameChangedAtMs", "updatedAtMs")
        val AVATAR_MASK = listOf("avatar", "updatedAtMs")
        val TERMS_MASK = listOf("termsAcceptedVersion", "updatedAtMs")

        private val moshi = Moshi.Builder().build()
        private val anyAdapter = moshi.adapter(Any::class.java)
        private val bodyAdapter = moshi.adapter<Map<String, Any>>(
            Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java),
        )

        fun avatarValue(id: String): FirestoreValue = FirestoreValue.MapValue(
            mapOf("kind" to FirestoreValue.StringValue("preset"), "id" to FirestoreValue.StringValue(id)),
        )

        fun commitBody(writes: List<FirestoreWrite>): String =
            bodyAdapter.toJson(mapOf("writes" to writes.map { it.toJson() }))

        /**
         * `GET players/{uid}` → profile; null when a required field is missing. An avatar of any kind
         * other than `preset` reads as the fallback avatar.
         */
        fun decodeProfile(body: String): PlayerProfile? {
            val fields = documentFields(body) ?: return null
            val nickname = fields["nickname"]?.stringValue ?: return null
            val lower = fields["nicknameLower"]?.stringValue ?: return null
            val changed = fields["nicknameChangedAtMs"]?.integerValue ?: return null
            val created = fields["createdAtMs"]?.integerValue ?: return null
            val avatar = fields["avatar"]?.mapValue
            val avatarId = avatar?.get("id")?.stringValue.takeIf { avatar?.get("kind")?.stringValue == "preset" }
            return PlayerProfile(
                nickname = nickname,
                nicknameLower = lower,
                nicknameChangedAtMs = changed,
                avatarId = avatarId ?: AvatarCatalog.FALLBACK_ID,
                termsAcceptedVersion = (fields["termsAcceptedVersion"]?.integerValue ?: 0L).toInt(),
                createdAtMs = created,
                updatedAtMs = fields["updatedAtMs"]?.integerValue ?: created,
            )
        }

        /** `GET nicknames/{lower}` → the uid holding the reservation. */
        fun decodeNicknameOwner(body: String): String? = documentFields(body)?.get("uid")?.stringValue

        private fun documentFields(body: String): Map<String, FirestoreValue>? {
            val document = runCatching { anyAdapter.fromJson(body) }.getOrNull() as? Map<*, *> ?: return null
            val fields = document["fields"] as? Map<*, *> ?: return null
            return FirestoreValue.decodeFields(fields)
        }
    }
}

/**
 * Server clock correction. The rules check client timestamps against `request.time` ±60 s, so the
 * skew measured from the last Firestore response's `Date` header (second precision) is added.
 */
class ServerClock(private val localNowMs: () -> Long = System::currentTimeMillis) {
    /** Server minus device, in ms. Null until measured — a write first measures it with a GET. */
    @Volatile
    var offsetMs: Long? = null
        private set

    fun record(dateHeader: String?, receivedAtMs: Long = localNowMs()) {
        val serverMs = parseHttpDate(dateHeader) ?: return
        offsetMs = serverMs - receivedAtMs
    }

    fun nowMs(): Long = localNowMs() + (offsetMs ?: 0L)

    companion object {
        /** RFC 7231 IMF-fixdate (`Sun, 06 Nov 1994 08:49:37 GMT`). */
        fun parseHttpDate(value: String?): Long? = value?.let {
            runCatching {
                ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
            }.getOrNull()
        }
    }
}
