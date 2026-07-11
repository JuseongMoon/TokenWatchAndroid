package com.ScienceFiction.TokenWatchAndroid.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.security.KeyStore
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface CredentialVault {
    fun save(agentId: UUID, tokens: OAuthTokens)
    fun load(agentId: UUID): OAuthTokens?
    fun delete(agentId: UUID)
}

/** AES-GCM vault whose encryption key is non-exportable and owned by Android Keystore. */
class AndroidCredentialVault(context: Context) : CredentialVault {
    private val preferences = context.applicationContext.getSharedPreferences(
        STORAGE_NAME,
        Context.MODE_PRIVATE,
    )

    override fun save(agentId: UUID, tokens: OAuthTokens) {
        val account = account(agentId)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(account.toByteArray(Charsets.UTF_8))
        val ciphertext = cipher.doFinal(TokenJson.encode(tokens).toByteArray(Charsets.UTF_8))
        val payload = listOf(
            PAYLOAD_VERSION,
            cipher.iv.base64Url(),
            ciphertext.base64Url(),
        ).joinToString(".")
        check(preferences.edit().putString(account, payload).commit()) {
            "Unable to persist encrypted credentials"
        }
    }

    override fun load(agentId: UUID): OAuthTokens? {
        val account = account(agentId)
        val payload = preferences.getString(account, null) ?: return null
        return runCatching {
            val parts = payload.split('.')
            require(parts.size == 3 && parts[0] == PAYLOAD_VERSION)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(GCM_TAG_BITS, parts[1].decodeBase64Url()),
            )
            cipher.updateAAD(account.toByteArray(Charsets.UTF_8))
            val plaintext = cipher.doFinal(parts[2].decodeBase64Url())
            TokenJson.decode(String(plaintext, Charsets.UTF_8))
        }.getOrElse {
            // A restored/corrupt blob must not leave an unusable account credential behind.
            preferences.edit().remove(account).commit()
            null
        }
    }

    override fun delete(agentId: UUID) {
        preferences.edit().remove(account(agentId)).commit()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

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

    private fun account(agentId: UUID) = "tokens.$agentId"

    private fun ByteArray.base64Url(): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(this)

    private fun String.decodeBase64Url(): ByteArray = Base64.getUrlDecoder().decode(this)

    companion object {
        internal const val STORAGE_NAME = "com.ScienceFiction.TokenWatchAndroid.oauth"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "com.ScienceFiction.TokenWatchAndroid.oauth.key.v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val PAYLOAD_VERSION = "v1"
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
