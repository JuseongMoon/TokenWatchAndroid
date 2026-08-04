package com.ScienceFiction.TokenWatchAndroid.data

import com.ScienceFiction.TokenWatchAndroid.notifications.WindowObservation
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import java.time.Instant
import okio.Buffer

/** Defensive JSON codec for persisted subscription-window reset observations. */
object ResetBaselineCodec {
    fun encode(observations: Map<String, WindowObservation>): String {
        val buffer = Buffer()
        val writer = JsonWriter.of(buffer)
        writer.beginObject()
        observations.forEach { (key, observation) ->
            if (!observation.usedPercent.isFinite()) return@forEach
            writer.name(key).beginObject()
            writer.name("resetsAt")
            observation.resetsAt?.let { writer.value(it.epochSecond) } ?: writer.nullValue()
            writer.name("usedPercent").value(observation.usedPercent)
            writer.name("windowSeconds")
            observation.windowSeconds?.takeIf(Double::isFinite)?.let(writer::value) ?: writer.nullValue()
            writer.endObject()
        }
        writer.endObject()
        writer.flush()
        return buffer.readUtf8()
    }

    fun decode(json: String?): Map<String, WindowObservation> {
        if (json.isNullOrBlank()) return emptyMap()
        val output = linkedMapOf<String, WindowObservation>()
        val reader = JsonReader.of(Buffer().writeUtf8(json))
        try {
            if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) return emptyMap()
            reader.beginObject()
            while (reader.hasNext()) {
                val key = reader.nextName()
                val observation = readObservation(reader)
                if (observation != null) output[key] = observation
            }
            reader.endObject()
        } catch (_: Exception) {
            // Preserve entries decoded before a malformed tail.
        }
        return output
    }

    private fun readObservation(reader: JsonReader): WindowObservation? {
        if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
            reader.skipValue()
            return null
        }
        var resetsAt: Instant? = null
        var usedPercent: Double? = null
        var windowSeconds: Double? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "resetsAt" -> resetsAt = when (reader.peek()) {
                    JsonReader.Token.NULL -> reader.nextNull<Unit>().let { null }
                    JsonReader.Token.NUMBER -> runCatching { Instant.ofEpochSecond(reader.nextLong()) }.getOrNull()
                    else -> reader.skipValue().let { null }
                }
                "usedPercent" -> usedPercent = when (reader.peek()) {
                    JsonReader.Token.NUMBER -> runCatching { reader.nextDouble() }.getOrNull()
                    else -> reader.skipValue().let { null }
                }
                "windowSeconds" -> windowSeconds = when (reader.peek()) {
                    JsonReader.Token.NULL -> reader.nextNull<Unit>().let { null }
                    JsonReader.Token.NUMBER -> runCatching { reader.nextDouble() }.getOrNull()
                    else -> reader.skipValue().let { null }
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        val used = usedPercent?.takeIf(Double::isFinite) ?: return null
        return WindowObservation(
            resetsAt = resetsAt,
            usedPercent = used,
            windowSeconds = windowSeconds?.takeIf { it.isFinite() && it > 0.0 },
        )
    }
}
