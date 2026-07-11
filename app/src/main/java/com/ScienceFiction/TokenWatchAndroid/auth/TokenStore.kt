package com.ScienceFiction.TokenWatchAndroid.auth

import com.ScienceFiction.TokenWatchAndroid.auth.oauth.OAuthCodeClient
import com.ScienceFiction.TokenWatchAndroid.auth.oauth.OAuthException
import com.ScienceFiction.TokenWatchAndroid.domain.AgentProvider
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
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
 * Serializes credential access and refresh so concurrent usage requests cannot rotate the same
 * refresh token twice. Encrypted persistence remains delegated to [CredentialVault].
 */
class TokenStore(
    private val vault: CredentialVault,
    private val refresher: TokenRefresher,
    private val now: () -> Instant = Instant::now,
) {
    private val mutex = Mutex()

    suspend fun save(agentId: UUID, tokens: OAuthTokens) = exclusive {
        saveToVault(agentId, tokens)
    }

    suspend fun tokens(agentId: UUID): OAuthTokens? = exclusive {
        loadFromVault(agentId)
    }

    suspend fun delete(agentId: UUID) = exclusive {
        deleteFromVault(agentId)
    }

    /** Returns stored credentials, refreshing an expired OAuth credential before use. */
    suspend fun validTokens(agentId: UUID, provider: AgentProvider): OAuthTokens = exclusive {
        val stored = loadFromVault(agentId) ?: throw OAuthException.NotAuthenticated()
        if (!stored.isExpired(now())) return@exclusive stored
        if (stored.refreshToken == null) throw OAuthException.NotAuthenticated()

        refresher.refresh(provider, stored).also { refreshed ->
            saveToVault(agentId, refreshed)
        }
    }

    /** Forces one refresh after a server 401, irrespective of the stored expiry timestamp. */
    suspend fun forceRefresh(agentId: UUID, provider: AgentProvider): OAuthTokens = exclusive {
        val stored = loadFromVault(agentId)
        if (stored?.refreshToken == null) throw OAuthException.NotAuthenticated()

        refresher.refresh(provider, stored).also { refreshed ->
            saveToVault(agentId, refreshed)
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
