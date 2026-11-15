package com.ScienceFiction.TokenWatchAndroid.ui.add

import com.ScienceFiction.TokenWatchAndroid.analytics.AnalyticsEvent
import com.ScienceFiction.TokenWatchAndroid.analytics.LoginAttemptTracker
import com.ScienceFiction.TokenWatchAndroid.auth.OAuthTokens
import com.ScienceFiction.TokenWatchAndroid.auth.ProviderAuthRegistry
import com.ScienceFiction.TokenWatchAndroid.auth.device.CopilotDeviceFlow
import com.ScienceFiction.TokenWatchAndroid.auth.device.CursorAuth
import com.ScienceFiction.TokenWatchAndroid.auth.oauth.ClaudeOAuthClient
import com.ScienceFiction.TokenWatchAndroid.auth.oauth.CodexOAuthClient
import com.ScienceFiction.TokenWatchAndroid.auth.oauth.GrokOAuthClient
import com.ScienceFiction.TokenWatchAndroid.domain.AgentProvider
import com.ScienceFiction.TokenWatchAndroid.localization.L10n
import com.ScienceFiction.TokenWatchAndroid.localization.Lang
import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkResponse
import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkTransport
import com.ScienceFiction.TokenWatchAndroid.network.providers.apikey.KimiUsageClient
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The login funnel as the app sees it: events come from the activity-retained view model, so a
 * recreated activity (rotation, dark mode, language) only starts collecting again. It must not log
 * a second login_start, and the failure or abandon that follows keeps its provider and stage.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AddAgentFlowFunnelTest {
    private val loc = L10n(Lang.EN)
    private var now = 5_000_000L
    private val added = CopyOnWriteArrayList<AgentProvider>()
    private val onAddAgent: suspend (AgentProvider, OAuthTokens) -> Unit = { provider, _ -> added += provider }
    private val tokenEndpoint = NetworkTransport {
        NetworkResponse(200, emptyMap(), """{"access_token":"synthetic-access","expires_in":3600}""".toByteArray())
    }
    private val providerAuth = ProviderAuthRegistry(
        claudeOAuth = ClaudeOAuthClient(tokenEndpoint),
        codexOAuth = CodexOAuthClient(tokenEndpoint),
        grokOAuth = GrokOAuthClient(tokenEndpoint),
        copilotDeviceFlow = CopilotDeviceFlow(tokenEndpoint),
        cursorAuth = CursorAuth(tokenEndpoint),
        kimi = KimiUsageClient(tokenEndpoint),
    )
    private val noRedirects = OkHttpClient.Builder().followRedirects(false).build()
    /** The app container's tracker: one per process. */
    private val loginAttempts = LoginAttemptTracker { now }
    private lateinit var viewModel: AddAgentFlowViewModel

    @Before fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        viewModel = AddAgentFlowViewModel(loginAttempts)
    }

    @After fun tearDown() {
        viewModel.cancelAndReset()
        Dispatchers.resetMain()
    }

    private fun drained(): List<Pair<String, Map<String, String>>> =
        viewModel.drainAnalyticsEvents().map { it.eventName to it.parameters }

    private fun awaitPhase(predicate: (AddAgentPhase) -> Boolean) {
        val deadline = System.currentTimeMillis() + 2_000
        while (!predicate(viewModel.phase)) {
            check(System.currentTimeMillis() < deadline) { "phase stayed ${viewModel.phase}" }
            Thread.sleep(10)
        }
    }

    /** Opens Claude's sign-in window and returns the loopback port and the attempt's state. */
    private fun beginClaude(lifetimeMillis: Long? = null): Pair<Int, String> {
        viewModel.selectProvider(AgentProvider.CLAUDE, providerAuth, loc, onAddAgent)
        lifetimeMillis?.let { viewModel.loopbackLifetimeMillis = it }
        val url = runBlocking { viewModel.beginBrowserLogin(providerAuth, loc, onAddAgent) }!!.toHttpUrl()
        return url.queryParameter("redirect_uri")!!.toHttpUrl().port to url.queryParameter("state")!!
    }

    @Test fun aRecreatedScreenStartsCollectingWithoutASecondStart() = runBlocking {
        viewModel.selectProvider(AgentProvider.KIMI, providerAuth, loc, onAddAgent)
        // The first screen logs the start, then the activity is recreated and its collector cancelled.
        val first = withTimeout(1_000) { viewModel.analyticsEvents.first() }
        assertEquals("login_start", first.eventName)

        // The new screen collects again; nothing is pending, so it sees only what happens next.
        now += 12_000
        viewModel.cancelAndReset()
        val next = withTimeout(1_000) { viewModel.analyticsEvents.first() }

        assertEquals("login_abandon", next.eventName)
        assertEquals(
            mapOf("provider" to "kimi", "stage" to "api_key_entry", "elapsed" to "10_60s", "attempt" to "1"),
            next.parameters,
        )
        assertTrue(viewModel.drainAnalyticsEvents().isEmpty())
    }

    @Test fun aFailureAfterRecreationKeepsItsProviderAndStage() {
        beginClaude(lifetimeMillis = 150)
        assertEquals(listOf("login_start"), drained().map { it.first })

        // Recreated while the sign-in window is open; the listener then times out.
        awaitPhase { it is AddAgentPhase.Failed }

        assertEquals(
            listOf(
                "login_fail" to mapOf(
                    "provider" to "claude",
                    "auth_kind" to "oauth_browser",
                    "stage" to "browser_wait",
                    "code" to "timed_out",
                ),
            ),
            drained(),
        )
    }

    @Test fun aSuccessfulSignInIsNeverAnAbandonAndResetsTheAttempt() {
        val (port, state) = beginClaude()
        val status = noRedirects.newCall(
            Request.Builder().url("http://127.0.0.1:$port/callback?code=synthetic-code&state=$state").build(),
        ).execute().use { it.code }
        assertEquals(302, status)
        awaitPhase { it == AddAgentPhase.Completed }

        // What the screen does on Completed, in either order with the funnel: reset, then dismiss.
        viewModel.cancelAndReset()
        viewModel.cancelAndReset()

        assertEquals(listOf(AgentProvider.CLAUDE), added)
        assertEquals(listOf("login_start"), drained().map { it.first })

        // The next Claude attempt counts from one again.
        viewModel.selectProvider(AgentProvider.CLAUDE, providerAuth, loc, onAddAgent)
        viewModel.cancelAndReset()
        assertEquals(
            listOf("login_start" to "", "login_abandon" to "1"),
            drained().map { it.first to (it.second["attempt"] ?: "") },
        )
    }

    @Test fun attemptsAccumulateAcrossAbandonsUntilASuccess() {
        repeat(4) {
            viewModel.selectProvider(AgentProvider.OPENROUTER, providerAuth, loc, onAddAgent)
            viewModel.cancelAndReset()
        }

        val attempts = viewModel.drainAnalyticsEvents()
            .filter { it.eventName == "login_abandon" }
            .map { it.parameters["attempt"] }
        assertEquals(listOf("1", "2", "3", "3"), attempts)
    }

    /**
     * Like iOS, attempts are counted per process: a view model cleared with its activity (and a
     * new one for the activity that comes back) keeps counting; only a new process starts over.
     */
    @Test fun attemptsOutliveTheViewModelAndStartOverWithTheProcess() {
        viewModel.selectProvider(AgentProvider.DEEPSEEK, providerAuth, loc, onAddAgent)
        viewModel.cancelAndReset()

        val next = AddAgentFlowViewModel(loginAttempts)
        next.selectProvider(AgentProvider.DEEPSEEK, providerAuth, loc, onAddAgent)
        now += 61_000
        next.cancelAndReset()
        val abandon = next.drainAnalyticsEvents().single { it.eventName == "login_abandon" }
        assertEquals("2", abandon.parameters["attempt"])
        assertEquals("gt60s", abandon.parameters["elapsed"])

        val restarted = AddAgentFlowViewModel(LoginAttemptTracker { now })
        restarted.selectProvider(AgentProvider.DEEPSEEK, providerAuth, loc, onAddAgent)
        restarted.cancelAndReset()
        assertEquals(
            "1",
            restarted.drainAnalyticsEvents().single { it.eventName == "login_abandon" }.parameters["attempt"],
        )
    }

    @Test fun funnelEventsAreTheOnlyLoginEventsTheViewModelEmits() {
        beginClaude()
        viewModel.cancelAndReset()

        val events: List<AnalyticsEvent> = viewModel.drainAnalyticsEvents()
        assertEquals(listOf("login_start", "login_abandon"), events.map { it.eventName })
        assertEquals("browser_wait", events[1].parameters["stage"])
    }
}
