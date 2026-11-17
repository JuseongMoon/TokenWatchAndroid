package com.ScienceFiction.TokenWatchAndroid.ui

import com.ScienceFiction.TokenWatchAndroid.analytics.AddAgentSource
import com.ScienceFiction.TokenWatchAndroid.analytics.AnalyticsEvent
import com.ScienceFiction.TokenWatchAndroid.analytics.ScreenName
import com.ScienceFiction.TokenWatchAndroid.domain.AgentProvider
import com.ScienceFiction.TokenWatchAndroid.localization.L10n
import com.ScienceFiction.TokenWatchAndroid.localization.Lang
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * D022, matching iOS `FirstRunEntryTests` (586c045): the add-agent screen_view says how it was
 * reached, the first-screen and demo texts are the iOS strings, and demo mode's connect button goes
 * through the usual demo exit before the add-agent screen opens.
 */
class FirstRunEntryTest {
    @Test fun theAddAgentScreenViewCarriesHowItWasReached() {
        val cases = listOf(
            AddAgentSource.EMPTY_STATE to "empty_state",
            AddAgentSource.DEMO to "demo",
            AddAgentSource.LIST to "list",
        )
        for ((source, tag) in cases) {
            val parameters = AnalyticsEvent.ScreenView(ScreenName.ADD_AGENT, source = source).parameters
            assertEquals("add_agent", parameters["screen_name"])
            assertEquals(tag, parameters["source"])
            assertNull(parameters["provider"])
        }
    }

    @Test fun otherScreenViewsAreUnchanged() {
        assertEquals(setOf("screen_name", "screen_class"), AnalyticsEvent.ScreenView(ScreenName.MAIN).parameters.keys)
        assertEquals(
            setOf("screen_name", "screen_class", "provider"),
            AnalyticsEvent.ScreenView(ScreenName.AGENT_DETAIL, AgentProvider.CODEX).parameters.keys,
        )
    }

    /** The add-agent screen opened after demo mode must be counted: screen_view is not demo-gated. */
    @Test fun screenViewIsNotADemoGatedProviderEvent() {
        assertFalse(AnalyticsEvent.ScreenView(ScreenName.ADD_AGENT, source = AddAgentSource.DEMO).isProviderScoped)
    }

    @Test fun firstScreenAndDemoTextsAreTheIosStrings() {
        val ko = L10n(Lang.KO)
        val en = L10n(Lang.EN)
        assertEquals("아직 연결한 계정이 없습니다. 쓰고 있는 AI 서비스를 연결하면 남은 사용량이 여기에 표시됩니다.", ko.emptyStateMessage)
        assertEquals("No accounts connected yet. Connect the AI services you use to see your remaining usage here.", en.emptyStateMessage)
        assertEquals("데모 화면입니다. 내 계정을 연결하면 실제 사용량이 표시됩니다.", ko.demoConnectMessage)
        assertEquals("This is a demo. Connect your own account to see your real usage.", en.demoConnectMessage)
        for (text in listOf(ko.emptyStateMessage, en.emptyStateMessage, ko.demoConnectMessage, en.demoConnectMessage)) {
            assertFalse(text, text.contains("몬스터") || text.lowercase().contains("monster"))
        }
    }

    @Test fun connectingFromDemoEndsDemoModeFirstThenOpensTheAddScreen() = runBlocking {
        val steps = mutableListOf<String>()

        connectFromDemo(
            log = { steps += it.eventName },
            exitDemo = { steps += "demo off" },
            openAdd = { steps += "add:${it.wireId}" },
        )

        assertEquals(listOf("demo_end", "demo off", "add:demo"), steps)
    }

    @Test fun theDemoExitButtonStillLogsDemoEndThenLeaves() = runBlocking {
        val steps = mutableListOf<String>()

        leaveDemo(log = { steps += it.eventName }, exitDemo = { steps += "demo off" })

        assertEquals(listOf("demo_end", "demo off"), steps)
    }
}
