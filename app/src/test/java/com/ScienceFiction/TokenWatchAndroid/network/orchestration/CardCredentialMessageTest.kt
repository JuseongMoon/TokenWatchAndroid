package com.ScienceFiction.TokenWatchAndroid.network.orchestration

import com.ScienceFiction.TokenWatchAndroid.analytics.FetchErrorReason
import com.ScienceFiction.TokenWatchAndroid.auth.CredentialKeys
import com.ScienceFiction.TokenWatchAndroid.auth.CredentialStorage
import com.ScienceFiction.TokenWatchAndroid.auth.CredentialUnavailableException
import com.ScienceFiction.TokenWatchAndroid.auth.CredentialVault
import com.ScienceFiction.TokenWatchAndroid.auth.EncryptedCredentialVault
import com.ScienceFiction.TokenWatchAndroid.auth.OAuthTokens
import com.ScienceFiction.TokenWatchAndroid.auth.TokenRefresher
import com.ScienceFiction.TokenWatchAndroid.auth.TokenStore
import com.ScienceFiction.TokenWatchAndroid.auth.oauth.OAuthException
import com.ScienceFiction.TokenWatchAndroid.domain.AgentProvider
import com.ScienceFiction.TokenWatchAndroid.domain.AuthKind
import com.ScienceFiction.TokenWatchAndroid.localization.L10n
import com.ScienceFiction.TokenWatchAndroid.localization.Lang
import com.ScienceFiction.TokenWatchAndroid.localization.signInRefillsLostCard
import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkTransport
import com.ScienceFiction.TokenWatchAndroid.network.core.ProviderUsageClient
import com.ScienceFiction.TokenWatchAndroid.network.core.UsageException
import com.ScienceFiction.TokenWatchAndroid.network.providers.subscription.CodexAccountClient
import java.time.Instant
import java.util.UUID
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A card that lost its stored credential says "sign in again" only where signing in again refills
 * that card (email providers). For Copilot and the API key providers a sign-in adds a new card, so
 * the text says to remove this card and add it again. Only the wording changes: the analytics
 * bucket stays the same, and a token the server turned down keeps its usual text.
 */
class CardCredentialMessageTest {
    private val now = Instant.parse("2026-11-22T00:00:00Z")
    private val emailProviders = setOf(AgentProvider.CLAUDE, AgentProvider.CODEX, AgentProvider.GROK, AgentProvider.CURSOR)
    private val removeCardProviders = setOf(
        AgentProvider.COPILOT, AgentProvider.KIMI, AgentProvider.OPENROUTER, AgentProvider.DEEPSEEK,
        AgentProvider.POE, AgentProvider.ELEVENLABS,
    )

    // region Pure choice

    @Test fun theNewTextsAreExactlyTheAgreedStrings() {
        val ko = L10n(Lang.KO)
        val en = L10n(Lang.EN)
        assertEquals("이 기기의 보안 저장소 키를 찾을 수 없습니다. 이 카드를 지우고 다시 추가해 주세요.", ko.errCredentialKeyMissingRemoveCard)
        assertEquals("This device's secure storage key is missing. Remove this card and add it again.", en.errCredentialKeyMissingRemoveCard)
        assertEquals("저장된 로그인 정보가 없습니다. 이 카드를 지우고 다시 추가해 주세요.", ko.errNoSavedSignInRemoveCard)
        assertEquals("No saved sign-in for this card. Remove this card and add it again.", en.errNoSavedSignInRemoveCard)
        // The email providers keep the I003 text.
        assertEquals("이 기기의 보안 저장소 키를 찾을 수 없어 다시 로그인해야 합니다.", ko.errCredentialKeyMissing)
        assertEquals("This device's secure storage key is missing. Please sign in again.", en.errCredentialKeyMissing)
    }

    @Test fun onlyEmailProvidersAreRefilledBySigningInAgain() {
        assertEquals(AgentProvider.entries.toSet(), emailProviders + removeCardProviders)
        for (provider in AgentProvider.entries) {
            assertEquals("$provider", provider in emailProviders, signInRefillsLostCard(provider))
        }
        // Every API key provider and Copilot: a sign-in cannot find the old card.
        for (provider in AgentProvider.entries.filter { it.authKind == AuthKind.API_KEY } + AgentProvider.COPILOT) {
            assertFalse("$provider", signInRefillsLostCard(provider))
        }
    }

    @Test fun aMissingKeyAsksToRemoveTheCardOnlyWhereSigningInAgainCannotRefillIt() {
        for (lang in Lang.entries) {
            val loc = L10n(lang)
            for (provider in AgentProvider.entries) {
                val expected = if (provider in emailProviders) loc.errCredentialKeyMissing else loc.errCredentialKeyMissingRemoveCard
                assertEquals("$lang $provider", expected, message(keyMissing(), loc, provider))
                assertEquals("$lang $provider", expected, loc.errCredential(KEY_MISSING, provider))
            }
        }
    }

    @Test fun noStoredCredentialAsksToRemoveTheCardOnlyWhereSigningInAgainCannotRefillIt() {
        for (lang in Lang.entries) {
            val loc = L10n(lang)
            for (provider in AgentProvider.entries) {
                val expected = if (provider in emailProviders) loc.errNotAuthenticated else loc.errNoSavedSignInRemoveCard
                assertEquals("$lang $provider", expected, message(OAuthException.NotAuthenticated(), loc, provider, credentialMissing = true))
            }
        }
    }

    /** A stored token that the server turned down (or that expired) is not "no saved sign-in". */
    @Test fun aRejectedTokenKeepsItsUsualTextEverywhere() {
        for (lang in Lang.entries) {
            val loc = L10n(lang)
            for (provider in AgentProvider.entries) {
                val expected = if (provider == AgentProvider.OPENROUTER) loc.errOpenRouterKeyRejected else loc.errNotAuthenticated
                assertEquals("$lang $provider", expected, message(OAuthException.NotAuthenticated(), loc, provider))
            }
        }
    }

    @Test fun theOtherStorageReasonsKeepTheTryAgainTextEverywhere() {
        val others = CredentialUnavailableException.Reason.entries - KEY_MISSING
        for (lang in Lang.entries) {
            val loc = L10n(lang)
            for (provider in AgentProvider.entries) {
                for (reason in others) {
                    assertEquals("$lang $provider $reason", loc.errCredentialUnavailable,
                        message(CredentialUnavailableException(reason), loc, provider))
                }
            }
        }
    }

    /** The sign-in screen has no card to remove: it keeps the provider-free text. */
    @Test fun theProviderFreeTextIsUnchanged() {
        val en = L10n(Lang.EN)
        assertEquals(en.errCredentialKeyMissing, en.errCredential(KEY_MISSING))
        assertEquals(en.errCredentialKeyMissing, message(keyMissing(), en, provider = null))
        assertEquals(en.errNotAuthenticated, message(OAuthException.NotAuthenticated(), en, provider = null, credentialMissing = true))
    }

    // endregion

    // region Through a fetch

    private val en = L10n(Lang.EN)

    @Test fun aCardWithNoStoredCredentialSaysSoWhereSigningInAgainCannotRefillIt() = runBlocking {
        for (provider in AgentProvider.entries) {
            val id = UUID.randomUUID()
            val snapshot = gateway(store(MemoryVault()), ProviderUsageClient { error("no request without a credential") })
                .fetchSnapshot(provider, id)

            val expected = if (provider in emailProviders) en.errNotAuthenticated else en.errNoSavedSignInRemoveCard
            assertEquals("$provider", expected, snapshot.error)
            // The analytics bucket is unchanged by the wording.
            assertEquals("$provider", FetchErrorReason.AUTH, snapshot.errorReason)
        }
    }

    @Test fun aTokenTheServerTurnsDownStillSaysLoginRequired() = runBlocking {
        for (provider in listOf(AgentProvider.COPILOT, AgentProvider.DEEPSEEK, AgentProvider.POE)) {
            val id = UUID.randomUUID()
            val vault = MemoryVault().apply { save(id, OAuthTokens.session("rejected-token", accountId = "1001")) }
            val snapshot = gateway(store(vault), ProviderUsageClient { throw UsageException.Unauthorized() })
                .fetchSnapshot(provider, id)

            assertEquals("$provider", en.errNotAuthenticated, snapshot.error)
            assertEquals("$provider", FetchErrorReason.AUTH, snapshot.errorReason)
        }
    }

    @Test fun aMissingKeyOnACardFollowsItsProvider() = runBlocking {
        for (provider in AgentProvider.entries) {
            val vault = object : CredentialVault {
                override fun save(agentId: UUID, tokens: OAuthTokens) = error("nothing is saved")
                override fun load(agentId: UUID): OAuthTokens? = throw keyMissing()
                override fun delete(agentId: UUID) = error("nothing is deleted")
            }
            val snapshot = gateway(store(vault), ProviderUsageClient { error("no request without a credential") })
                .fetchSnapshot(provider, UUID.randomUUID())

            val expected = if (provider in emailProviders) en.errCredentialKeyMissing else en.errCredentialKeyMissingRemoveCard
            assertEquals("$provider", expected, snapshot.error)
            assertEquals("$provider", FetchErrorReason.CREDENTIAL, snapshot.errorReason)
        }
    }

    /**
     * The reported flow on the real vault: the key that sealed both cards is gone (missing key), a
     * sign-in creates a new key, and the old card's payload then fails its tag, is discarded as
     * damaged, and the card has no stored credential.
     */
    @Test fun aCopilotCardAfterTheKeyWasReplacedAsksToBeRemoved() = runBlocking {
        val keys = SwappableKeys(newKey())
        val vault = EncryptedCredentialVault(keys, MemoryStorage())
        val oldCard = UUID.randomUUID()
        vault.save(oldCard, OAuthTokens.session("old-token", accountId = "1001"))
        val tokenStore = store(vault)
        val gateway = gateway(tokenStore, ProviderUsageClient { error("no request without a credential") })

        keys.key = null
        assertEquals(en.errCredentialKeyMissingRemoveCard, gateway.fetchSnapshot(AgentProvider.COPILOT, oldCard).error)

        // Signing in again (a new card) creates a new key.
        val newCard = UUID.randomUUID()
        tokenStore.save(newCard, OAuthTokens.session("new-token", accountId = "1001"))
        assertNotNull(keys.key)

        assertEquals(en.errNoSavedSignInRemoveCard, gateway.fetchSnapshot(AgentProvider.COPILOT, oldCard).error)
        assertNull(tokenStore.tokens(oldCard))
    }

    // endregion

    private fun message(
        error: Throwable,
        loc: L10n,
        provider: AgentProvider?,
        credentialMissing: Boolean = false,
    ) = UsageGateway.localizedMessage(error, loc, provider, credentialMissing)

    private fun keyMissing() = CredentialUnavailableException(KEY_MISSING)

    private fun store(vault: CredentialVault) =
        TokenStore(vault, TokenRefresher { _, _ -> error("not refreshed") }, now = { now })

    private fun gateway(store: TokenStore, client: ProviderUsageClient) = UsageGateway(
        registry = ProviderUsageRegistry(AgentProvider.entries.associateWith { client }),
        tokenStore = store,
        rateLimitGate = RateLimitGate(now = { now }),
        codexAccountClient = CodexAccountClient(NetworkTransport { error("Codex plan lookup not expected") }),
        localization = { en },
        now = { now },
    )

    private class MemoryVault : CredentialVault {
        private val values = mutableMapOf<UUID, OAuthTokens>()
        override fun save(agentId: UUID, tokens: OAuthTokens) {
            values[agentId] = tokens
        }
        override fun load(agentId: UUID): OAuthTokens? = values[agentId]
        override fun delete(agentId: UUID) {
            values.remove(agentId)
        }
    }

    private class SwappableKeys(var key: SecretKey?) : CredentialKeys {
        override fun existing(): SecretKey? = key
        override fun getOrCreate(): SecretKey = key ?: newKey().also { key = it }
    }

    private class MemoryStorage : CredentialStorage {
        private val values = mutableMapOf<String, String>()
        override fun read(account: String): String? = values[account]
        override fun write(account: String, payload: String): Boolean {
            values[account] = payload
            return true
        }
        override fun remove(account: String) {
            values.remove(account)
        }
    }

    private companion object {
        val KEY_MISSING = CredentialUnavailableException.Reason.KEY_MISSING

        fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    }
}
