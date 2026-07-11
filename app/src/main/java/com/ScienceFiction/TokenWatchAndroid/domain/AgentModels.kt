package com.ScienceFiction.TokenWatchAndroid.domain

import java.util.UUID

interface Identifiable<out ID> {
    val id: ID
}

/** A user-added provider account. Credentials are stored outside this model. */
data class Agent(
    val provider: AgentProvider,
    override val id: UUID = UUID.randomUUID(),
    val accountLabel: String? = null,
) : Identifiable<UUID>
