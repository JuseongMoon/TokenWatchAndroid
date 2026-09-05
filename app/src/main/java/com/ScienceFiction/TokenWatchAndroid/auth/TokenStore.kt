package com.ScienceFiction.TokenWatchAndroid.auth

import com.ScienceFiction.TokenWatchAndroid.auth.oauth.OAuthCodeClient
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

/** Clean-baseline provider refresh dispatch: Claude and Codex, and no other provider. */
class ProviderTokenRefresher(
    private val claude: OAuthCodeClient,
    private val codex: OAuthCodeClient,
) : TokenRefresher {
    override suspend fun refresh(provider: AgentProvider, tokens: OAuthTokens): OAuthTokens =
        when (provider) {
            AgentProvider.CLAUDE -> claude.refresh(tokens)
            AgentProvider.CODEX -> codex.refresh(tokens)
            else -> throw OAuthException.NotAuthenticated()
        }
}

/**
 * Serializes credential access and coalesces refreshes so concurrent usage requests cannot rotate
 * the same refresh token twice. A refresh runs in an application-owned scope: cancelling a screen
 * or background worker must not abandon a newly rotated token before encrypted persistence.
 */
class TokenStore(
    private val vault: CredentialVault,
    private val refresher: TokenRefresher,
    private val now: () -> Instant = Instant::now,
) {
    private val mutex = Mutex()
    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshTasks = mutableMapOf<UUID, Deferred<OAuthTokens>>()

    suspend fun save(agentId: UUID, tokens: OAuthTokens) = exclusive {
        saveToVault(agentId, tokens)
    }

    suspend fun tokens(agentId: UUID): OAuthTokens? = exclusive {
        loadFromVault(agentId)
    }

    suspend fun delete(agentId: UUID) {
        val task = mutex.withLock { refreshTasks.remove(agentId) }
        task?.cancel()
        exclusive { deleteFromVault(agentId) }
    }

    /** Updates only the cached account plan after a live Codex account lookup. */
    suspend fun updatePlan(agentId: UUID, plan: String) = exclusive {
        loadFromVault(agentId)?.let { stored ->
            saveToVault(agentId, stored.copy(plan = plan))
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
        val current = exclusive { loadFromVault(agentId) }
        if (current?.refreshToken == null) throw OAuthException.NotAuthenticated()
        return try {
            refresher.refresh(provider, current).also { persistRotated(agentId, it) }
        } catch (revoked: OAuthException.RefreshRevoked) {
            exclusive { loadFromVault(agentId) }
                ?.takeIf { it.refreshToken != null }
                ?.let {
                    persistRotated(agentId, it.copy(refreshToken = null))
                }
            throw revoked
        }
    }

    private suspend fun persistRotated(agentId: UUID, tokens: OAuthTokens) {
        exclusive {
            try {
                saveToVault(agentId, tokens)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Losing a rotated token is unrecoverable, so retry the encrypted write once.
                try {
                    saveToVault(agentId, tokens)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // The current in-memory request may still succeed; a later call will require login.
                }
            }
        }
    }

    private suspend fun loadFromVault(agentId: UUID): OAuthTokens? =
        withContext(Dispatchers.IO) { vault.load(agentId) }

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
