package com.ScienceFiction.TokenWatchAndroid.domain

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

class CollectionLogicTest {
    @Test
    fun movingAnAgentSwapsWithItsNeighbor() {
        val agents = agents()

        assertEquals(
            listOf(AgentProvider.CODEX, AgentProvider.CLAUDE, AgentProvider.COPILOT),
            agents.reordered(agents[1].id, -1).map(Agent::provider),
        )
        assertEquals(
            listOf(AgentProvider.CLAUDE, AgentProvider.COPILOT, AgentProvider.CODEX),
            agents.reordered(agents[1].id, 1).map(Agent::provider),
        )
    }

    @Test
    fun boundariesAndUnknownIdsAreNoOps() {
        val agents = agents()

        assertEquals(agents, agents.reordered(agents.first().id, -1))
        assertEquals(agents, agents.reordered(agents.last().id, 1))
        assertEquals(agents, agents.reordered(UUID.randomUUID(), -1))
    }

    @Test
    fun movingUpThenDownRestoresTheOriginalOrder() {
        val agents = agents()
        val movingId = agents.last().id

        val movedUp = agents.reordered(movingId, -1)
        assertEquals(agents, movedUp.reordered(movingId, 1))
    }

    private fun agents() = listOf(
        Agent(AgentProvider.CLAUDE, UUID.fromString("00000000-0000-0000-0000-000000000001")),
        Agent(AgentProvider.CODEX, UUID.fromString("00000000-0000-0000-0000-000000000002")),
        Agent(AgentProvider.COPILOT, UUID.fromString("00000000-0000-0000-0000-000000000003")),
    )
}
