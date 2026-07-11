package com.ScienceFiction.TokenWatchAndroid.auth

import com.ScienceFiction.TokenWatchAndroid.auth.oauth.OAuthCallback
import com.ScienceFiction.TokenWatchAndroid.auth.oauth.OAuthCodeClient
import com.ScienceFiction.TokenWatchAndroid.auth.session.BrowserCookie
import com.ScienceFiction.TokenWatchAndroid.auth.session.CursorSessionAuth
import com.ScienceFiction.TokenWatchAndroid.domain.AgentProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class ProviderAuthRegistryTest {
    private val claude = FakeOAuthClient("claude")
    private val codex = FakeOAuthClient("codex")
    private val registry = ProviderAuthRegistry(claude, codex)

    @Test
    fun oauthDispatchIsLimitedToCleanBaselineProviders() {
        assertSame(claude, registry.oauthClient(AgentProvider.CLAUDE))
        assertSame(codex, registry.oauthClient(AgentProvider.CODEX))
        assertThrows(IllegalArgumentException::class.java) {
            registry.oauthClient(AgentProvider.COPILOT)
        }
    }

    @Test
    fun apiKeyAndSessionDispatchCoverTheirProviderKinds() {
        val key = registry.apiKeyCredential(AgentProvider.OPENROUTER, "  secret  ")
        assertEquals("secret", key.accessToken)
        assertThrows(IllegalArgumentException::class.java) {
            registry.apiKeyCredential(AgentProvider.CLAUDE, "secret")
        }

        assertEquals(
            SessionCaptureMode.COOKIE,
            registry.sessionCaptureMode(AgentProvider.CURSOR),
        )
        assertEquals(
            SessionCaptureMode.LOCAL_STORAGE,
            registry.sessionCaptureMode(AgentProvider.WINDSURF),
        )
        assertNull(registry.sessionLoginUrl(AgentProvider.CLAUDE))
    }

    @Test
    fun sessionProbesRouteWithoutExposingCredentialsToAgentModels() {
        val cursor = registry.sessionProbe(
            AgentProvider.CURSOR,
            listOf(BrowserCookie(CursorSessionAuth.cookieName, "user::jwt", "cursor.com")),
        )
        assertNotNull(cursor)
        assertEquals("user", cursor?.accountId)

        val windsurf = registry.localStorageProbe(
            AgentProvider.WINDSURF,
            mapOf("windsurf-auth-token" to "token"),
        )
        assertNotNull(windsurf)
        assertNull(registry.localStorageProbe(AgentProvider.GROK, emptyMap()))
    }

    private class FakeOAuthClient(private val id: String) : OAuthCodeClient {
        override fun authorizeUrl(pkce: Pkce): String = "$id:${pkce.state}"
        override fun parseCallback(url: String): OAuthCallback? = null
        override suspend fun exchange(callback: OAuthCallback, pkce: Pkce): OAuthTokens = error("unused")
        override suspend fun refresh(tokens: OAuthTokens): OAuthTokens = error("unused")
    }
}
