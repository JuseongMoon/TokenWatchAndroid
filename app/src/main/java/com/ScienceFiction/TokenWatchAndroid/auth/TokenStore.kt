package com.ScienceFiction.TokenWatchAndroid.auth

import com.ScienceFiction.TokenWatchAndroid.auth.oauth.BrowserOAuthClient
import com.ScienceFiction.TokenWatchAndroid.auth.oauth.OAuthException
import com.ScienceFiction.TokenWatchAndroid.domain.AgentProvider
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Refresh boundary used by [TokenStore]; only OAuth providers implement this operation. */
fun interface TokenRefresher {
    suspend fun refresh(provider: AgentProvider, tokens: OAuthTokens): OAuthTokens
}

/**
 * Provider refresh dispatch. Claude, Codex, and Grok rotate refresh tokens; device-flow and API-key
 * credentials have no refresh, and Cursor v1 signs in again when its ~60-day token expires.
 */
class ProviderTokenRefresher(
    private val claude: BrowserOAuthClient,
    private val codex: BrowserOAuthClient,
    private val grok: BrowserOAuthClient,
) : TokenRefresher {
    override suspend fun refresh(provider: AgentProvider, tokens: OAuthTokens): OAuthTokens =
        when (provider) {
            AgentProvider.CLAUDE -> claude.refresh(tokens)
            AgentProvider.CODEX -> codex.refresh(tokens)
            AgentProvider.GROK -> grok.refresh(tokens)
            AgentProvider.COPILOT, AgentProvider.CURSOR, AgentProvider.KIMI, AgentProvider.OPENROUTER,
            AgentProvider.DEEPSEEK, AgentProvider.POE, AgentProvider.ELEVENLABS,
            -> throw OAuthException.NotAuthenticated()
        }
}

/**
 * Serializes credential access and coalesces refreshes so concurrent usage requests cannot rotate
 * the same refresh token twice. A refresh runs in an application-owned scope: cancelling a screen
 * or background worker must not abandon a newly rotated token before encrypted persistence.
 *
 * Each agent also has a credential generation that a sign-in ([save]) or [delete] advances. A
 * refresh records the generation it started from and may persist its rotated token, or clear a
 * revoked refresh token, only while that generation is still current; the comparison and the
 * write share one critical section. A superseded refresh hands its caller the current credential
 * instead of the stale one, so a late response can never overwrite or invalidate a newer sign-in.
 * Plan-only updates ([updatePlan]) keep the generation: they do not replace the credential.
 *
 * A rotated credential the vault would not take is not dropped: it stays in [unsaved], which every
 * later access reads first and tries to write again. Its old refresh token is already spent, so the
 * copy in memory is the only one that still works, and requests keep using it while it is valid. A
 * refresh is not sent while it is unsaved (that would rotate it again with nothing on disk); a
 * request that needs one fails with [CredentialUnavailableException] (reason `NOT_SAVED`) instead.
 */
class TokenStore(
    private val vault: CredentialVault,
    private val refresher: TokenRefresher,
    private val now: () -> Instant = Instant::now,
) {
    private val mutex = Mutex()
    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshTasks = mutableMapOf<UUID, Deferred<OAuthTokens>>()

    /** Guarded by [mutex]. Missing means generation 0. */
    private val generations = mutableMapOf<UUID, Long>()

    /** Guarded by [mutex]. Rotated credentials of the current generation not yet in the vault. */
    private val unsaved = mutableMapOf<UUID, OAuthTokens>()

    /**
     * Stores a newly signed-in credential and starts a new generation. A refresh still running for
     * the previous credential is detached rather than cancelled: it finishes, but cannot persist,
     * and later callers start a fresh refresh against the new credential.
     */
    suspend fun save(agentId: UUID, tokens: OAuthTokens) = exclusive {
        saveToVault(agentId, tokens)
        unsaved.remove(agentId)
        advanceGeneration(agentId)
        refreshTasks.remove(agentId)
        Unit
    }

    suspend fun tokens(agentId: UUID): OAuthTokens? = exclusive {
        loadFromVault(agentId)
    }

    suspend fun delete(agentId: UUID) {
        // A late refresh response sees the advanced generation and cannot recreate the credential.
        val task = exclusive {
            advanceGeneration(agentId)
            unsaved.remove(agentId)
            deleteFromVault(agentId)
            refreshTasks.remove(agentId)
        }
        task?.cancel()
    }

    /** Updates only the cached account plan (live Codex lookup, or a plan in the usage response). */
    suspend fun updatePlan(agentId: UUID, plan: String) = exclusive {
        loadFromVault(agentId)?.let { stored ->
            val updated = stored.copy(plan = plan)
            // An unsaved credential takes the plan in memory too, and is written whenever it can be.
            if (agentId in unsaved) persistRotated(agentId, updated) else saveToVault(agentId, updated)
        }
    }

    /** Returns stored credentials, refreshing an expired OAuth credential before use. */
    suspend fun validTokens(agentId: UUID, provider: AgentProvider): OAuthTokens {
        val stored = exclusive { loadFromVault(agentId) } ?: throw OAuthException.NotAuthenticated()
        if (!stored.isExpired(now())) return stored
        if (stored.refreshToken == null) throw OAuthException.NotAuthenticated()
        return refreshShared(agentId, provider)
    }

    /** Forces one refresh after a server 401, irrespective of the stored expiry timestamp. */
    suspend fun forceRefresh(agentId: UUID, provider: AgentProvider): OAuthTokens {
        val stored = exclusive { loadFromVault(agentId) }
        if (stored?.refreshToken == null) throw OAuthException.NotAuthenticated()
        return refreshShared(agentId, provider)
    }

    private suspend fun refreshShared(agentId: UUID, provider: AgentProvider): OAuthTokens {
        val task = mutex.withLock {
            refreshTasks[agentId] ?: refreshScope.async {
                refreshAndPersist(agentId, provider)
            }.also { created ->
                refreshTasks[agentId] = created
                created.invokeOnCompletion {
                    refreshScope.launch {
                        mutex.withLock { refreshTasks.remove(agentId, created) }
                    }
                }
            }
        }
        return task.await()
    }

    private suspend fun refreshAndPersist(agentId: UUID, provider: AgentProvider): OAuthTokens {
        val (current, generation, held) = exclusive {
            Triple(loadFromVault(agentId), generationOf(agentId), agentId in unsaved)
        }
        if (current?.refreshToken == null) throw OAuthException.NotAuthenticated()
        // The only working refresh token is still only in memory; rotating it again would leave
        // nothing usable on disk either way, so the request fails until the vault takes it.
        if (held) throw CredentialUnavailableException(CredentialUnavailableException.Reason.NOT_SAVED)
        val rotated = try {
            refresher.refresh(provider, current)
        } catch (revoked: OAuthException.RefreshRevoked) {
            val superseded = exclusive {
                if (generationOf(agentId) != generation) return@exclusive true
                // Clear only the refresh token that was actually rejected; anything else in the
                // vault (an unrelated write) is left untouched.
                // Best effort: if this read or write fails, the next refresh is simply rejected again.
                bestEffort { loadFromVault(agentId) }
                    ?.takeIf { it.refreshToken == current.refreshToken }
                    ?.let { bestEffort { saveToVault(agentId, it.copy(refreshToken = null)) } }
                false
            }
            if (superseded) return currentCredential(agentId)
            throw revoked
        }
        val persisted = exclusive {
            if (generationOf(agentId) != generation) return@exclusive false
            persistRotated(agentId, rotated)
            true
        }
        return if (persisted) rotated else currentCredential(agentId)
    }

    /** The credential of the generation that superseded a refresh, e.g. a sign-in made meanwhile. */
    private suspend fun currentCredential(agentId: UUID): OAuthTokens =
        exclusive { loadFromVault(agentId) } ?: throw OAuthException.NotAuthenticated()

    /**
     * Writes a rotated credential, retrying once. If both writes fail it is kept in [unsaved] for
     * later accesses to use and write; the caller still gets it. Must run inside [exclusive] so the
     * generation check and the write cannot be separated.
     */
    private suspend fun persistRotated(agentId: UUID, tokens: OAuthTokens) {
        try {
            saveToVault(agentId, tokens)
        } catch (cancelled: CancellationException) {
            unsaved[agentId] = tokens
            throw cancelled
        } catch (_: Exception) {
            try {
                saveToVault(agentId, tokens)
            } catch (cancelled: CancellationException) {
                unsaved[agentId] = tokens
                throw cancelled
            } catch (_: Exception) {
                unsaved[agentId] = tokens
                return
            }
        }
        unsaved.remove(agentId)
    }

    private suspend fun <T> bestEffort(block: suspend () -> T): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private fun generationOf(agentId: UUID): Long = generations[agentId] ?: 0L

    private fun advanceGeneration(agentId: UUID) {
        generations[agentId] = generationOf(agentId) + 1
    }

    /**
     * The current credential. A rotated one still waiting in [unsaved] is tried on the vault first
     * and returned either way; it stays in memory until a write succeeds. Must run inside
     * [exclusive].
     */
    private suspend fun loadFromVault(agentId: UUID): OAuthTokens? {
        unsaved[agentId]?.let { pending ->
            if (bestEffort { saveToVault(agentId, pending) } != null) unsaved.remove(agentId)
            return pending
        }
        return withContext(Dispatchers.IO) { vault.load(agentId) }
    }

    private suspend fun saveToVault(agentId: UUID, tokens: OAuthTokens) =
        withContext(Dispatchers.IO) { vault.save(agentId, tokens) }

    private suspend fun deleteFromVault(agentId: UUID) =
        withContext(Dispatchers.IO) { vault.delete(agentId) }

    private suspend fun <T> exclusive(block: suspend () -> T): T {
        mutex.lock()
        return try {
            block()
        } finally {
            mutex.unlock()
        }
    }
}
