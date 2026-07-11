package com.ScienceFiction.TokenWatchAndroid.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ScienceFiction.TokenWatchAndroid.data.AppSettings
import com.ScienceFiction.TokenWatchAndroid.domain.Agent
import com.ScienceFiction.TokenWatchAndroid.domain.AgentProvider
import com.ScienceFiction.TokenWatchAndroid.domain.AgentSnapshot
import com.ScienceFiction.TokenWatchAndroid.domain.ServiceHealth
import com.ScienceFiction.TokenWatchAndroid.domain.UsageWindow
import com.ScienceFiction.TokenWatchAndroid.domain.WindowKind
import com.ScienceFiction.TokenWatchAndroid.localization.L10n
import com.ScienceFiction.TokenWatchAndroid.localization.Lang
import com.ScienceFiction.TokenWatchAndroid.ui.theme.TokenWatchTheme
import java.time.Instant
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScreenUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun agentCard_hidesEmailAndKeepsStatusOnSeparateLine() {
        val agent = Agent(
            provider = AgentProvider.CLAUDE,
            accountLabel = "private@example.com",
        )
        composeRule.setContent {
            TokenWatchTheme {
                AgentCard(
                    agent = agent,
                    snapshot = null,
                    isLoading = false,
                    serviceHealth = ServiceHealth.OPERATIONAL,
                    hideUnusedWindows = false,
                    loc = L10n(Lang.EN),
                )
            }
        }

        composeRule.onNodeWithText("[C] CLAUDE").assertIsDisplayed()
        composeRule.onNodeWithText("operational").assertIsDisplayed()
        composeRule.onNodeWithText("private@example.com").assertDoesNotExist()
    }

    @Test
    fun agentCard_lastGoodErrorAppearsBeforeUsageGraph() {
        val error = "Too many requests. Retrying in ~4 min · usage below isn't updating."
        composeRule.setContent {
            TokenWatchTheme {
                AgentCard(
                    agent = Agent(provider = AgentProvider.CLAUDE, accountLabel = "pro"),
                    snapshot = AgentSnapshot(
                        windows = listOf(
                            UsageWindow(
                                label = "Current session",
                                usedPercent = 62.0,
                                resetsAt = null,
                                kind = WindowKind.SESSION,
                                windowSeconds = WindowKind.SESSION.defaultSeconds,
                            ),
                        ),
                        planLabel = "pro",
                        fetchedAt = Instant.parse("2026-07-11T00:00:00Z"),
                        error = error,
                    ),
                    isLoading = false,
                    serviceHealth = ServiceHealth.OPERATIONAL,
                    hideUnusedWindows = false,
                    loc = L10n(Lang.EN),
                )
            }
        }

        val errorNode = composeRule.onNodeWithText(error).assertIsDisplayed().fetchSemanticsNode()
        val usageNode = composeRule.onNodeWithText("CURRENT SESSION")
            .assertIsDisplayed()
            .fetchSemanticsNode()

        assertTrue(
            "The stale/error notice must be above the first usage graph",
            errorNode.boundsInRoot.top < usageNode.boundsInRoot.top,
        )
    }

    @Test
    fun settingsScreen_rendersKoreanHelpWithoutMaterialCards() {
        val loc = L10n(Lang.KO)
        composeRule.setContent {
            TokenWatchTheme {
                SettingsScreen(
                    settings = AppSettings(),
                    agents = emptyList(),
                    snapshots = emptyMap(),
                    autoIntervalSeconds = 60,
                    appVersion = "1.0 (1)",
                    loc = loc,
                    onSettingsChange = {},
                    onLogoutAgent = {},
                    onDone = {},
                )
            }
        }

        composeRule.onNodeWithText("SETTINGS").assertIsDisplayed()
        composeRule.onNodeWithText("시스템 언어를 따르거나 직접 선택합니다.").assertExists()
        composeRule.onNodeWithText("no accounts connected").assertExists()
    }
}
