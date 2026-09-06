package com.ScienceFiction.TokenWatchAndroid

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ScienceFiction.TokenWatchAndroid.data.AppSettings
import com.ScienceFiction.TokenWatchAndroid.domain.Agent
import com.ScienceFiction.TokenWatchAndroid.domain.AgentProvider
import com.ScienceFiction.TokenWatchAndroid.localization.L10n
import com.ScienceFiction.TokenWatchAndroid.localization.Lang
import com.ScienceFiction.TokenWatchAndroid.ui.screens.MainScreen
import com.ScienceFiction.TokenWatchAndroid.ui.theme.TokenWatchTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainScreenInteractionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun longPressCardExposesRefreshAndDeleteActions() {
        val agent = Agent(AgentProvider.CLAUDE)
        var deleted: Agent? = null
        composeRule.setContent {
            TokenWatchTheme {
                MainScreen(
                    agents = listOf(agent),
                    snapshots = emptyMap(),
                    loadingAgentIds = emptySet(),
                    serviceStatus = emptyMap(),
                    settings = AppSettings(),
                    loc = L10n(Lang.EN),
                    appVersion = "1.0",
                    isRefreshingAll = false,
                    onSettings = {},
                    onAnnouncements = {},
                    onAddAgent = {},
                    onOpenAgent = { _ -> },
                    onMoveUp = { _ -> },
                    onMoveDown = { _ -> },
                    onRefreshAgent = { _ -> },
                    onDeleteAgent = { deleted = it },
                    onRefreshAll = {},
                )
            }
        }

        composeRule.onNodeWithText("[C] CLAUDE").performTouchInput { longClick() }
        composeRule.onNodeWithText("[ REFRESH ]").assertIsDisplayed()
        composeRule.onNodeWithText("[ DELETE ]").performClick()
        composeRule.runOnIdle { assertEquals(agent, deleted) }
    }
}
