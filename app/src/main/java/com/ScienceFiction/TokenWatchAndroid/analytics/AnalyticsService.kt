package com.ScienceFiction.TokenWatchAndroid.analytics

import android.content.Context
import android.os.Bundle
import com.ScienceFiction.TokenWatchAndroid.BuildConfig
import com.ScienceFiction.TokenWatchAndroid.data.AppSettings
import com.ScienceFiction.TokenWatchAndroid.domain.Agent
import com.ScienceFiction.TokenWatchAndroid.domain.WorkHoursSchedule
import com.ScienceFiction.TokenWatchAndroid.localization.AppLanguage
import com.google.firebase.analytics.FirebaseAnalytics

/**
 * The app's only Firebase touchpoint, mirroring the iOS `AnalyticsService`.
 *
 * Three gates, in order:
 *  1. **Unconfigured** — if the SDK cannot start (no `google-services.json` in the build), every
 *     call is a no-op rather than a crash.
 *  2. **Debug builds** — off unless `ANALYTICS_DEBUG=true` is set in `local.properties`, so day-to-day
 *     development does not pollute production statistics. This stands in for the iOS
 *     `-FIRDebugEnabled` launch argument; turn it on when verifying in DebugView.
 *  3. **Opt-out** — the PRIVACY toggle in settings. Default on; switching it off stops SDK
 *     collection *and* short-circuits this wrapper.
 *
 * What must never be logged is enforced at the [AnalyticsEvent] boundary: no account labels, ids,
 * emails, tokens, API keys, raw error text, OAuth URLs, or usage figures.
 */
class AnalyticsService(
    context: Context,
    private val isDemo: () -> Boolean = { false },
) {
    private val analytics: FirebaseAnalytics? = runCatching {
        FirebaseAnalytics.getInstance(context.applicationContext)
    }.getOrNull()

    /** Mirrors the persisted opt-out so non-Compose callers can read it without a Flow. */
    @Volatile
    private var collectionEnabled: Boolean = true

    private val isUsable: Boolean
        get() = analytics != null && collectionEnabled && (!BuildConfig.DEBUG || BuildConfig.ANALYTICS_DEBUG)

    /** Applies the opt-out to the SDK as well, so nothing is buffered while it is off. */
    fun setCollectionEnabled(enabled: Boolean) {
        collectionEnabled = enabled
        analytics?.setAnalyticsCollectionEnabled(enabled && (!BuildConfig.DEBUG || BuildConfig.ANALYTICS_DEBUG))
    }

    fun log(event: AnalyticsEvent) {
        if (!isUsable) return
        val demo = isDemo()
        if (event.isProviderScoped && demo) return
        val parameters = if (demo) event.parameters - "provider" else event.parameters
        analytics?.logEvent(
            event.eventName,
            Bundle().apply { parameters.forEach { (key, value) -> putString(key, value) } },
        )
    }

    /**
     * Agent-derived plus settings-derived properties. Skipped in demo mode so sample agents never
     * overwrite the real profile.
     */
    fun syncUserProperties(agents: List<Agent>, settings: AppSettings) {
        if (!isUsable || isDemo()) return
        analytics?.setUserProperty("agents_count", agents.size.toString())
        val tags = agents.map { it.provider.analyticsShortTag }.distinct().sorted().joinToString(",")
        analytics?.setUserProperty("providers", tags.ifEmpty { null })
        syncSettingsProperties(settings)
    }

    /** Settings-only properties, for call sites that have no agent list. */
    fun syncSettingsProperties(settings: AppSettings) {
        if (!isUsable) return
        analytics?.setUserProperty("refresh_mode", refreshMode(settings.refreshInterval))
        analytics?.setUserProperty("notify", notifyMode(settings))
        analytics?.setUserProperty(
            "work_hours",
            workHoursBucket(
                hours = WorkHoursSchedule.decode(settings.workHours).onHours,
                enabled = WorkHoursSchedule.isEnabled(settings.workHours, settings.workHoursEnabled),
            ),
        )
        analytics?.setUserProperty("heartbeat", heartbeatMode(settings))
        analytics?.setUserProperty(
            "app_lang",
            when (settings.language) {
                AppLanguage.SYSTEM -> "system"
                AppLanguage.KOREAN -> "ko"
                AppLanguage.ENGLISH -> "en"
            },
        )
    }

    private fun refreshMode(interval: Int): String = when (interval) {
        -1 -> "auto"
        0 -> "off"
        else -> "${interval}s"
    }

    private fun notifyMode(settings: AppSettings): String = when {
        settings.notifySessionResets && settings.notifyWeeklyResets -> "both"
        settings.notifySessionResets -> "session"
        settings.notifyWeeklyResets -> "weekly"
        else -> "none"
    }

    private fun heartbeatMode(settings: AppSettings): String = when {
        !settings.heartbeatCursor -> "off"
        settings.heartbeatTracking -> "usage"
        else -> "heart"
    }

    companion object {
        /**
         * Weekly work hours as a bucket. A switched-off schedule reports "off" regardless of how
         * many hours are saved — the feature has no effect, which is what the property measures.
         */
        fun workHoursBucket(hours: Int, enabled: Boolean): String = when {
            !enabled || hours < 1 -> "off"
            hours <= 20 -> "1-20"
            hours <= 40 -> "21-40"
            else -> "41+"
        }
    }
}
