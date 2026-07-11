package com.ScienceFiction.TokenWatchAndroid.data

import com.ScienceFiction.TokenWatchAndroid.localization.AppLanguage

data class AppSettings(
    val refreshInterval: Int = DEFAULT_REFRESH_INTERVAL,
    val keepScreenOn: Boolean = false,
    val hideUnusedWindows: Boolean = false,
    val gaugeCritter: Boolean = true,
    val heartbeatCursor: Boolean = false,
    val heartbeatTracking: Boolean = false,
    val heartbeatTargets: Set<String> = emptySet(),
    val language: AppLanguage = AppLanguage.SYSTEM,
) {
    companion object {
        const val DEFAULT_REFRESH_INTERVAL = 60
    }
}

/** Pure validation shared by DataStore and local JVM tests. */
object AppSettingsCodec {
    val supportedRefreshIntervals: Set<Int> = setOf(-1, 0, 30, 60, 300)

    fun decode(
        refreshInterval: Int? = null,
        keepScreenOn: Boolean? = null,
        hideUnusedWindows: Boolean? = null,
        gaugeCritter: Boolean? = null,
        heartbeatCursor: Boolean? = null,
        heartbeatTracking: Boolean? = null,
        heartbeatTargets: Set<String>? = null,
        languageWireId: String? = null,
    ): AppSettings = normalize(
        AppSettings(
            refreshInterval = refreshInterval ?: AppSettings.DEFAULT_REFRESH_INTERVAL,
            keepScreenOn = keepScreenOn ?: false,
            hideUnusedWindows = hideUnusedWindows ?: false,
            gaugeCritter = gaugeCritter ?: true,
            heartbeatCursor = heartbeatCursor ?: false,
            heartbeatTracking = heartbeatTracking ?: false,
            heartbeatTargets = heartbeatTargets.orEmpty(),
            language = AppLanguage.fromWireId(languageWireId),
        ),
    )

    fun normalize(settings: AppSettings): AppSettings = settings.copy(
        refreshInterval = settings.refreshInterval.takeIf(supportedRefreshIntervals::contains)
            ?: AppSettings.DEFAULT_REFRESH_INTERVAL,
        heartbeatTargets = settings.heartbeatTargets.filterTo(linkedSetOf()) { it.isNotBlank() },
    )
}
