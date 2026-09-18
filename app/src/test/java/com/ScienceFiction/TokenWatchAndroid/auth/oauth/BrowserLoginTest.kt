package com.ScienceFiction.TokenWatchAndroid.auth.oauth

import com.ScienceFiction.TokenWatchAndroid.auth.Pkce
import com.ScienceFiction.TokenWatchAndroid.localization.L10n
import com.ScienceFiction.TokenWatchAndroid.localization.Lang
import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkResponse
import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkTransport
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude's in-app sign-in path (sign-in window + loopback), ported from iOS OAuthBrowserLoginTests:
 * pasted-code parsing, the loopback listener's parsing and state check, the redirect that closes the
 * window, and a real socket round trip.
 */
class BrowserLoginTest {
    private val fallback = "state-from-pkce"

    // region parseManualCode

    @Test fun codeAndStateAreSplitOnTheHash() {
        assertEquals(OAuthCallback("abc123", "xyz789"), ClaudeOAuthClient.parseManualCode("abc123#xyz789", fallback))
    }

    @Test fun missingStateFallsBackToTheOneThisLoginCreated() {
        assertEquals(OAuthCallback("abc123", fallback), ClaudeOAuthClient.parseManualCode("abc123", fallback))
    }

    @Test fun surroundingWhitespaceAndNewlinesAreTrimmed() {
        assertEquals(
            OAuthCallback("abc123", "xyz789"),
            ClaudeOAuthClient.parseManualCode("  abc123#xyz789\n", fallback),
        )
    }

    @Test fun aWholeCallbackUrlIsAccepted() {
        val url = "https://console.anthropic.com/oauth/code/callback?code=abc123&state=xyz789"
        assertEquals(OAuthCallback("abc123", "xyz789"), ClaudeOAuthClient.parseManualCode(url, fallback))
    }

    @Test fun emptyInputIsRejected() {
        assertNull(ClaudeOAuthClient.parseManualCode("", fallback))
        assertNull(ClaudeOAuthClient.parseManualCode("   \n ", fallback))
        assertNull(ClaudeOAuthClient.parseManualCode("#onlystate", fallback))
    }

    // endregion

    // region Loopback parsing

    @Test fun callbackPathWithMatchingStateYieldsTheCode() {
        assertEquals(
            OAuthCallback("abc123", fallback),
            LoopbackCallbackServer.parseCallback("/callback?code=abc123&state=$fallback", fallback),
        )
    }

    @Test fun aDifferentStateIsRejected() {
        // CSRF defence: a response to an authorization this app did not start is dropped.
        assertNull(LoopbackCallbackServer.parseCallback("/callback?code=abc123&state=attacker", fallback))
    }

    @Test fun otherPathsAndCodelessRequestsAreRejected() {
        assertNull(LoopbackCallbackServer.parseCallback("/favicon.ico", fallback))
        assertNull(LoopbackCallbackServer.parseCallback("/callback?state=$fallback", fallback))
        assertNull(LoopbackCallbackServer.parseCallback("/callback?code=&state=$fallback", fallback))
        // A denied approval redirects with an error and no code.
        assertNull(LoopbackCallbackServer.parseCallback("/callback?error=access_denied&state=$fallback", fallback))
    }

    @Test fun theRequestLineIsReadOnlyOnceTheHeadersEnd() {
        val partial = "GET /callback?code=a&state=b HTTP/1.1\r\nHost: localhost".toByteArray()
        assertNull(LoopbackCallbackServer.requestLine(partial))
        val complete = "GET /callback?code=a&state=b HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray()
        assertEquals("GET /callback?code=a&state=b HTTP/1.1", LoopbackCallbackServer.requestLine(complete))
    }

    @Test fun onlyGetRequestLinesYieldATarget() {
        assertEquals("/callback?code=a", LoopbackCallbackServer.target("GET /callback?code=a HTTP/1.1"))
        assertNull(LoopbackCallbackServer.target("POST /callback HTTP/1.1"))
        assertNull(LoopbackCallbackServer.target("garbage"))
    }

    /** Characters that need encoding (=, &, #, +, space) must survive the round trip. */
    @Test fun theSessionRedirectRoundTrips() {
        val location = LoopbackCallbackServer.redirectLocation(code = "a+b/c=d&e", state = "s t#x")
        assertTrue(location.startsWith("tokenwatch://login-complete?"))
        assertEquals(OAuthCallback("a+b/c=d&e", "s t#x"), LoopbackCallbackServer.parseSessionCallback(location))
    }

    @Test fun otherAddressesAndCodelessSessionCallbacksAreRejected() {
        assertNull(LoopbackCallbackServer.parseSessionCallback("https://login-complete?code=a&state=b"))
        assertNull(LoopbackCallbackServer.parseSessionCallback("tokenwatch://other?code=a&state=b"))
        assertNull(LoopbackCallbackServer.parseSessionCallback("tokenwatch://login-complete?state=b"))
    }

    // endregion

    // region Loopback listener (real sockets)

    private val noRedirects = OkHttpClient.Builder().followRedirects(false).build()

    private fun withServer(
        state: String,
        onCode: (LoopbackCallbackServer?, String, String) -> Unit,
        block: (port: Int) -> Unit,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val holder = AtomicReference<LoopbackCallbackServer?>()
        val server = LoopbackCallbackServer(state, scope) { code, received -> onCode(holder.get(), code, received) }
        holder.set(server)
        try {
            block(runBlocking { server.start() })
        } finally {
            server.stop()
            scope.cancel()
        }
    }

    /** The same request a browser sends: the listener answers 302 to the app scheme and hands over the code. */
    @Test fun listenerAnswers302AndDeliversTheCode() {
        val state = "integration-state"
        val received = AtomicReference<OAuthCallback?>()
        val delivered = CountDownLatch(1)
        withServer(state, onCode = { _, code, s ->
            received.set(OAuthCallback(code, s))
            delivered.countDown()
        }) { port ->
            assertTrue(port > 0)
            val response = noRedirects.newCall(
                Request.Builder().url("http://127.0.0.1:$port/callback?code=integration-code&state=$state").build(),
            ).execute()
            response.use {
                assertEquals(302, it.code)
                assertEquals(
                    "integration-code",
                    it.header("Location")?.let(LoopbackCallbackServer::parseSessionCallback)?.code,
                )
            }
            assertTrue(delivered.await(2, TimeUnit.SECONDS))
            assertEquals(OAuthCallback("integration-code", state), received.get())
        }
    }

    /**
     * Regression (iOS build 12): stopping the listener the moment the code arrives must not cut the
     * 302, because the code is handed over only after the response was written.
     */
    @Test fun stoppingOnTheCodeStillLetsThe302Arrive() {
        val state = "stop-on-code"
        val delivered = CountDownLatch(1)
        withServer(state, onCode = { server, _, _ ->
            server?.stop()
            delivered.countDown()
        }) { port ->
            noRedirects.newCall(
                Request.Builder().url("http://127.0.0.1:$port/callback?code=c&state=$state").build(),
            ).execute().use { assertEquals(302, it.code) }
            assertTrue(delivered.await(2, TimeUnit.SECONDS))
        }
    }

    @Test fun aMismatchedStateGets404AndNoDelivery() {
        val delivered = CountDownLatch(1)
        withServer("right", onCode = { _, _, _ -> delivered.countDown() }) { port ->
            noRedirects.newCall(
                Request.Builder().url("http://127.0.0.1:$port/callback?code=x&state=wrong").build(),
            ).execute().use { assertEquals(404, it.code) }
            assertFalse(delivered.await(300, TimeUnit.MILLISECONDS))
        }
    }

    // endregion

    // region Provider dispatch

    /** The loopback redirect and authorize URL Claude signs in with, verified on devices. */
    @Test fun claudeLoopbackRedirectAndAuthorizeUrl() {
        val claude = ClaudeOAuthClient(NetworkTransport { error("unused") })
        val pkce = Pkce.create()
        assertEquals("http://localhost:54321/callback", claude.loopbackRedirectUri(54321))
        // The paste fallback with its explicit console redirect equals the old default URL.
        assertEquals(claude.authorizeUrl(pkce), claude.authorizeUrl(pkce, claude.manualCodeRedirect))
        assertTrue(claude.authorizeUrl(pkce, claude.loopbackRedirectUri(54321)).contains("redirect_uri=http"))
    }

    /** invalid_grant while exchanging means the code expired or was reused, not "log in again". */
    @Test fun claudeExchangeReportsAnExpiredCode() {
        val pkce = Pkce.create()
        val claude = ClaudeOAuthClient(
            NetworkTransport { NetworkResponse(400, emptyMap(), """{"error":"invalid_grant"}""".toByteArray()) },
        )
        assertThrows(OAuthException.CodeExpired::class.java) {
            runBlocking { claude.exchange("code", pkce.state, pkce, "http://localhost:1/callback") }
        }
    }

    /** A request that dies before reaching the server is sent once more; the code is still unused. */
    @Test fun claudeExchangeRetriesOneTransientFailure() = runBlocking {
        val pkce = Pkce.create()
        var calls = 0
        val claude = ClaudeOAuthClient(
            NetworkTransport {
                calls += 1
                if (calls == 1) throw IOException("connection lost")
                NetworkResponse(200, emptyMap(), """{"access_token":"at","expires_in":60}""".toByteArray())
            },
        )
        assertEquals("at", claude.exchange("code", pkce.state, pkce, "http://localhost:1/callback").accessToken)
        assertEquals(2, calls)
        assertTrue(isTransientNetworkError(IOException()))
    }

    @Test fun sessionFailureCopySuggestsTheCodeOnlyWithTheFallback() {
        for (lang in Lang.entries) {
            val loc = L10n(lang)
            val plain = loc.browserSessionFailed(manualFallback = false)
            assertFalse(plain.contains("코드"))
            assertFalse(plain.lowercase().contains("code"))
            assertNotEquals(plain, loc.browserSessionFailed(manualFallback = true))
        }
        assertTrue(L10n(Lang.EN).browserSheetIntro("Claude").contains("Claude"))
    }

    // endregion
}
