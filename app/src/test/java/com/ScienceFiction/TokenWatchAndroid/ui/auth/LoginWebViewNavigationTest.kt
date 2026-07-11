package com.ScienceFiction.TokenWatchAndroid.ui.auth

import com.ScienceFiction.TokenWatchAndroid.auth.oauth.OAuthCallback
import com.ScienceFiction.TokenWatchAndroid.auth.oauth.CodexOAuthClient
import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginWebViewNavigationTest {
    @Test
    fun codexCleartextLocalhostCallbackIsInterceptedBeforeNavigation() {
        val callbackUrl = "http://localhost:1455/auth/callback?code=code-1&state=state-1"
        val expected = OAuthCallback(code = "code-1", state = "state-1")
        val client = CodexOAuthClient(NetworkTransport { error("No network request expected") })

        val decision = decideOAuthNavigation(callbackUrl, client::parseCallback)

        assertEquals(OAuthNavigationDecision.Intercept(expected), decision)
    }

    @Test
    fun unrelatedNavigationIsAllowedAndParserFailureIsDistinct() {
        assertSame(
            OAuthNavigationDecision.Allow,
            decideOAuthNavigation("https://auth.openai.com/login") { null },
        )
        assertSame(
            OAuthNavigationDecision.ParserFailure,
            decideOAuthNavigation("http://localhost/callback") { error("bad callback") },
        )
    }

    @Test
    fun callbackGateDeliversCallbackOnlyOnceAcrossNavigationLifecycleObservations() {
        val callbackUrl = "https://console.anthropic.com/oauth/code/callback?code=code-1&state=state-1"
        val expected = OAuthCallback(code = "code-1", state = "state-1")
        val gate = OAuthCallbackGate()
        val parser: (String) -> OAuthCallback? = { url ->
            expected.takeIf { url == callbackUrl }
        }

        // Mirrors shouldOverrideUrlLoading, onPageStarted, history, and onPageFinished.
        val observations = List(4) { gate.inspect(callbackUrl, parser) }

        assertTrue(observations.all(OAuthCallbackInterception::consumeNavigation))
        assertEquals(listOf(expected), observations.mapNotNull(OAuthCallbackInterception::callback))
        assertFalse(observations.any(OAuthCallbackInterception::reportParserFailure))
    }

    @Test
    fun callbackGateConsumesParserFailureAndReportsItOnlyOnce() {
        val gate = OAuthCallbackGate()
        val parser: (String) -> OAuthCallback? = { error("bad callback") }

        val first = gate.inspect("https://callback.invalid", parser)
        val duplicate = gate.inspect("https://callback.invalid", parser)

        assertTrue(first.consumeNavigation)
        assertTrue(first.reportParserFailure)
        assertNull(first.callback)
        assertTrue(duplicate.consumeNavigation)
        assertFalse(duplicate.reportParserFailure)
        assertNull(duplicate.callback)
    }

    @Test
    fun callbackGateAllowsUnrelatedNavigationAndCanResetForANewFlow() {
        val callback = OAuthCallback(code = "code-2", state = "state-2")
        val gate = OAuthCallbackGate()
        val parser: (String) -> OAuthCallback? = { url -> callback.takeIf { url.endsWith("callback") } }

        assertFalse(gate.inspect("https://claude.ai/login", parser).consumeNavigation)
        assertEquals(callback, gate.inspect("https://example.com/callback", parser).callback)
        assertNull(gate.inspect("https://example.com/callback", parser).callback)

        gate.reset()

        assertEquals(callback, gate.inspect("https://example.com/callback", parser).callback)
    }
}
