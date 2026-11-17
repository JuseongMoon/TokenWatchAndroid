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
 * D021 / I004: a rotated refresh token the vault would not take is kept in memory, used while it is
 * valid, and written on every later access until the vault takes it. It is never refreshed again
 * while unsaved: a request that needs a refresh then fails with `NOT_SAVED`. A credential the vault
 * cannot read right now is neither refreshed nor deleted.
 */
class TokenStoreCredentialTest {
    private val start = Instant.parse("2026-07-11T00:00:00Z")
    private var clock = start
    private val id = UUID.randomUUID()
    private val expired = OAuthTokens(accessToken = "old", refreshToken = "spent", expiresAt = start.plusSeconds(30))
    private val rotated = OAuthTokens(accessToken = "new", refreshToken = "rotated", expiresAt = start.plusSeconds(7_200))

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

    /** The refresh token each refresh was sent with. */
    private val sent = mutableListOf<String?>()
    private val refreshes get() = sent.size

    private fun store(vault: CredentialVault) = TokenStore(
        vault = vault,
        refresher = TokenRefresher { _, tokens ->
            sent += tokens.refreshToken
            if (tokens.refreshToken == "rotated") rotated.copy(refreshToken = "rotated-again") else rotated
        },
        now = { clock },
    )

    private fun notSaved(block: suspend () -> Unit) = assertEquals(
        CredentialUnavailableException.Reason.NOT_SAVED,
        assertThrows(CredentialUnavailableException::class.java) { runBlocking { block() } }.reason,
    )

    @Test
    fun aRotatedTokenThatCannotBeSavedStillServesTheRequestAndIsSavedOnTheNextAccess(): Unit = runBlocking {
        val vault = FlakyVault(id to expired).apply { failingSaves = 2 }
        val store = store(vault)

        // The write and its one retry both fail; the request still gets the working token.
        assertSame(rotated, store.validTokens(id, AgentProvider.CLAUDE))
        assertEquals(2, vault.saveAttempts)
        assertSame(expired, vault.values[id])

        // The next access writes it first, and nothing is refreshed again.
        assertSame(rotated, store.validTokens(id, AgentProvider.CLAUDE))
        assertSame(rotated, vault.values[id])
        assertEquals(1, refreshes)
    }

    @Test
    fun whileTheVaultKeepsRefusingAValidMemoryTokenKeepsWorkingAndEveryAccessTriesToSaveIt(): Unit = runBlocking {
        val vault = FlakyVault(id to expired).apply { failingSaves = 4 }
        val store = store(vault)

        assertSame(rotated, store.validTokens(id, AgentProvider.CLAUDE))
        assertSame(rotated, store.validTokens(id, AgentProvider.CLAUDE))
        assertSame(rotated, store.tokens(id))
        assertEquals(4, vault.saveAttempts)
        assertSame(expired, vault.values[id])
        assertEquals(1, refreshes)

        // The vault recovers: the next access writes the token from memory.
        assertSame(rotated, store.tokens(id))
        assertSame(rotated, vault.values[id])
    }

    @Test
    fun anUnsavedTokenIsNotRefreshedAgainARequestThatNeedsOneFailsNotSaved(): Unit = runBlocking {
        val vault = FlakyVault(id to expired).apply { failingSaves = Int.MAX_VALUE }
        val store = store(vault)
        assertSame(rotated, store.validTokens(id, AgentProvider.CLAUDE))

        // The memory token expires while the vault still refuses it.
        clock = start.plusSeconds(8_000)
        notSaved { store.validTokens(id, AgentProvider.CLAUDE) }
        // A server 401 needs a refresh too.
        clock = start
        notSaved { store.forceRefresh(id, AgentProvider.CLAUDE) }
        assertEquals(listOf<String?>("spent"), sent)
        assertSame(expired, vault.values[id])

        // Once the vault takes it, the refresh goes out with the token from memory, never the spent one.
        vault.failingSaves = 0
        clock = start.plusSeconds(8_000)
        assertEquals("rotated-again", store.validTokens(id, AgentProvider.CLAUDE).refreshToken)
        assertEquals(listOf<String?>("spent", "rotated"), sent)
        assertEquals("rotated-again", vault.values[id]?.refreshToken)
    }

    @Test
    fun aPlanUpdateWhileUnsavedStaysInMemoryAndIsWrittenLater(): Unit = runBlocking {
        val vault = FlakyVault(id to expired).apply { failingSaves = Int.MAX_VALUE }
        val store = store(vault)
        assertSame(rotated, store.validTokens(id, AgentProvider.GROK))

        store.updatePlan(id, "SuperGrok")

        assertEquals("SuperGrok", store.tokens(id)?.plan)
        assertSame(expired, vault.values[id])
        vault.failingSaves = 0
        assertEquals("SuperGrok", store.tokens(id)?.plan)
        assertEquals("SuperGrok", vault.values[id]?.plan)
        assertEquals("rotated", vault.values[id]?.refreshToken)
    }

    @Test
    fun aSingleFailedWriteIsRetriedAtOnce(): Unit = runBlocking {
        val vault = FlakyVault(id to expired).apply { failingSaves = 1 }
        val store = store(vault)

        assertSame(rotated, store.validTokens(id, AgentProvider.CODEX))
        assertSame(rotated, vault.values[id])
    }

    @Test
    fun aNewSignInReplacesAnUnsavedRotation(): Unit = runBlocking {
        val vault = FlakyVault(id to expired).apply { failingSaves = 2 }
        val store = store(vault)
        store.validTokens(id, AgentProvider.CLAUDE)

        val signedIn = OAuthTokens(accessToken = "signed-in", refreshToken = "fresh", expiresAt = start.plusSeconds(3_600))
        store.save(id, signedIn)

        assertSame(signedIn, store.validTokens(id, AgentProvider.CLAUDE))
        assertSame(signedIn, vault.values[id])
    }

    @Test
    fun removingTheAccountDropsAnUnsavedRotation(): Unit = runBlocking {
        val vault = FlakyVault(id to expired).apply { failingSaves = 2 }
        val store = store(vault)
        store.validTokens(id, AgentProvider.CLAUDE)

        store.delete(id)

        assertNull(store.tokens(id))
        assertNull(vault.values[id])
    }

    @Test
    fun aCredentialTheVaultCannotReadIsNeitherRefreshedNorDeleted(): Unit = runBlocking {
        val vault = FlakyVault(id to expired).apply { unreadable = CredentialUnavailableException.Reason.KEY_MISSING }
        val store = store(vault)

        for (access in listOf<suspend () -> Unit>(
            { store.validTokens(id, AgentProvider.CLAUDE) },
            { store.forceRefresh(id, AgentProvider.CLAUDE) },
            { store.tokens(id) },
        )) {
            val error = assertThrows(CredentialUnavailableException::class.java) { runBlocking { access() } }
            assertEquals(CredentialUnavailableException.Reason.KEY_MISSING, error.reason)
        }
        assertEquals(0, refreshes)
        assertSame(expired, vault.values[id])

        // Readable again: business as usual.
        vault.unreadable = null
        assertSame(rotated, store.validTokens(id, AgentProvider.CLAUDE))
    }

    @Test
    fun nothingStoredIsStillLoginRequired(): Unit = runBlocking {
        val store = store(FlakyVault())

        assertThrows(OAuthException.NotAuthenticated::class.java) {
            runBlocking { store.validTokens(id, AgentProvider.CLAUDE) }
        }
    }
}
