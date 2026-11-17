package com.ScienceFiction.TokenWatchAndroid.auth

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.security.KeyStore
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface CredentialVault {
    fun save(agentId: UUID, tokens: OAuthTokens)

    /**
     * The stored credential, or null when there is none. Throws [CredentialUnavailableException]
     * when one is stored but cannot be read right now; that credential is kept, never deleted.
     */
    fun load(agentId: UUID): OAuthTokens?
    fun delete(agentId: UUID)
}

/**
 * A credential is stored but cannot be used right now. Unlike a damaged one, it is kept: the cause
 * may pass, or a later build may read it. Never carries token material.
 */
class CredentialUnavailableException(
    val reason: Reason,
    cause: Throwable? = null,
) : Exception("Stored credential unavailable: ${reason.name.lowercase()}", cause) {
    enum class Reason {
        /** The key that encrypted it is not in the keystore. Reading never creates one. */
        KEY_MISSING,

        /** The keystore or the cipher failed (KeyStoreException, ProviderException, …). */
        KEYSTORE_UNAVAILABLE,

        /** Written in a payload format this build does not know. */
        UNSUPPORTED_VERSION,

        /** A rotated credential could not be written; it is held in memory until a write succeeds. */
        NOT_SAVED,
    }
}

/** The vault's encryption key. Only [CredentialVault.save] may create one. */
interface CredentialKeys {
    /** The existing key, or null when there is none. Never creates a key. */
    fun existing(): SecretKey?

    /** The existing key, or a newly created one. */
    fun getOrCreate(): SecretKey
}

/** Where the encrypted payloads are kept, one string per account. */
interface CredentialStorage {
    fun read(account: String): String?

    /** False when the write could not be committed. */
    fun write(account: String, payload: String): Boolean
    fun remove(account: String)
}

/**
 * AES-GCM vault. Each payload is `v1.<iv>.<ciphertext>` (base64url), bound to its account through
 * the associated data.
 *
 * A failed read deletes the payload only when it is damaged: it does not parse, or the key exists
 * and the authentication tag still fails, or the plaintext is not a credential. A missing key, a
 * keystore error, or an unknown format version leaves the payload in place and throws
 * [CredentialUnavailableException]. Reading never creates a key: a key created there would make
 * every existing payload fail its tag on the next read, and so be deleted.
 */
class EncryptedCredentialVault(
    private val keys: CredentialKeys,
    private val storage: CredentialStorage,
) : CredentialVault {
    override fun save(agentId: UUID, tokens: OAuthTokens) {
        val account = account(agentId)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keys.getOrCreate())
        cipher.updateAAD(account.toByteArray(Charsets.UTF_8))
        val ciphertext = cipher.doFinal(TokenJson.encode(tokens).toByteArray(Charsets.UTF_8))
        val payload = listOf(PAYLOAD_VERSION, cipher.iv.base64Url(), ciphertext.base64Url()).joinToString(".")
        check(storage.write(account, payload)) { "Unable to persist encrypted credentials" }
    }

    override fun load(agentId: UUID): OAuthTokens? {
        val account = account(agentId)
        val payload = storage.read(account) ?: return null
        val sealed = when (val parsed = SealedPayload.parse(payload)) {
            is SealedPayload.Parsed -> parsed
            SealedPayload.UnsupportedVersion ->
                throw CredentialUnavailableException(CredentialUnavailableException.Reason.UNSUPPORTED_VERSION)
            SealedPayload.Damaged -> return discard(account)
        }
        val key = try {
            keys.existing()
        } catch (error: Exception) {
            throw CredentialUnavailableException(CredentialUnavailableException.Reason.KEYSTORE_UNAVAILABLE, error)
        } ?: throw CredentialUnavailableException(CredentialUnavailableException.Reason.KEY_MISSING)
        val plaintext = try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, sealed.iv))
            cipher.updateAAD(account.toByteArray(Charsets.UTF_8))
            cipher.doFinal(sealed.ciphertext)
        } catch (_: AEADBadTagException) {
            // The key is there and still rejects the payload: it was altered or sealed by another key.
            return discard(account)
        } catch (error: Exception) {
            throw CredentialUnavailableException(CredentialUnavailableException.Reason.KEYSTORE_UNAVAILABLE, error)
        }
        val tokens = runCatching { TokenJson.decode(String(plaintext, Charsets.UTF_8)) }.getOrNull()
        return tokens ?: discard(account)
    }

    override fun delete(agentId: UUID) {
        storage.remove(account(agentId))
    }

    /** A damaged payload must not leave an unusable credential behind. */
    private fun discard(account: String): OAuthTokens? {
        storage.remove(account)
        return null
    }

    private fun account(agentId: UUID) = "tokens.$agentId"

    private fun ByteArray.base64Url(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(this)

    /** A payload split into its parts, or why it cannot be. */
    private sealed interface SealedPayload {
        class Parsed(val iv: ByteArray, val ciphertext: ByteArray) : SealedPayload
        data object UnsupportedVersion : SealedPayload
        data object Damaged : SealedPayload

        companion object {
            fun parse(payload: String): SealedPayload {
                val parts = payload.split('.')
                val version = parts.first()
                if (version != PAYLOAD_VERSION) {
                    // Only something shaped like a version is a format we may not know yet.
                    return if (VERSION_SHAPE.matches(version)) UnsupportedVersion else Damaged
                }
                if (parts.size != 3) return Damaged
                val iv = parts[1].decodeBase64Url() ?: return Damaged
                val ciphertext = parts[2].decodeBase64Url() ?: return Damaged
                if (iv.size != GCM_IV_BYTES || ciphertext.size < GCM_TAG_BITS / 8) return Damaged
                return Parsed(iv, ciphertext)
            }

            private fun String.decodeBase64Url(): ByteArray? =
                runCatching { Base64.getUrlDecoder().decode(this) }.getOrNull()
        }
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128

        /** Keystore AES-GCM always generates a 96-bit IV, and this vault has never written another. */
        private const val GCM_IV_BYTES = 12
        private const val PAYLOAD_VERSION = "v1"
        private val VERSION_SHAPE = Regex("v[0-9]+")
    }
}

/** [EncryptedCredentialVault] over Android Keystore (non-exportable key) and app-private preferences. */
class AndroidCredentialVault(context: Context) : CredentialVault by EncryptedCredentialVault(
    keys = AndroidKeystoreKeys(),
    storage = PreferencesCredentialStorage(
        context.applicationContext.getSharedPreferences(STORAGE_NAME, Context.MODE_PRIVATE),
    ),
) {
    companion object {
        internal const val STORAGE_NAME = "com.ScienceFiction.TokenWatchAndroid.oauth"
    }
}

private class AndroidKeystoreKeys : CredentialKeys {
    override fun existing(): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        return keyStore.getKey(KEY_ALIAS, null) as? SecretKey
    }

    override fun getOrCreate(): SecretKey {
        existing()?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "com.ScienceFiction.TokenWatchAndroid.oauth.key.v1"
    }
}

private class PreferencesCredentialStorage(private val preferences: SharedPreferences) : CredentialStorage {
    override fun read(account: String): String? = preferences.getString(account, null)
    override fun write(account: String, payload: String): Boolean =
        preferences.edit().putString(account, payload).commit()
    override fun remove(account: String) {
        preferences.edit().remove(account).commit()
    }
}

private object TokenJson {
    private val mapType = Types.newParameterizedType(
        Map::class.java,
        String::class.java,
        Any::class.java,
    )
    private val adapter = Moshi.Builder().build().adapter<Map<String, Any?>>(mapType)

    fun encode(tokens: OAuthTokens): String = adapter.toJson(
        mapOf(
            "accessToken" to tokens.accessToken,
            "refreshToken" to tokens.refreshToken,
            "expiresAtMillis" to tokens.expiresAt?.toEpochMilli(),
            "scopes" to tokens.scopes,
            "accountEmail" to tokens.accountEmail,
            "plan" to tokens.plan,
            "idToken" to tokens.idToken,
            "accountId" to tokens.accountId,
        ),
    )

    fun decode(json: String): OAuthTokens? {
        val value = adapter.fromJson(json) ?: return null
        val accessToken = value["accessToken"] as? String ?: return null
        val scopes = (value["scopes"] as? List<*>)?.mapNotNull { it as? String }.orEmpty()
        val expiresAtMillis = (value["expiresAtMillis"] as? Number)?.toLong()
        return OAuthTokens(
            accessToken = accessToken,
            refreshToken = value["refreshToken"] as? String,
            expiresAt = expiresAtMillis?.let(Instant::ofEpochMilli),
            scopes = scopes,
            accountEmail = value["accountEmail"] as? String,
            plan = value["plan"] as? String,
            idToken = value["idToken"] as? String,
            accountId = value["accountId"] as? String,
        )
    }
}
