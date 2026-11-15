package com.ScienceFiction.TokenWatchAndroid.ui.add

import com.ScienceFiction.TokenWatchAndroid.analytics.AnalyticsEvent
import com.ScienceFiction.TokenWatchAndroid.analytics.LoginAttemptTracker
import com.ScienceFiction.TokenWatchAndroid.analytics.LoginStage
import com.ScienceFiction.TokenWatchAndroid.analytics.loginAttemptBucket
import com.ScienceFiction.TokenWatchAndroid.analytics.loginElapsedBucket
import com.ScienceFiction.TokenWatchAndroid.auth.Pkce
import com.ScienceFiction.TokenWatchAndroid.domain.AgentProvider
import org.junit.Assert.assertEquals
import org.junit.Test

/** D020: the login funnel events, login_abandon's `elapsed` and `attempt`, and their boundaries. */
class LoginFunnelTest {
    private var now = 1_000_000L
    private val funnel = LoginFunnel(LoginAttemptTracker { now })
    private var phase: AddAgentPhase = AddAgentPhase.PickProvider
    private val logged = mutableListOf<AnalyticsEvent>()

    private fun go(next: AddAgentPhase) {
        logged += funnel.transition(phase, next)
        phase = next
    }

    private fun browser(provider: AgentProvider = AgentProvider.CLAUDE) =
        AddAgentPhase.BrowserLogin(provider, Pkce.create())

    private fun names() = logged.map { it.eventName }
    private fun abandons() = logged.filter { it.eventName == "login_abandon" }.map { it.parameters }

    // region Buckets

    @Test fun elapsedBoundariesMatchIos() {
        assertEquals("lt10s", loginElapsedBucket(0))
        assertEquals("lt10s", loginElapsedBucket(9_999))
        assertEquals("10_60s", loginElapsedBucket(10_000))
        assertEquals("10_60s", loginElapsedBucket(60_000))
        assertEquals("gt60s", loginElapsedBucket(60_001))
        // The wall clock was set back during the attempt.
        assertEquals("lt10s", loginElapsedBucket(-5_000))
    }

    @Test fun attemptIsCappedAtThree() {
        assertEquals(listOf("1", "2", "3", "3", "3"), listOf(1, 2, 3, 4, 50).map(::loginAttemptBucket))
    }

    @Test fun abandonCarriesExactlyItsFourParameters() {
        val event = AnalyticsEvent.LoginAbandon(AgentProvider.CODEX, LoginStage.BROWSER_WAIT, 60_001, 2)
        assertEquals("login_abandon", event.eventName)
        assertEquals(
            mapOf("provider" to "codex", "stage" to "browser_wait", "elapsed" to "gt60s", "attempt" to "2"),
            event.parameters,
        )
    }

    // endregion

    @Test fun closingAnOpenAttemptLogsAbandonWithElapsedAndAttempt() {
        go(browser())
        now += 15_000
        go(AddAgentPhase.PickProvider)

        assertEquals(listOf("login_start", "login_abandon"), names())
        assertEquals(
            listOf(mapOf("provider" to "claude", "stage" to "browser_wait", "elapsed" to "10_60s", "attempt" to "1")),
            abandons(),
        )
    }

    @Test fun elapsedIsMeasuredFromLoginStartAcrossStages() {
        val start = browser()
        go(start)
        now += 50_000
        go(start.copy(manualEntry = true))
        now += 20_000
        go(AddAgentPhase.PickProvider)

        assertEquals("code_entry", abandons().single()["stage"])
        assertEquals("gt60s", abandons().single()["elapsed"])
    }

    @Test fun updatesInPlaceDoNotStartTheAttemptAgain() {
        val start = browser()
        go(start)
        go(start.copy(isPresenting = true))
        go(start.copy(isPresenting = false, wasCancelled = true))
        go(start.copy(isPresenting = true))

        assertEquals(listOf("login_start"), names())
    }

    @Test fun attemptCountsStartsPerProviderUntilASuccess() {
        repeat(4) {
            go(browser(AgentProvider.CLAUDE))
            go(AddAgentPhase.PickProvider)
        }
        go(browser(AgentProvider.CODEX))
        go(AddAgentPhase.PickProvider)
        assertEquals(listOf("1", "2", "3", "3", "1"), abandons().map { it["attempt"] })

        // A failed attempt still counts as a start.
        go(AddAgentPhase.ApiKey(AgentProvider.KIMI))
        go(AddAgentPhase.Failed("x", LoginStage.API_KEY_ENTRY, "http_401"))
        go(AddAgentPhase.PickProvider)
        go(AddAgentPhase.ApiKey(AgentProvider.KIMI))
        go(AddAgentPhase.PickProvider)
        assertEquals("2", abandons().last()["attempt"])

        // Claude succeeds: its count starts over, Codex keeps its own.
        go(browser(AgentProvider.CLAUDE))
        go(AddAgentPhase.Authenticating)
        go(AddAgentPhase.Completed)
        go(AddAgentPhase.PickProvider)
        go(browser(AgentProvider.CLAUDE))
        go(AddAgentPhase.PickProvider)
        go(browser(AgentProvider.CODEX))
        go(AddAgentPhase.PickProvider)
        assertEquals(listOf("1", "2"), abandons().takeLast(2).map { it["attempt"] })
    }

    @Test fun aCompletedAttemptReturningToTheListIsNotAnAbandon() {
        go(browser())
        go(AddAgentPhase.Authenticating)
        go(AddAgentPhase.Completed)
        // The screen resets the flow right after completing (and the dismiss handler again).
        go(AddAgentPhase.PickProvider)
        go(AddAgentPhase.PickProvider)

        assertEquals(listOf("login_start"), names())
    }

    @Test fun aFailedAttemptClosedAfterwardsIsNotAnAbandon() {
        go(AddAgentPhase.DeviceFlow(AgentProvider.COPILOT))
        go(AddAgentPhase.Failed("x", LoginStage.DEVICE_POLL, "timed_out"))
        go(AddAgentPhase.PickProvider)

        assertEquals(listOf("login_start", "login_fail"), names())
        assertEquals("device_poll", logged[1].parameters["stage"])
    }

    @Test fun retryingAKeptCodeIsTheSameAttempt() {
        go(browser())
        go(AddAgentPhase.Authenticating)
        go(AddAgentPhase.Failed("offline", LoginStage.EXCHANGE, "network", canRetryExchange = true))
        go(AddAgentPhase.Authenticating)
        go(AddAgentPhase.Failed("offline", LoginStage.EXCHANGE, "network", canRetryExchange = true))

        assertEquals(listOf("login_start", "login_fail", "login_fail"), names())
        assertEquals(listOf("claude", "claude"), logged.drop(1).map { it.parameters["provider"] })
    }

    @Test fun aWrongPastedCodeIsReportedOncePerMessage() {
        val entry = browser().copy(manualEntry = true)
        go(entry)
        go(entry.copy(inlineError = "invalid"))
        go(entry.copy(inlineError = "invalid", isPresenting = true))

        assertEquals(listOf("login_start", "login_fail"), names())
        assertEquals("code_parse", logged[1].parameters["code"])
    }
}
