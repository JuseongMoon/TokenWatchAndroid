package com.ScienceFiction.TokenWatchAndroid.auth

import java.time.Instant
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthModelsTest {
    @Test
    fun apiKeyCredentialTrimsAndNeverExpires() {
        val tokens = OAuthTokens.apiKey("  sk-abc123\n")
        assertEquals("sk-abc123", tokens.accessToken)
        assertNull(tokens.refreshToken)
        assertNull(tokens.expiresAt)
        assertTrue(tokens.scopes.isEmpty())
        assertFalse(tokens.isExpired(Instant.MAX))
    }

    @Test
    fun sessionCredentialCarriesAccountId() {
        val tokens = OAuthTokens.session("cookievalue", accountId = "user_42")
        assertEquals("cookievalue", tokens.accessToken)
        assertEquals("user_42", tokens.accountId)
        assertFalse(tokens.isExpired())
    }

    @Test
    fun expiryUsesSixtySecondSafetyWindow() {
        val now = Instant.parse("2026-07-11T00:00:00Z")
        assertFalse(OAuthTokens("t", expiresAt = now.plusSeconds(61)).isExpired(now))
        assertTrue(OAuthTokens("t", expiresAt = now.plusSeconds(60)).isExpired(now))
        assertTrue(OAuthTokens("t", expiresAt = now.minusSeconds(1)).isExpired(now))
    }

    @Test
    fun pkceIsUrlSafeAndChallengeMatchesVerifier() {
        val first = Pkce.create()
        val second = Pkce.create()
        assertEquals(Pkce.challengeForVerifier(first.verifier), first.challenge)
        assertFalse(first.verifier.contains('='))
        assertFalse(first.state.contains('+'))
        assertNotEquals(first.verifier, second.verifier)
        assertNotEquals(first.state, second.state)
    }

    @Test
    fun jwtExtractsFlatAndNestedClaims() {
        val token = jwt(
            """{"https://api.openai.com/profile":{"email":"nested@example.com"},"https://api.openai.com/auth":{"chatgpt_plan_type":"chatgpt_plus","chatgpt_account_id":"acc_1"}}""",
        )
        assertEquals("nested@example.com", Jwt.email(token))
        assertEquals("Chatgpt Plus", Jwt.plan(token))
        assertEquals("acc_1", Jwt.accountId(token))
        assertNull(Jwt.payload("not-a-jwt"))
    }

    private fun jwt(payload: String): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        return listOf("{}", payload, "signature")
            .joinToString(".") { encoder.encodeToString(it.toByteArray()) }
    }
}
