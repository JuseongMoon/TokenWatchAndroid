package com.ScienceFiction.TokenWatchAndroid.data

import com.ScienceFiction.TokenWatchAndroid.domain.Announcement
import com.ScienceFiction.TokenWatchAndroid.domain.AnnouncementFeed
import com.ScienceFiction.TokenWatchAndroid.domain.LocalizedText
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import okio.Buffer

/**
 * Ordered, defensive codec for the announcement feed payload. Used for both the network response
 * body and the on-device cache, so a round trip has to be lossless.
 *
 * Decoding is deliberately lenient per item: an entry missing its id or publish time is dropped
 * while its neighbours survive, unknown fields are skipped and an unknown kind degrades to NOTICE.
 * That is what lets an older build keep working against a newer feed. The one hard stop is the
 * schema version — an unexpected one discards the whole feed, which is the forward-compatibility
 * escape hatch the server relies on.
 */
object AnnouncementCodec {

    fun encode(feed: AnnouncementFeed): String {
        val buffer = Buffer()
        val writer = JsonWriter.of(buffer)
        writer.beginObject()
        writer.name("schemaVersion").value(feed.schemaVersion)
        feed.generatedAt?.let { writer.name("generatedAt").value(it) }
        writer.name("items")
        writer.beginArray()
        feed.items.forEach { item ->
            writer.beginObject()
            writer.name("id").value(item.id)
            writer.name("kind").value(item.kind.wireId)
            writer.name("priority").value(item.priority)
            writer.name("publishedAt").value(item.publishedAt)
            item.startAt?.let { writer.name("startAt").value(it) }
            item.endAt?.let { writer.name("endAt").value(it) }
            writer.name("platform").value(item.platform)
            item.minAppVersion?.let { writer.name("minAppVersion").value(it) }
            item.maxAppVersion?.let { writer.name("maxAppVersion").value(it) }
            writer.name("title"); writeLocalized(writer, item.title)
            writer.name("body"); writeLocalized(writer, item.body)
            writer.endObject()
        }
        writer.endArray()
        writer.endObject()
        writer.flush()
        return buffer.readUtf8()
    }

    /** Decodes the feed payload itself. Null means "unusable" — the caller keeps its previous feed. */
    fun decode(json: String?): AnnouncementFeed? {
        if (json.isNullOrBlank()) return null
        val reader = JsonReader.of(Buffer().writeUtf8(json))
        var schemaVersion: Int? = null
        var generatedAt: Long? = null
        val items = mutableListOf<Announcement>()
        try {
            if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) return null
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "schemaVersion" -> schemaVersion = reader.nextInt()
                    "generatedAt" -> generatedAt = reader.readNullableLong()
                    "items" -> readItems(reader, items)
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
        } catch (_: Exception) {
            // Keep whatever parsed before a malformed tail; a broken feed must never crash startup.
        }
        if (schemaVersion != AnnouncementFeed.SUPPORTED_SCHEMA_VERSION) return null
        return AnnouncementFeed(schemaVersion, generatedAt, items)
    }

    private fun readItems(reader: JsonReader, into: MutableList<Announcement>) {
        if (reader.peek() != JsonReader.Token.BEGIN_ARRAY) {
            reader.skipValue()
            return
        }
        reader.beginArray()
        while (reader.hasNext()) {
            readItem(reader)?.let(into::add)
        }
        reader.endArray()
    }

    private fun readItem(reader: JsonReader): Announcement? {
        if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
            reader.skipValue()
            return null
        }
        var id: String? = null
        var kind: Announcement.Kind = Announcement.Kind.NOTICE
        var priority = 0
        var publishedAt: Long? = null
        var startAt: Long? = null
        var endAt: Long? = null
        var platform = Announcement.PLATFORM_ALL
        var minAppVersion: String? = null
        var maxAppVersion: String? = null
        var title = LocalizedText()
        var body = LocalizedText()
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "id" -> id = reader.readNullableString()
                "kind" -> kind = Announcement.Kind.fromWireId(reader.readNullableString())
                "priority" -> priority = reader.readNullableLong()?.toInt() ?: 0
                "publishedAt" -> publishedAt = reader.readNullableLong()
                "startAt" -> startAt = reader.readNullableLong()
                "endAt" -> endAt = reader.readNullableLong()
                "platform" -> platform = reader.readNullableString() ?: Announcement.PLATFORM_ALL
                "minAppVersion" -> minAppVersion = reader.readNullableString()
                "maxAppVersion" -> maxAppVersion = reader.readNullableString()
                "title" -> title = readLocalized(reader)
                "body" -> body = readLocalized(reader)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        // Both are load-bearing: the id keys dismissed/seen state and the date drives ordering.
        if (id.isNullOrEmpty() || publishedAt == null) return null
        return Announcement(
            id = id,
            kind = kind,
            priority = priority,
            publishedAt = publishedAt,
            startAt = startAt,
            endAt = endAt,
            platform = platform,
            minAppVersion = minAppVersion,
            maxAppVersion = maxAppVersion,
            title = title,
            body = body,
        )
    }

    /** Ordered id list (dismissed/seen). Order matters: the cap drops the oldest entries first. */
    fun encodeIds(ids: List<String>): String {
        val buffer = Buffer()
        val writer = JsonWriter.of(buffer)
        writer.beginArray()
        ids.forEach(writer::value)
        writer.endArray()
        writer.flush()
        return buffer.readUtf8()
    }

    fun decodeIds(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        val reader = JsonReader.of(Buffer().writeUtf8(json))
        val ids = mutableListOf<String>()
        try {
            if (reader.peek() != JsonReader.Token.BEGIN_ARRAY) return emptyList()
            reader.beginArray()
            while (reader.hasNext()) {
                if (reader.peek() == JsonReader.Token.STRING) ids.add(reader.nextString()) else reader.skipValue()
            }
            reader.endArray()
        } catch (_: Exception) {
            // Same contract as above: keep what parsed.
        }
        return ids
    }

    private fun writeLocalized(writer: JsonWriter, text: LocalizedText) {
        writer.beginObject()
        text.ko?.let { writer.name("ko").value(it) }
        text.en?.let { writer.name("en").value(it) }
        writer.endObject()
    }

    private fun readLocalized(reader: JsonReader): LocalizedText {
        if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
            reader.skipValue()
            return LocalizedText()
        }
        var ko: String? = null
        var en: String? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "ko" -> ko = reader.readNullableString()
                "en" -> en = reader.readNullableString()
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return LocalizedText(ko, en)
    }

    private fun JsonReader.readNullableString(): String? =
        if (peek() == JsonReader.Token.NULL) { nextNull<String>(); null } else nextString()

    private fun JsonReader.readNullableLong(): Long? =
        if (peek() == JsonReader.Token.NULL) { nextNull<Long>(); null } else nextLong()
}
