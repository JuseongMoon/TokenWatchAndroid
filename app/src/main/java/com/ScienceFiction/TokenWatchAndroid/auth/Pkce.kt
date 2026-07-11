package com.ScienceFiction.TokenWatchAndroid.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

data class Pkce(
    val verifier: String,
    val challenge: String,
    val state: String,
) {
    companion object {
        private const val RANDOM_BYTE_COUNT = 32
        private val secureRandom = SecureRandom()

        fun create(): Pkce {
            val verifier = randomUrlSafe(RANDOM_BYTE_COUNT)
            val state = randomUrlSafe(RANDOM_BYTE_COUNT)
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(verifier.toByteArray(Charsets.UTF_8))
            return Pkce(
                verifier = verifier,
                challenge = digest.base64UrlEncoded(),
                state = state,
            )
        }

        internal fun challengeForVerifier(verifier: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(verifier.toByteArray(Charsets.UTF_8))
                .base64UrlEncoded()

        private fun randomUrlSafe(byteCount: Int): String =
            ByteArray(byteCount).also(secureRandom::nextBytes).base64UrlEncoded()

        private fun ByteArray.base64UrlEncoded(): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(this)
    }
}
