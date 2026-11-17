package com.ScienceFiction.TokenWatchAndroid.auth

import java.security.KeyStoreException
import java.security.ProviderException
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D021: a stored credential is deleted only when it is damaged. A missing key, a keystore error or an
 * unknown format keeps it and throws [CredentialUnavailableException], and reading never creates a
 * key. The keystore is replaced by a software AES key so the real cipher path runs on the JVM.
 */
class EncryptedCredentialVaultTest {
    private val agentId = UUID.fromString("11111111-2222-3333-4444-555555555555")
    private val account = "tokens.$agentId"
    private val tokens = OAuthTokens(
        accessToken = "secret-access-token",
        refreshToken = "secret-refresh-token",
        expiresAt = Instant.parse("2026-07-12T00:00:00Z"),
        scopes = listOf("openid", "profile"),
        accountEmail = "test@example.com",
        plan = "pro",
        idToken = "header.payload.signature",
        accountId = "account-1",
    )

    private class FakeKeys(var key: SecretKey? = null) : CredentialKeys {
        var failure: Exception? = null
        var created = 0

        override fun existing(): SecretKey? {
            failure?.let { throw it }
            return key
        }

        override fun getOrCreate(): SecretKey = existing() ?: newKey().also {
            key = it
            created += 1
        }
    }

    private class MemoryStorage : CredentialStorage {
        val values = mutableMapOf<String, String>()
        override fun read(account: String): String? = values[account]
        override fun write(account: String, payload: String): Boolean {
            values[account] = payload
            return true
        }
        override fun remove(account: String) {
            values.remove(account)
        }
    }

    private val keys = FakeKeys()
    private val storage = MemoryStorage()
    private val vault = EncryptedCredentialVault(keys, storage)

    private fun stored() = storage.values[account]

    private fun unavailable(): CredentialUnavailableException.Reason =
        assertThrows(CredentialUnavailableException::class.java) { vault.load(agentId) }.reason

    @Test fun aSavedCredentialReadsBackAndIsNotStoredInTheClear() {
        vault.save(agentId, tokens)

        assertEquals(tokens, vault.load(agentId))
        val payload = stored()!!
        assertTrue(payload.startsWith("v1."))
        assertEquals(3, payload.split('.').size)
        assertFalse(payload.contains(tokens.accessToken))
        assertFalse(payload.contains(tokens.refreshToken!!))
        assertEquals(1, keys.created)
    }

    /** The format written before D021 (`v1.<iv>.<ciphertext>`, account as AAD) still reads. */
    @Test fun aPayloadInTheExistingV1FormatStillReads() {
        val key = newKey()
        keys.key = key
        storage.values[account] = sealV1(key, account, """{"accessToken":"legacy","scopes":["a"]}""")

        assertEquals("legacy", vault.load(agentId)?.accessToken)
        assertEquals(listOf("a"), vault.load(agentId)?.scopes)
    }

    @Test fun nothingStoredIsNullWithoutTouchingTheKeystore() {
        keys.failure = KeyStoreException("must not be asked")

        assertNull(vault.load(agentId))
        assertEquals(0, keys.created)
    }

    // region Kept: CredentialUnavailableException

    @Test fun aMissingKeyKeepsThePayloadAndReadingCreatesNoKey() {
        vault.save(agentId, tokens)
        val payload = stored()
        keys.key = null
        keys.created = 0

        assertEquals(CredentialUnavailableException.Reason.KEY_MISSING, unavailable())
        assertEquals(CredentialUnavailableException.Reason.KEY_MISSING, unavailable())
        assertEquals(0, keys.created)
        assertNull(keys.key)
        assertEquals(payload, stored())
    }

    @Test fun aKeystoreErrorKeepsThePayload() {
        vault.save(agentId, tokens)
        val payload = stored()

        keys.failure = KeyStoreException("keystore busy")
        assertEquals(CredentialUnavailableException.Reason.KEYSTORE_UNAVAILABLE, unavailable())
        keys.failure = ProviderException("keystore daemon gone")
        assertEquals(CredentialUnavailableException.Reason.KEYSTORE_UNAVAILABLE, unavailable())
        assertEquals(payload, stored())

        // Once the keystore answers again, the same credential reads.
        keys.failure = null
        assertEquals(tokens, vault.load(agentId))
    }

    @Test fun aCipherThatCannotStartKeepsThePayload() {
        vault.save(agentId, tokens)
        val payload = stored()
        // A key the cipher refuses stands in for the keystore failing inside the cipher.
        keys.key = javax.crypto.spec.SecretKeySpec(ByteArray(7), "AES")

        assertEquals(CredentialUnavailableException.Reason.KEYSTORE_UNAVAILABLE, unavailable())
        assertEquals(payload, stored())
    }

    @Test fun anUnknownFormatVersionIsKeptForTheBuildThatWroteIt() {
        keys.failure = KeyStoreException("must not be asked")
        storage.values[account] = "v2.AAAA.BBBB.CCCC"

        assertEquals(CredentialUnavailableException.Reason.UNSUPPORTED_VERSION, unavailable())
        assertEquals("v2.AAAA.BBBB.CCCC", stored())
    }

    // endregion

    // region Deleted: damaged

    @Test fun anUnparseablePayloadIsDeleted() {
        keys.key = newKey()
        for (damaged in listOf("garbage", "", "v1.only-two", "v1.!!!.@@@", "v1.AAAA.BBBB.CCCC", "v1..")) {
            storage.values[account] = damaged
            assertNull(damaged, vault.load(agentId))
            assertNull(damaged, stored())
        }
        assertEquals(0, keys.created)
    }

    @Test fun aPayloadTheExistingKeyRejectsIsDeleted() {
        val other = newKey()
        keys.key = other
        storage.values[account] = sealV1(newKey(), account, """{"accessToken":"x"}""")

        assertNull(vault.load(agentId))
        assertNull(stored())
    }

    @Test fun aPayloadMovedToAnotherAccountFailsItsTagAndIsDeleted() {
        val key = newKey()
        keys.key = key
        storage.values[account] = sealV1(key, "tokens.someone-else", """{"accessToken":"x"}""")

        assertNull(vault.load(agentId))
        assertNull(stored())
    }

    @Test fun plaintextThatIsNotACredentialIsDeleted() {
        val key = newKey()
        keys.key = key
        for (plaintext in listOf("not json", """{"refreshToken":"no access token"}""")) {
            storage.values[account] = sealV1(key, account, plaintext)
            assertNull(plaintext, vault.load(agentId))
            assertNull(plaintext, stored())
        }
    }

    // endregion

    @Test fun savingWithoutAKeyCreatesOne() {
        assertNull(keys.key)

        vault.save(agentId, tokens)

        assertEquals(1, keys.created)
        assertNotNull(keys.key)
        assertEquals(tokens, vault.load(agentId))
    }

    private companion object {
        fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

        /** A payload exactly as the vault writes it, around arbitrary plaintext. */
        fun sealV1(key: SecretKey, account: String, plaintext: String): String {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD(account.toByteArray(Charsets.UTF_8))
            val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            val encoder = Base64.getUrlEncoder().withoutPadding()
            return "v1.${encoder.encodeToString(cipher.iv)}.${encoder.encodeToString(ciphertext)}"
        }
    }
}
