package com.ScienceFiction.TokenWatchAndroid.analytics

import com.ScienceFiction.TokenWatchAndroid.domain.AgentProvider

/**
 * The process-wide part of the login funnel behind login_abandon's `elapsed` and `attempt` (D020),
 * the counterpart of the iOS `LoginFunnelTracker` singleton.
 *
 * One instance lives in the app container, so the counts outlive the add-agent view model (an
 * activity that finishes and comes back keeps them) and start over only when the process does.
 * Nothing is persisted.
 */
class LoginAttemptTracker(private val now: () -> Long = System::currentTimeMillis) {
    /** login_start count per provider since its last success. */
    private val attempts = mutableMapOf<AgentProvider, Int>()

    /** Wall-clock time of each provider's latest login_start. */
    private val startedAt = mutableMapOf<AgentProvider, Long>()

    /** A login_start for [provider]: one more attempt, timed from now. */
    @Synchronized
    fun started(provider: AgentProvider) {
        attempts[provider] = (attempts[provider] ?: 0) + 1
        startedAt[provider] = now()
    }

    /** [provider] signed in: its next attempt counts from one again. */
    @Synchronized
    fun succeeded(provider: AgentProvider) {
        attempts.remove(provider)
        startedAt.remove(provider)
    }

    /** The login_abandon event for [provider]'s current attempt, left at [stage]. */
    @Synchronized
    fun abandon(provider: AgentProvider, stage: LoginStage): AnalyticsEvent.LoginAbandon {
        val current = now()
        return AnalyticsEvent.LoginAbandon(
            provider,
            stage,
            elapsedMillis = current - (startedAt[provider] ?: current),
            attempt = attempts[provider] ?: 1,
        )
    }
}
