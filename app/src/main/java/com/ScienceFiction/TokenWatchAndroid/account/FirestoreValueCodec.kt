package com.ScienceFiction.TokenWatchAndroid.account

import com.ScienceFiction.TokenWatchAndroid.domain.account.AvatarCatalog
import com.ScienceFiction.TokenWatchAndroid.domain.account.NicknamePolicy
import com.ScienceFiction.TokenWatchAndroid.domain.account.PlayerProfile
import com.squareup.moshi.Moshi
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Firestore REST pieces for the app account profile (pure, unit tested): value decoding, reading the
 * profile document, and server clock skew from the `Date` header. Profile writes go through the V2
 * server callable (`setProfile`). No Firestore SDK — the same "HTTPS + JSON" surface as the announcement
 * feed, mirroring iOS `FirestoreREST.swift`.
 */

/** The three Firestore value kinds the profile uses. Integers travel as decimal strings. */
sealed class FirestoreValue {
    data class StringValue(val value: String) : FirestoreValue()
    data class IntegerValue(val value: Long) : FirestoreValue()
    data class MapValue(val fields: Map<String, FirestoreValue>) : FirestoreValue()

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

/** The profile document's decoding. */
class PlayerDocuments(val projectId: String) {
    companion object {
        private val anyAdapter = Moshi.Builder().build().adapter(Any::class.java)

        /**
         * `GET players/{uid}` → profile; null when a required field (the tag included) is missing. An
         * avatar of any kind other than `preset` reads as the fallback avatar.
         */
        fun decodeProfile(body: String): PlayerProfile? {
            val fields = documentFields(body) ?: return null
            val nickname = fields["nickname"]?.stringValue ?: return null
            val lower = fields["nicknameLower"]?.stringValue ?: return null
            val tag = fields["tag"]?.stringValue?.takeIf(NicknamePolicy::isValidTag) ?: return null
            val changed = fields["nicknameChangedAtMs"]?.integerValue ?: return null
            val created = fields["createdAtMs"]?.integerValue ?: return null
            val avatar = fields["avatar"]?.mapValue
            val avatarId = avatar?.get("id")?.stringValue.takeIf { avatar?.get("kind")?.stringValue == "preset" }
            return PlayerProfile(
                nickname = nickname,
                nicknameLower = lower,
                tag = tag,
                nicknameChangedAtMs = changed,
                avatarId = avatarId ?: AvatarCatalog.FALLBACK_ID,
                termsAcceptedVersion = (fields["termsAcceptedVersion"]?.integerValue ?: 0L).toInt(),
                createdAtMs = created,
                updatedAtMs = fields["updatedAtMs"]?.integerValue ?: created,
            )
        }

        private fun documentFields(body: String): Map<String, FirestoreValue>? {
            val document = runCatching { anyAdapter.fromJson(body) }.getOrNull() as? Map<*, *> ?: return null
            val fields = document["fields"] as? Map<*, *> ?: return null
            return FirestoreValue.decodeFields(fields)
        }
    }
}

/**
 * Server clock correction, so the 30-day nickname interval can be shown on the server's clock before the
 * server judges it: the skew measured from the last Firestore response's `Date` header (second
 * precision) is added to the device time.
 */
class ServerClock(private val localNowMs: () -> Long = System::currentTimeMillis) {
    /** Server minus device, in ms. Null until measured — a GET measures it when needed. */
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
