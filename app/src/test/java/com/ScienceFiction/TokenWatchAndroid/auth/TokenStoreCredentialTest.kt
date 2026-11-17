package com.ScienceFiction.TokenWatchAndroid.auth

import com.ScienceFiction.TokenWatchAndroid.auth.oauth.OAuthException
import com.ScienceFiction.TokenWatchAndroid.domain.AgentProvider
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * D021: a rotated refresh token the vault would not take is reported and kept in memory until a
 * later access writes it, and a credential the vault cannot read right now is neither refreshed nor
 * deleted.
 */
class TokenStoreCredentialTest {
    private val now = Instant.parse("2026-07-11T00:00:00Z")
    private val id = UUID.randomUUID()
    private val expired = OAuthTokens(accessToken = "old", refreshToken = "spent", expiresAt = now.plusSeconds(30))
    private val rotated = OAuthTokens(accessToken = "new", refreshToken = "rotated", expiresAt = now.plusSeconds(7_200))

    /** A vault whose writes and reads can be made to fail. */
    private class FlakyVault(vararg initial: Pair<UUID, OAuthTokens>) : CredentialVault {
        val values = initial.toMap().toMutableMap()
        var failingSaves = 0
        var saveAttempts = 0
        var unreadable: CredentialUnavailableException.Reason? = null

        @Synchronized override fun save(agentId: UUID, tokens: OAuthTokens) {
            saveAttempts += 1
            if (failingSaves > 0) {
                failingSaves -= 1
                throw IllegalStateException("Unable to persist encrypted credentials")
            }
            values[agentId] = tokens
        }

        @Synchronized override fun load(agentId: UUID): OAuthTokens? {
            unreadable?.let { throw CredentialUnavailableException(it) }
            return values[agentId]
        }

        @Synchronized override fun delete(agentId: UUID) {
            values.remove(agentId)
        }
    }

    private fun store(vault: CredentialVault, onRefresh: () -> OAuthTokens = { rotated }): Pair<TokenStore, () -> Int> {
        var refreshes = 0
        val store = TokenStore(
            vault = vault,
            refresher = TokenRefresher { _, _ ->
                refreshes += 1
                onRefresh()
            },
            now = { now },
        )
        return store to { refreshes }
    }

    private fun notSaved(block: suspend () -> Unit) = assertEquals(
        CredentialUnavailableException.Reason.NOT_SAVED,
        assertThrows(CredentialUnavailableException::class.java) { runBlocking { block() } }.reason,
    )

    @Test
    fun aRotatedTokenThatCannotBeSavedIsReportedAndSavedOnTheNextAccess(): Unit = runBlocking {
        val vault = FlakyVault(id to expired).apply { failingSaves = 2 }
        val (store, refreshes) = store(vault)

        // The write and its one retry both fail: the caller hears about it.
        notSaved { store.validTokens(id, AgentProvider.CLAUDE) }
        assertEquals(2, vault.saveAttempts)
        assertSame(expired, vault.values[id])

        // The next access writes the rotated token first and uses it, without spending it again.
        assertSame(rotated, store.validTokens(id, AgentProvider.CLAUDE))
        assertSame(rotated, vault.values[id])
        assertEquals(1, refreshes())
        assertSame(rotated, store.tokens(id))
    }

    @Test
    fun whileTheVaultKeepsRefusingEveryAccessReportsItAndTheTokenStaysInMemory(): Unit = runBlocking {
        val vault = FlakyVault(id to expired).apply { failingSaves = 4 }
        val (store, refreshes) = store(vault)

        notSaved { store.validTokens(id, AgentProvider.CLAUDE) }
        notSaved { store.tokens(id) }
        notSaved { store.forceRefresh(id, AgentProvider.CLAUDE) }
        // No second refresh was sent with the spent token in the meantime.
        assertEquals(1, refreshes())
        assertSame(expired, vault.values[id])

        assertSame(rotated, store.tokens(id))
        assertSame(rotated, vault.values[id])
    }

    @Test
    fun aSingleFailedWriteIsRetriedAtOnce(): Unit = runBlocking {
        val vault = FlakyVault(id to expired).apply { failingSaves = 1 }
        val (store, _) = store(vault)

        assertSame(rotated, store.validTokens(id, AgentProvider.CODEX))
        assertSame(rotated, vault.values[id])
    }

    @Test
    fun aNewSignInReplacesAnUnsavedRotation(): Unit = runBlocking {
        val vault = FlakyVault(id to expired).apply { failingSaves = 2 }
        val (store, _) = store(vault)
        notSaved { store.validTokens(id, AgentProvider.CLAUDE) }

        val signedIn = OAuthTokens(accessToken = "signed-in", refreshToken = "fresh", expiresAt = now.plusSeconds(3_600))
        store.save(id, signedIn)

        assertSame(signedIn, store.validTokens(id, AgentProvider.CLAUDE))
        assertSame(signedIn, vault.values[id])
    }

    @Test
    fun removingTheAccountDropsAnUnsavedRotation(): Unit = runBlocking {
        val vault = FlakyVault(id to expired).apply { failingSaves = 2 }
        val (store, _) = store(vault)
        notSaved { store.validTokens(id, AgentProvider.CLAUDE) }

        store.delete(id)

        assertNull(store.tokens(id))
        assertNull(vault.values[id])
    }

    @Test
    fun aCredentialTheVaultCannotReadIsNeitherRefreshedNorDeleted(): Unit = runBlocking {
        val vault = FlakyVault(id to expired).apply { unreadable = CredentialUnavailableException.Reason.KEY_MISSING }
        val (store, refreshes) = store(vault)

        for (access in listOf<suspend () -> Unit>(
            { store.validTokens(id, AgentProvider.CLAUDE) },
            { store.forceRefresh(id, AgentProvider.CLAUDE) },
            { store.tokens(id) },
        )) {
            val error = assertThrows(CredentialUnavailableException::class.java) { runBlocking { access() } }
            assertEquals(CredentialUnavailableException.Reason.KEY_MISSING, error.reason)
        }
        assertEquals(0, refreshes())
        assertSame(expired, vault.values[id])

        // Readable again: business as usual.
        vault.unreadable = null
        assertSame(rotated, store.validTokens(id, AgentProvider.CLAUDE))
    }

    @Test
    fun nothingStoredIsStillLoginRequired(): Unit = runBlocking {
        val (store, _) = store(FlakyVault())

        assertThrows(OAuthException.NotAuthenticated::class.java) {
            runBlocking { store.validTokens(id, AgentProvider.CLAUDE) }
        }
    }
}
