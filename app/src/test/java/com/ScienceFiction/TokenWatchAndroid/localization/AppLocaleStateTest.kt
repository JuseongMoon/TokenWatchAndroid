package com.ScienceFiction.TokenWatchAndroid.localization

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLocaleStateTest {
    @Test
    fun followsTheLatestExplicitLanguage() {
        val state = AppLocaleState(AppLanguage.ENGLISH)
        assertEquals("Login required.", state.l10n().errNotAuthenticated)

        state.language = AppLanguage.KOREAN
        assertEquals("로그인이 필요합니다.", state.l10n().errNotAuthenticated)
    }
}
