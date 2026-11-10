package com.ScienceFiction.TokenWatchAndroid.ui.add

import com.ScienceFiction.TokenWatchAndroid.analytics.LoginStage
import com.ScienceFiction.TokenWatchAndroid.auth.OAuthTokens
import com.ScienceFiction.TokenWatchAndroid.auth.ProviderAuthRegistry
import com.ScienceFiction.TokenWatchAndroid.auth.device.CopilotDeviceFlow
import com.ScienceFiction.TokenWatchAndroid.auth.device.CursorAuth
import com.ScienceFiction.TokenWatchAndroid.auth.oauth.ClaudeOAuthClient
import com.ScienceFiction.TokenWatchAndroid.auth.oauth.CodexOAuthClient
import com.ScienceFiction.TokenWatchAndroid.auth.oauth.GrokOAuthClient
import com.ScienceFiction.TokenWatchAndroid.auth.oauth.LoopbackCallbackServer
import com.ScienceFiction.TokenWatchAndroid.domain.AgentProvider
import com.ScienceFiction.TokenWatchAndroid.localization.L10n
import com.ScienceFiction.TokenWatchAndroid.localization.Lang
import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkResponse
import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkTransport
import com.ScienceFiction.TokenWatchAndroid.network.providers.apikey.KimiUsageClient
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * A5: the browser sign-in ends exactly once on a provider error callback or the listener timeout,
 * a late result of an earlier attempt cannot touch the current one, and a success still completes.
 * Real loopback sockets; only the token endpoint is faked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AddAgentFlowViewModelBrowserTest {
    private val loc = L10n(Lang.EN)
    private val noRedirects = OkHttpClient.Builder().followRedirects(false).build()
    private val added = CopyOnWriteArrayList<Pair<AgentProvider, OAuthTokens>>()
    private val onAddAgent: suspend (AgentProvider, OAuthTokens) -> Unit = { provider, tokens ->
        added += provider to tokens
    }
    /** While true the token endpoint behaves like a background app cut off from the network. */
    private val offline = java.util.concurrent.atomic.AtomicBoolean(false)
    private val tokenCalls = java.util.concurrent.atomic.AtomicInteger(0)
    private val tokenEndpoint = NetworkTransport {
        tokenCalls.incrementAndGet()
        if (offline.get()) throw java.net.UnknownHostException("Unable to resolve host \"platform.claude.com\"")
        NetworkResponse(
            200,
            emptyMap(),
            """{"access_token":"synthetic-access","refresh_token":"synthetic-refresh","expires_in":3600}""".toByteArray(),
        )
    }
    private val providerAuth = ProviderAuthRegistry(
        claudeOAuth = ClaudeOAuthClient(tokenEndpoint),
        codexOAuth = CodexOAuthClient(tokenEndpoint),
        grokOAuth = GrokOAuthClient(tokenEndpoint),
        copilotDeviceFlow = CopilotDeviceFlow(tokenEndpoint),
        cursorAuth = CursorAuth(tokenEndpoint),
        kimi = KimiUsageClient(tokenEndpoint),
    )
    private lateinit var viewModel: AddAgentFlowViewModel

    @Before fun setUp() {
        // Unconfined but on real time: the exchange's transient-retry delay must actually elapse.
        Dispatchers.setMain(Dispatchers.Unconfined)
        viewModel = AddAgentFlowViewModel()
        viewModel.selectProvider(AgentProvider.CLAUDE, providerAuth, loc, onAddAgent)
    }

    @After fun tearDown() {
        viewModel.cancelAndReset()
        Dispatchers.resetMain()
    }

    /** Opens the sign-in window and returns the loopback port and the attempt's state. */
    private fun begin(lifetimeMillis: Long = LoopbackCallbackServer.DEFAULT_LIFETIME_MILLIS): Pair<Int, String> {
        viewModel.loopbackLifetimeMillis = lifetimeMillis
        val authorizeUrl = runBlocking { viewModel.beginBrowserLogin(providerAuth, loc, onAddAgent) }
        assertNotNull(authorizeUrl)
        val url = authorizeUrl!!.toHttpUrl()
        val port = url.queryParameter("redirect_uri")!!.toHttpUrl().port
        return port to url.queryParameter("state")!!
    }

    private fun get(port: Int, query: String): Int =
        noRedirects.newCall(Request.Builder().url("http://127.0.0.1:$port/callback?$query").build())
            .execute().use { it.code }

    private fun awaitPhase(timeoutMillis: Long = 2_000, predicate: (AddAgentPhase) -> Boolean): AddAgentPhase {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!predicate(viewModel.phase)) {
            check(System.currentTimeMillis() < deadline) { "phase stayed ${viewModel.phase}" }
            Thread.sleep(10)
        }
        return viewModel.phase
    }

    private val presenting = { phase: AddAgentPhase -> (phase as? AddAgentPhase.BrowserLogin)?.isPresenting == true }

    @Test fun timeoutWhileTheWindowIsOpenEndsTheAttemptWithATimeout() {
        begin(lifetimeMillis = 150)
        assertTrue(presenting(viewModel.phase))

        val failed = awaitPhase { it is AddAgentPhase.Failed } as AddAgentPhase.Failed

        assertEquals(loc.pollingLoginTimedOut, failed.message)
        assertEquals(LoginStage.BROWSER_WAIT, failed.stage)
        assertEquals("timed_out", failed.code)
        assertTrue(added.isEmpty())
    }

    @Test fun timeoutAfterTheUserClosedTheWindowKeepsTheRetryHint() {
        begin(lifetimeMillis = 150)
        viewModel.onBrowserWindowResult(0, null, providerAuth, loc, onAddAgent)
        val cancelled = viewModel.phase as AddAgentPhase.BrowserLogin
        assertTrue(cancelled.wasCancelled)

        Thread.sleep(400)

        assertSame(cancelled, viewModel.phase)
        // Retrying starts a fresh attempt with its own listener.
        begin()
        assertTrue(presenting(viewModel.phase))
    }

    @Test fun deniedConsentEndsTheAttemptOnceAsDenied() {
        val (port, state) = begin()

        assertEquals(302, get(port, "error=access_denied&state=$state"))
        val failed = awaitPhase { it is AddAgentPhase.Failed } as AddAgentPhase.Failed
        assertEquals(loc.deviceFlowDenied, failed.message)
        assertEquals("denied", failed.code)
        assertEquals(LoginStage.BROWSER_WAIT, failed.stage)

        // The sign-in window then reports the same redirect; it must not end the flow again.
        viewModel.onBrowserWindowResult(
            -1,
            LoopbackCallbackServer.errorRedirectLocation("access_denied", state),
            providerAuth,
            loc,
            onAddAgent,
        )
        assertSame(failed, viewModel.phase)
    }

    @Test fun theWindowsErrorRedirectArrivingFirstEndsTheAttemptOnce() {
        val (port, state) = begin()

        viewModel.onBrowserWindowResult(
            -1,
            LoopbackCallbackServer.errorRedirectLocation("access_denied", state),
            providerAuth,
            loc,
            onAddAgent,
        )
        val failed = viewModel.phase as AddAgentPhase.Failed
        assertEquals("denied", failed.code)

        // The listener was retired with the attempt; a late delivery cannot reach the flow.
        runCatching { get(port, "error=access_denied&state=$state") }
        Thread.sleep(100)
        assertSame(failed, viewModel.phase)
    }

    @Test fun anotherProviderErrorIsReportedWithoutAProviderString() {
        val (port, state) = begin()

        assertEquals(302, get(port, "error=server_error&state=$state"))
        val failed = awaitPhase { it is AddAgentPhase.Failed } as AddAgentPhase.Failed

        assertEquals(loc.browserProviderError, failed.message)
        assertEquals("other", failed.code)
    }

    @Test fun anErrorWithAnotherStateLeavesTheAttemptRunning() {
        val (port, _) = begin()

        assertEquals(404, get(port, "error=access_denied&state=forged"))
        Thread.sleep(100)

        assertTrue(presenting(viewModel.phase))
    }

    @Test fun aStatelessErrorCallbackEndsTheAttemptAsDenied() {
        val (port, _) = begin()

        assertEquals(302, get(port, "error=access_denied"))
        val failed = awaitPhase { it is AddAgentPhase.Failed } as AddAgentPhase.Failed

        assertEquals("denied", failed.code)
    }

    @Test fun aCopilotAccountLookupFailureShowsARetryableMessage() {
        val error = com.ScienceFiction.TokenWatchAndroid.auth.device.DeviceFlowException.AccountLookupFailed("http_503")
        assertEquals(loc.copilotAccountLookupFailed, authErrorMessage(error, loc))
        assertEquals("http_503", com.ScienceFiction.TokenWatchAndroid.analytics.LoginFailureCode.from(error))
    }

    @Test fun aCodeExchangeCutOffInTheBackgroundCompletesOnReturn() {
        val (port, state) = begin()
        offline.set(true)

        assertEquals(302, get(port, "code=synthetic-code&state=$state"))
        // Both attempts of the exchange fail while the window still covers the app...
        val deadline = System.currentTimeMillis() + 5_000
        while (tokenCalls.get() < 2) {
            check(System.currentTimeMillis() < deadline)
            Thread.sleep(10)
        }
        Thread.sleep(100)
        // ...and the flow waits instead of failing.
        assertEquals(AddAgentPhase.Authenticating, viewModel.phase)

        offline.set(false)
        viewModel.onAppResumed()

        awaitPhase { it == AddAgentPhase.Completed }
        assertEquals("synthetic-access", added.single().second.accessToken)
    }

    @Test fun aNetworkFailureInFrontShowsAMessageAndKeepsTheCodeForRetry() {
        val (port, state) = begin()
        viewModel.onAppResumed()
        offline.set(true)

        assertEquals(302, get(port, "code=synthetic-code&state=$state"))
        val failed = awaitPhase(timeoutMillis = 5_000) { it is AddAgentPhase.Failed } as AddAgentPhase.Failed

        assertEquals(loc.errNetwork, failed.message)
        assertEquals("network_offline", failed.code)
        assertTrue(failed.canRetryExchange)
    }

    @Test fun networkExceptionsAreNeverShownVerbatim() {
        for (error in listOf(
            java.net.UnknownHostException("Unable to resolve host \"github.com\""),
            java.net.SocketTimeoutException("timeout"),
            java.net.ConnectException("Failed to connect"),
        )) {
            assertEquals(loc.errNetwork, authErrorMessage(error, loc))
        }
    }

    @Test fun anEarlierAttemptsTimeoutCannotEndTheNewAttempt() {
        begin(lifetimeMillis = 150)
        viewModel.onBrowserWindowResult(0, null, providerAuth, loc, onAddAgent)
        begin()

        Thread.sleep(400)

        assertTrue(presenting(viewModel.phase))
    }

    // region Codex (fixed loopback localhost:1455/auth/callback)

    private fun portIsFree(port: Int): Boolean = runCatching {
        java.net.ServerSocket(port, 1, java.net.InetAddress.getByName("127.0.0.1")).close()
    }.isSuccess

    @Test fun codexSignsInThroughTheRegisteredLoopbackAndCompletes() {
        assumeTrue("port 1455 is in use on this machine", portIsFree(1455))
        viewModel.selectProvider(AgentProvider.CODEX, providerAuth, loc, onAddAgent)
        assertTrue(viewModel.phase is AddAgentPhase.BrowserLogin)
        val (port, state) = begin()
        assertEquals(1455, port)

        // Only the registered path is a callback.
        assertEquals(404, get(port, "code=synthetic-code&state=$state"))
        val status = noRedirects.newCall(
            Request.Builder().url("http://127.0.0.1:1455/auth/callback?code=synthetic-code&state=$state").build(),
        ).execute().use { it.code }
        assertEquals(302, status)

        awaitPhase { it == AddAgentPhase.Completed }
        assertEquals(AgentProvider.CODEX, added.single().first)
        assertEquals("synthetic-access", added.single().second.accessToken)
    }

    @Test fun codexWithItsPortHeldElsewhereFailsWithAPortInUseCode() {
        // If something else already holds the port, the outcome is the same.
        val holder = runCatching {
            java.net.ServerSocket(1455, 1, java.net.InetAddress.getByName("127.0.0.1"))
        }.getOrNull()
        try {
            viewModel.selectProvider(AgentProvider.CODEX, providerAuth, loc, onAddAgent)
            val url = runBlocking { viewModel.beginBrowserLogin(providerAuth, loc, onAddAgent) }

            assertNull(url)
            val failed = viewModel.phase as AddAgentPhase.Failed
            assertEquals(loc.browserPortInUse, failed.message)
            assertEquals(LoginStage.BROWSER_WAIT, failed.stage)
            assertEquals("loopback_port_in_use", failed.code)
            assertTrue(!viewModel.loginWindowOpen)
        } finally {
            holder?.close()
        }
    }

    // endregion

    @Test fun aSuccessfulCallbackStillCompletesAndItsTimeoutNeverFires() {
        val (port, state) = begin(lifetimeMillis = 300)

        assertEquals(302, get(port, "code=synthetic-code&state=$state"))
        awaitPhase { it == AddAgentPhase.Completed }
        Thread.sleep(500)

        assertEquals(AddAgentPhase.Completed, viewModel.phase)
        assertEquals(1, added.size)
        assertEquals("synthetic-access", added.single().second.accessToken)
    }
}
