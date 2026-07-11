package com.ScienceFiction.TokenWatchAndroid.network.orchestration

import com.ScienceFiction.TokenWatchAndroid.domain.AgentSnapshot
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Per-agent 429 backoff and exact last-success cache from the clean iOS baseline. */
class RateLimitGate(
    private val now: () -> Instant = Instant::now,
    private val defaultBackoff: Duration = DEFAULT_BACKOFF,
) {
    private val mutex = Mutex()
    private val blockedUntil = mutableMapOf<UUID, Instant>()
    private val lastGood = mutableMapOf<UUID, AgentSnapshot>()

    /** Returns the deadline while blocked, or clears an elapsed deadline. */
    suspend fun blocked(agentId: UUID): Instant? = mutex.withLock {
        val until = blockedUntil[agentId] ?: return@withLock null
        if (until.isAfter(now())) return@withLock until
        blockedUntil.remove(agentId)
        null
    }

    /** Uses a future Retry-After value, otherwise the clean-baseline five-minute fallback. */
    suspend fun recordRateLimit(agentId: UUID, retryAfter: Instant?) = mutex.withLock {
        val current = now()
        blockedUntil[agentId] = retryAfter
            ?.takeIf { it.isAfter(current) }
            ?: current.plus(defaultBackoff)
    }

    suspend fun recordSuccess(agentId: UUID, snapshot: AgentSnapshot) = mutex.withLock {
        blockedUntil.remove(agentId)
        lastGood[agentId] = snapshot
    }

    suspend fun lastGood(agentId: UUID): AgentSnapshot? = mutex.withLock {
        lastGood[agentId]
    }

    companion object {
        val DEFAULT_BACKOFF: Duration = Duration.ofMinutes(5)
    }
}
