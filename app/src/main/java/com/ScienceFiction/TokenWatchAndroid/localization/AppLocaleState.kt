package com.ScienceFiction.TokenWatchAndroid.localization

/** Thread-safe language bridge for network errors created outside the Compose state tree. */
class AppLocaleState(initialLanguage: AppLanguage = AppLanguage.SYSTEM) {
    @Volatile
    var language: AppLanguage = initialLanguage

    fun l10n(): L10n = L10n(language.resolved())
}
