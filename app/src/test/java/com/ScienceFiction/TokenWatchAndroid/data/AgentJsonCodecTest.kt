package com.ScienceFiction.TokenWatchAndroid.data

import com.ScienceFiction.TokenWatchAndroid.domain.Agent
import com.ScienceFiction.TokenWatchAndroid.domain.AgentProvider
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentJsonCodecTest {
    @Test
    fun unsupportedProviderIsDroppedWithoutLosingSupportedAccounts() {
        // windsurf left in the 2026-08 audit and stayed out; cursor came back in 2026-09.
        val result = AgentJsonCodec.decodeDetailed(
            """[{"id":"11111111-1111-1111-1111-111111111111","provider":"claude"},{"id":"22222222-2222-2222-2222-222222222222","provider":"windsurf"},{"id":"33333333-3333-3333-3333-333333333333","provider":"codex"}]""",
        )
        assertEquals(listOf(AgentProvider.CLAUDE, AgentProvider.CODEX), result.agents.map { it.provider })
        assertEquals(listOf(UUID.fromString("22222222-2222-2222-2222-222222222222")), result.droppedIds)
    }

    @Test
    fun reintroducedProvidersDecodeAgain() {
        val result = AgentJsonCodec.decodeDetailed(
            """[{"id":"44444444-4444-4444-4444-444444444444","provider":"poe"},{"id":"55555555-5555-5555-5555-555555555555","provider":"grok"},{"id":"66666666-6666-6666-6666-666666666666","provider":"cursor"}]""",
        )
        assertEquals(listOf(AgentProvider.POE, AgentProvider.GROK, AgentProvider.CURSOR), result.agents.map { it.provider })
        assertTrue(result.droppedIds.isEmpty())
    }
    private val firstId = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val secondId = UUID.fromString("00000000-0000-0000-0000-000000000002")

    @Test
    fun roundTripPreservesAgentOrderAndLabels() {
        val agents = listOf(
            Agent(provider = AgentProvider.CLAUDE, id = firstId, accountLabel = "pro · 개인"),
            Agent(provider = AgentProvider.CODEX, id = secondId, accountLabel = null),
        )

        assertEquals(agents, AgentJsonCodec.decode(AgentJsonCodec.encode(agents)))
    }

    @Test
    fun malformedTopLevelValuesAreIgnored() {
        assertTrue(AgentJsonCodec.decode(null).isEmpty())
        assertTrue(AgentJsonCodec.decode("").isEmpty())
        assertTrue(AgentJsonCodec.decode("not-json").isEmpty())
        assertTrue(AgentJsonCodec.decode("{\"provider\":\"claude\"}").isEmpty())
    }

    @Test
    fun invalidEntriesAreSkippedWithoutDroppingValidNeighbors() {
        val json = """
            [
              {"id":"$firstId","provider":"claude","accountLabel":"pro","futureField":42},
              {"id":"not-a-uuid","provider":"codex","accountLabel":null},
              {"id":"$secondId","provider":"unknown","accountLabel":null},
              17,
              {"id":"$secondId","provider":"codex","accountLabel":null}
            ]
        """.trimIndent()

        assertEquals(
            listOf(
                Agent(provider = AgentProvider.CLAUDE, id = firstId, accountLabel = "pro"),
                Agent(provider = AgentProvider.CODEX, id = secondId, accountLabel = null),
            ),
            AgentJsonCodec.decode(json),
        )
    }

    @Test
    fun validPrefixSurvivesMalformedTail() {
        val json = """[{"id":"$firstId","provider":"claude","accountLabel":null},{"""

        assertEquals(
            listOf(Agent(provider = AgentProvider.CLAUDE, id = firstId, accountLabel = null)),
            AgentJsonCodec.decode(json),
        )
    }
}
