package com.ScienceFiction.TokenWatchAndroid.data

import com.ScienceFiction.TokenWatchAndroid.domain.Agent
import com.ScienceFiction.TokenWatchAndroid.domain.AgentProvider
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import java.util.UUID
import okio.Buffer

/** Ordered, defensive JSON codec for the non-secret portion of added agents. */
object AgentJsonCodec {
    data class DecodeResult(val agents: List<Agent>, val droppedIds: List<UUID>)
    fun encode(agents: List<Agent>): String {
        val buffer = Buffer()
        val writer = JsonWriter.of(buffer)
        writer.beginArray()
        agents.forEach { agent ->
            writer.beginObject()
            writer.name("id").value(agent.id.toString())
            writer.name("provider").value(agent.provider.wireId)
            writer.name("accountLabel")
            if (agent.accountLabel == null) writer.nullValue() else writer.value(agent.accountLabel)
            writer.endObject()
        }
        writer.endArray()
        writer.flush()
        return buffer.readUtf8()
    }

    fun decode(json: String?): List<Agent> = decodeDetailed(json).agents

    fun decodeDetailed(json: String?): DecodeResult {
        if (json.isNullOrBlank()) return DecodeResult(emptyList(), emptyList())

        val reader = JsonReader.of(Buffer().writeUtf8(json))
        val agents = mutableListOf<Agent>()
        val dropped = mutableListOf<UUID>()
        try {
            if (reader.peek() != JsonReader.Token.BEGIN_ARRAY) return DecodeResult(emptyList(), emptyList())
            reader.beginArray()
            while (reader.hasNext()) {
                val decoded = readAgent(reader)
                decoded.agent?.let(agents::add)
                decoded.droppedId?.let(dropped::add)
            }
            reader.endArray()
        } catch (_: Exception) {
            // Keep valid entries decoded before a malformed tail; never crash startup.
        }
        return DecodeResult(agents, dropped)
    }

    private data class DecodedAgent(val agent: Agent? = null, val droppedId: UUID? = null)

    private fun readAgent(reader: JsonReader): DecodedAgent {
        if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
            reader.skipValue()
            return DecodedAgent()
        }

        var id: String? = null
        var providerId: String? = null
        var accountLabel: String? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "id" -> id = reader.readNullableString()
                "provider" -> providerId = reader.readNullableString()
                "accountLabel" -> accountLabel = reader.readNullableString()
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        val storedId = id ?: return DecodedAgent()
        val uuid = try {
            UUID.fromString(storedId)
        } catch (_: IllegalArgumentException) {
            null
        } ?: return DecodedAgent()
        val rawProvider = providerId ?: return DecodedAgent()
        val provider = AgentProvider.fromWireId(rawProvider)
            ?: return DecodedAgent(droppedId = uuid)
        return DecodedAgent(agent = Agent(provider = provider, id = uuid, accountLabel = accountLabel))
    }

    private fun JsonReader.readNullableString(): String? = when (peek()) {
        JsonReader.Token.STRING -> nextString()
        JsonReader.Token.NULL -> nextNull<String>()
        else -> {
            skipValue()
            null
        }
    }
}
