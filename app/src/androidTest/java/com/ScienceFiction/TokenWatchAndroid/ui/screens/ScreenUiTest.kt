package com.ScienceFiction.TokenWatchAndroid.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ScienceFiction.TokenWatchAndroid.data.AppSettings
import com.ScienceFiction.TokenWatchAndroid.domain.Agent
import com.ScienceFiction.TokenWatchAndroid.domain.AgentProvider
import com.ScienceFiction.TokenWatchAndroid.domain.AgentSnapshot
import com.ScienceFiction.TokenWatchAndroid.domain.ServiceHealth
import com.ScienceFiction.TokenWatchAndroid.domain.UsageStyle
import com.ScienceFiction.TokenWatchAndroid.domain.UsageWindow
import com.ScienceFiction.TokenWatchAndroid.domain.WindowKind
import com.ScienceFiction.TokenWatchAndroid.localization.L10n
import com.ScienceFiction.TokenWatchAndroid.localization.Lang
import com.ScienceFiction.TokenWatchAndroid.ui.components.UsageBar
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
    fun agentCard_hidesEmailAndExposesInlineStatusBadge() {
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
        composeRule.onNodeWithContentDescription("operational").assertIsDisplayed()
        composeRule.onNodeWithText("operational").assertDoesNotExist()
        composeRule.onNodeWithText("private@example.com").assertDoesNotExist()
    }

    @Test
    fun agentCard_omitsStatusBadgeWhenProviderHasNoStatusEndpoint() {
        composeRule.setContent {
            TokenWatchTheme {
                AgentCard(
                    agent = Agent(provider = AgentProvider.OPENROUTER, accountLabel = "pro"),
                    snapshot = null,
                    isLoading = false,
                    serviceHealth = null,
                    hideUnusedWindows = false,
                    loc = L10n(Lang.EN),
                )
            }
        }

        composeRule.onNodeWithText("[or] OPENROUTER").assertIsDisplayed()
        composeRule.onNodeWithText("· pro").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("operational").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("unknown").assertDoesNotExist()
    }

    @Test
    fun creditUsageBar_showsEstimatedBalanceAndRemainingSemantics() {
        composeRule.setContent {
            TokenWatchTheme {
                UsageBar(
                    window = creditWindow(),
                    loc = L10n(Lang.EN),
                    gaugeCritterEnabled = false,
                )
            }
        }

        composeRule.onNodeWithText("~18.00 USD left").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("18% left").assertIsDisplayed()
    }

    @Test
    fun creditDetail_confirmsAndDispatchesPeakReset() {
        var resetWindowLabel: String? = null
        val agent = Agent(provider = AgentProvider.OPENROUTER, accountLabel = "api")
        val loc = L10n(Lang.EN)
        composeRule.setContent {
            TokenWatchTheme {
                DetailScreen(
                    agent = agent,
                    snapshot = AgentSnapshot(
                        windows = listOf(creditWindow()),
                        planLabel = null,
                        fetchedAt = Instant.parse("2026-07-11T00:00:00Z"),
                        error = null,
                    ),
                    isLoading = false,
                    account = DetailAccountUiState(isLoading = false),
                    serviceHealth = ServiceHealth.UNKNOWN,
                    hideUnusedWindows = false,
                    gaugeCritterEnabled = false,
                    loc = loc,
                    showLogoutConfirmation = false,
                    onBack = {},
                    onRefresh = {},
                    onOpenStatusPage = {},
                    onResetCreditPeak = { resetWindowLabel = it },
                    onLogoutRequest = {},
                    onLogoutConfirm = {},
                    onLogoutDismiss = {},
                )
            }
        }

        composeRule.onNodeWithText("[reset]").performScrollTo().performClick()
        composeRule.onNodeWithText("Reset gauge scale").assertIsDisplayed()
        composeRule.onNodeWithText("[ ${loc.creditResetConfirm} ]").performClick()
        composeRule.runOnIdle {
            assertTrue(resetWindowLabel == "Balance")
        }
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

    @Test
    fun settingsScreen_marksSelectedHeartbeatTargetWithV() {
        val agent = Agent(provider = AgentProvider.CODEX)
        val window = UsageWindow(
            label = "Current session",
            usedPercent = 25.0,
            resetsAt = null,
            kind = WindowKind.SESSION,
        )
        val targetId = "${agent.id}|${window.label}"

        composeRule.setContent {
            TokenWatchTheme {
                SettingsScreen(
                    settings = AppSettings(
                        heartbeatCursor = true,
                        heartbeatTracking = true,
                        heartbeatTargets = setOf(targetId),
                    ),
                    agents = listOf(agent),
                    snapshots = mapOf(
                        agent.id to AgentSnapshot(
                            windows = listOf(window),
                            planLabel = null,
                            fetchedAt = Instant.parse("2026-07-11T00:00:00Z"),
                            error = null,
                        ),
                    ),
                    autoIntervalSeconds = 60,
                    appVersion = "1.0 (1)",
                    loc = L10n(Lang.EN),
                    onSettingsChange = {},
                    onLogoutAgent = {},
                    onDone = {},
                )
            }
        }

        composeRule.onNodeWithText("[v]").performScrollTo().assertIsDisplayed()
    }

    private fun creditWindow() = UsageWindow(
        label = "Balance",
        usedPercent = 82.0,
        resetsAt = null,
        kind = WindowKind.WEEKLY,
        style = UsageStyle.CREDIT_GAUGE,
        valueText = "18.00 USD left",
        balanceRemaining = 18.0,
        estimatedTotal = true,
    )
}
