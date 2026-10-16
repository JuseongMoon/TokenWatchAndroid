package com.ScienceFiction.TokenWatchAndroid.analytics

import com.ScienceFiction.TokenWatchAndroid.account.AccountFailStage
import com.ScienceFiction.TokenWatchAndroid.domain.account.AccountMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountEventsTest {
    private val events: List<AnalyticsEvent> = listOf(
        AnalyticsEvent.AccountLogin(AccountMethod.APPLE, isNew = true),
        AnalyticsEvent.AccountLoginFail(AccountMethod.GOOGLE, AccountFailStage.CONFLICT),
        AnalyticsEvent.AccountLink(AccountMethod.GOOGLE),
        AnalyticsEvent.AccountLogout,
        AnalyticsEvent.AccountDelete,
        AnalyticsEvent.ProfileUpdate(ProfileField.NICKNAME),
    )

    @Test fun namesAndParametersMatchTheContract() {
        assertEquals(
            listOf("account_login", "account_login_fail", "account_link", "account_logout", "account_delete", "profile_update"),
            events.map { it.eventName },
        )
        assertEquals(mapOf("method" to "apple", "is_new" to "1"), events[0].parameters)
        assertEquals(mapOf("method" to "google", "is_new" to "0"), AnalyticsEvent.AccountLogin(AccountMethod.GOOGLE, false).parameters)
        assertEquals(mapOf("method" to "google", "stage" to "conflict"), events[1].parameters)
        assertEquals(mapOf("method" to "google"), events[2].parameters)
        assertEquals(emptyMap<String, String>(), events[3].parameters)
        assertEquals(mapOf("surface" to "app"), events[4].parameters)
        assertEquals(mapOf("field" to "nickname"), events[5].parameters)
        assertEquals(listOf("cancel", "provider", "firebase", "conflict"), AccountFailStage.entries.map { it.wireId })
        assertEquals(listOf("nickname", "avatar"), ProfileField.entries.map { it.wireId })
    }

    @Test fun parametersCarryOnlyEnumerations() {
        val allowed = setOf("apple", "google", "0", "1", "cancel", "provider", "firebase", "conflict", "app", "nickname", "avatar")
        events.flatMap { it.parameters.values }.forEach { assertTrue("unexpected value $it", it in allowed) }
    }

    @Test fun accountEventsAreNotProviderScopedAndNeverReachTheDiagnosticReport() {
        events.forEach {
            assertFalse(it.isProviderScoped)
            assertFalse(it is AnalyticsEvent.LoginFail)
        }
    }

    @Test fun theThreeScreensCarryAScreenClass() {
        assertEquals("account_login" to "AccountLoginScreen", ScreenName.ACCOUNT_LOGIN.let { it.wireId to it.screenClass })
        assertEquals("profile_setup" to "ProfileSetupScreen", ScreenName.PROFILE_SETUP.let { it.wireId to it.screenClass })
        assertEquals("account" to "AccountScreen", ScreenName.ACCOUNT.let { it.wireId to it.screenClass })
    }
}
