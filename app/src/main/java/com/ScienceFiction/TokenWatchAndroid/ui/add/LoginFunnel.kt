package com.ScienceFiction.TokenWatchAndroid.ui.add

import com.ScienceFiction.TokenWatchAndroid.analytics.AnalyticsEvent
import com.ScienceFiction.TokenWatchAndroid.analytics.LoginStage
import com.ScienceFiction.TokenWatchAndroid.domain.AgentProvider

/**
 * Turns add-agent phase changes into the login funnel events: login_start, login_fail and
 * login_abandon.
 *
 * It lives in [AddAgentFlowViewModel] and sees every phase change as it happens, rather than in
 * the composition. An activity recreated mid-login (rotation, dark mode, language) therefore keeps
 * the attempt's provider, stage, start time and attempt count, so it neither logs a second
 * login_start nor loses the failure or abandon that follows.
 *
 * Not thread-safe by itself; [AddAgentFlowViewModel] serialises the calls.
 */
internal class LoginFunnel(private val now: () -> Long) {
    /** The provider of the attempt in progress; null between attempts. */
    private var provider: AgentProvider? = null
    private var stage = LoginStage.AUTHORIZE
    private var startedAt = 0L

    /** A failure that kept its code can be retried as an exchange; that retry is the same attempt. */
    private var retryProvider: AgentProvider? = null

    /** login_start count per provider since its last success (D020 `attempt`). */
    private val attempts = mutableMapOf<AgentProvider, Int>()

    fun transition(previous: AddAgentPhase, next: AddAgentPhase): List<AnalyticsEvent> {
        // An equal phase set again changes nothing on screen, so it is not a new step either.
        if (previous == next) return emptyList()
        val events = mutableListOf<AnalyticsEvent>()
        fun started(provider: AgentProvider, stage: LoginStage) {
            if (this.provider == null) {
                attempts[provider] = (attempts[provider] ?: 0) + 1
                startedAt = now()
                events += AnalyticsEvent.LoginStart(provider)
            }
            this.provider = provider
            this.stage = stage
        }
        when (next) {
            is AddAgentPhase.BrowserLogin -> {
                started(next.provider, if (next.manualEntry) LoginStage.CODE_ENTRY else LoginStage.BROWSER_WAIT)
                if (next.inlineError != null && (previous as? AddAgentPhase.BrowserLogin)?.inlineError != next.inlineError) {
                    events += AnalyticsEvent.LoginFail(next.provider, LoginStage.CODE_ENTRY, "code_parse")
                }
            }
            is AddAgentPhase.ApiKey -> started(next.provider, LoginStage.API_KEY_ENTRY)
            is AddAgentPhase.DeviceFlow -> started(next.provider, LoginStage.DEVICE_POLL)
            AddAgentPhase.Authenticating -> {
                if (provider == null) provider = retryProvider
                retryProvider = null
                if (stage != LoginStage.API_KEY_ENTRY) stage = LoginStage.EXCHANGE
            }
            is AddAgentPhase.Failed -> {
                provider?.let {
                    // The message is deliberately not forwarded: it can carry provider error text.
                    val stage = next.stage ?: stage
                    events += AnalyticsEvent.LoginFail(it, stage, next.code ?: stage.wireId)
                }
                retryProvider = provider.takeIf { next.canRetryExchange }
                provider = null
            }
            AddAgentPhase.Completed -> {
                // login_success itself is logged where the agent is stored; this ends the attempt.
                provider?.let { attempts.remove(it) }
                provider = null
                retryProvider = null
            }
            AddAgentPhase.PickProvider -> {
                // Back to the list with an attempt still open: the user left it (close, back).
                // A completed or failed attempt has already ended, so it never counts as abandoned.
                provider?.let {
                    events += AnalyticsEvent.LoginAbandon(it, stage, now() - startedAt, attempts[it] ?: 1)
                }
                provider = null
                retryProvider = null
            }
        }
        return events
    }
}
