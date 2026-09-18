package com.ScienceFiction.TokenWatchAndroid.auth

import com.ScienceFiction.TokenWatchAndroid.auth.device.CopilotDeviceFlow
import com.ScienceFiction.TokenWatchAndroid.auth.device.CursorAuth
import com.ScienceFiction.TokenWatchAndroid.auth.oauth.BrowserOAuthClient
import com.ScienceFiction.TokenWatchAndroid.auth.oauth.OAuthCallback
import com.ScienceFiction.TokenWatchAndroid.auth.oauth.OAuthCodeClient
import com.ScienceFiction.TokenWatchAndroid.domain.AgentProvider
import com.ScienceFiction.TokenWatchAndroid.domain.AuthKind
import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkResponse
import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkTransport
import com.ScienceFiction.TokenWatchAndroid.network.providers.apikey.KimiKeyException
import com.ScienceFiction.TokenWatchAndroid.network.providers.apikey.KimiUsageClient
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class ProviderAuthRegistryTest {
    private val claude = FakeBrowserClient("claude", manual = "https://console.example/code")
    private val grok = FakeBrowserClient("grok", manual = null)
    private val codex = FakeOAuthClient("codex")
    private val requestedHosts = mutableListOf<String>()

    /** Kimi's regional probe: the first host rejects the key, the second accepts it. */
    private val kimiTransport = NetworkTransport { request ->
        requestedHosts += request.url.host
        val status = when {
            request.url.encodedPath.endsWith("/me") -> 200
            request.url.host == "api.kimi.com" -> 401
            else -> 200
        }
        val body = if (request.url.encodedPath.endsWith("/me")) """{"user_level_name":"Allegretto"}""" else "{}"
        NetworkResponse(status, emptyMap(), body.toByteArray())
    }
    private val unusedTransport = NetworkTransport { error("unused") }

    private val registry = ProviderAuthRegistry(
        claudeOAuth = claude,
        codexOAuth = codex,
        grokOAuth = grok,
        copilotDeviceFlow = CopilotDeviceFlow(unusedTransport),
        cursorAuth = CursorAuth(unusedTransport),
        kimi = KimiUsageClient(kimiTransport),
    )

    @Test fun oauthDispatchIsLimitedToOAuthProviders() {
        assertSame(codex, registry.oauthClient(AgentProvider.CODEX))
        assertThrows(IllegalArgumentException::class.java) { registry.oauthClient(AgentProvider.CLAUDE) }
        assertThrows(IllegalArgumentException::class.java) { registry.oauthClient(AgentProvider.COPILOT) }
    }

    @Test fun browserSignInCoversClaudeAndGrokOnly() {
        assertSame(claude, registry.browserClient(AgentProvider.CLAUDE))
        assertSame(grok, registry.browserClient(AgentProvider.GROK))
        AgentProvider.entries.filter { it.authKind != AuthKind.OAUTH_BROWSER }.forEach { provider ->
            assertThrows(IllegalArgumentException::class.java) { registry.browserClient(provider) }
        }
    }

    /** Only Claude has the paste-the-code fallback; the sign-in screen hides it for everyone else. */
    @Test fun manualCodeFallbackExistsOnlyForClaude() {
        AgentProvider.entries.forEach { provider ->
            if (provider == AgentProvider.CLAUDE) {
                assertEquals("https://console.example/code", registry.manualCodeRedirect(provider))
            } else {
                assertNull(provider.wireId, registry.manualCodeRedirect(provider))
            }
        }
    }

    @Test fun apiKeyDispatchCoversOfficialKeyProviders(): Unit = runBlocking {
        val key = registry.apiKeyCredential(AgentProvider.OPENROUTER, "  secret  ")
        assertEquals("secret", key.accessToken)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { registry.apiKeyCredential(AgentProvider.CLAUDE, "secret") }
        }
    }

    /** Kimi keeps the host that accepted the key and the plan name, and never creates a card for a bad key. */
    @Test fun kimiKeyIsBoundToTheHostThatAcceptsIt(): Unit = runBlocking {
        val tokens = registry.apiKeyCredential(AgentProvider.KIMI, " sk-kimi-test ")
        assertEquals("sk-kimi-test", tokens.accessToken)
        assertEquals("api.kimi.ai", tokens.accountId)
        assertEquals("Allegretto", tokens.plan)
        assertEquals(listOf("api.kimi.com", "api.kimi.ai", "api.kimi.ai"), requestedHosts)

        val rejecting = KimiUsageClient { NetworkResponse(401, emptyMap(), ByteArray(0)) }
        assertThrows(KimiKeyException::class.java) {
            runBlocking { rejecting.prepareCredential("sk-kimi-bad") }
        }
    }

    @Test fun cursorPollingLoginOpensTheLoginPageWithoutACode(): Unit = runBlocking {
        val login = registry.startPollingLogin(AgentProvider.CURSOR)
        assertNull(login.userCode)
        assertEquals("cursor.com", login.verificationUrl?.toHttpUrl()?.host)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { registry.startPollingLogin(AgentProvider.CLAUDE) }
        }
    }

    private class FakeOAuthClient(private val id: String) : OAuthCodeClient {
        override fun authorizeUrl(pkce: Pkce): String = "$id:${pkce.state}"
        override fun parseCallback(url: String): OAuthCallback? = null
        override suspend fun exchange(callback: OAuthCallback, pkce: Pkce): OAuthTokens = error("unused")
        override suspend fun refresh(tokens: OAuthTokens): OAuthTokens = error("unused")
    }

    private class FakeBrowserClient(private val id: String, manual: String?) : BrowserOAuthClient {
        override fun loopbackRedirectUri(port: Int): String = "http://localhost:$port/$id"
        override fun authorizeUrl(pkce: Pkce, redirect: String): String = "$id:$redirect"
        override val manualCodeRedirect: String? = manual
        override suspend fun exchange(code: String, state: String, pkce: Pkce, redirect: String): OAuthTokens =
            error("unused")
        override suspend fun refresh(tokens: OAuthTokens): OAuthTokens = error("unused")
    }
}
