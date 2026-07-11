package com.ScienceFiction.TokenWatchAndroid.auth

import java.time.Instant

/**
 * Provider credential container matching the clean iOS baseline.
 *
 * API keys and captured browser sessions use the same shape with no refresh token or expiry.
 */
data class OAuthTokens(
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresAt: Instant? = null,
    val scopes: List<String> = emptyList(),
    val accountEmail: String? = null,
    val plan: String? = null,
    val idToken: String? = null,
    val accountId: String? = null,
) {
    fun isExpired(now: Instant = Instant.now()): Boolean =
        expiresAt?.let { !now.isBefore(it.minusSeconds(EXPIRY_SAFETY_SECONDS)) } ?: false

    companion object {
        private const val EXPIRY_SAFETY_SECONDS = 60L

        fun apiKey(key: String, email: String? = null, plan: String? = null) = OAuthTokens(
            accessToken = key.trim(),
            accountEmail = email,
            plan = plan,
        )

        fun session(
            token: String,
            email: String? = null,
            plan: String? = null,
            accountId: String? = null,
        ) = OAuthTokens(
            accessToken = token,
            accountEmail = email,
            plan = plan,
            accountId = accountId,
        )
    }
}
