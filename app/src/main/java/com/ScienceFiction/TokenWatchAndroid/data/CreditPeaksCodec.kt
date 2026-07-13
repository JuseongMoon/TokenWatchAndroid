package com.ScienceFiction.TokenWatchAndroid.data

import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import okio.Buffer

/** Defensive JSON codec for non-secret, per-agent observed credit-balance peaks. */
object CreditPeaksCodec {
    fun encode(peaks: Map<String, Double>): String {
        val buffer = Buffer()
        val writer = JsonWriter.of(buffer)
        writer.beginObject()
        peaks.forEach { (key, value) ->
            if (value.isFinite()) writer.name(key).value(value)
        }
        writer.endObject()
        writer.flush()
        return buffer.readUtf8()
    }

    fun decode(json: String?): Map<String, Double> {
        if (json.isNullOrBlank()) return emptyMap()

        val reader = JsonReader.of(Buffer().writeUtf8(json))
        val peaks = linkedMapOf<String, Double>()
        try {
            if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) return emptyMap()
            reader.beginObject()
            while (reader.hasNext()) {
                val key = reader.nextName()
                val value = when (reader.peek()) {
                    JsonReader.Token.NUMBER -> runCatching { reader.nextDouble() }.getOrNull()
                    else -> {
                        reader.skipValue()
                        null
                    }
                }
                if (value != null && value.isFinite()) peaks[key] = value
            }
            reader.endObject()
        } catch (_: Exception) {
            // Keep valid entries decoded before a malformed tail; startup must remain recoverable.
        }
        return peaks
    }
}
