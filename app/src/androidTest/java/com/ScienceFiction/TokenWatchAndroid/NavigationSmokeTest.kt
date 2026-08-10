package com.ScienceFiction.TokenWatchAndroid

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ScienceFiction.TokenWatchAndroid.domain.AgentProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun addProviderAndSettingsSurfacesAreReachable() {
        val lastProvider = AgentProvider.entries.last()

        composeRule.onNodeWithText("[ + ADD AGENT ]").performClick()
        composeRule.onNodeWithText("ADD AGENT").assertIsDisplayed()
        composeRule.onNodeWithText("claude").assertIsDisplayed()
        composeRule.onNodeWithText(lastProvider.displayName.lowercase())
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("[esc]").performClick()

        composeRule.onNodeWithText("[SETTINGS]").performClick()
        composeRule.onNodeWithText("SETTINGS").assertIsDisplayed()
        composeRule.onNodeWithText("ACCOUNTS").assertIsDisplayed()
        composeRule.onNodeWithText("AUTO-REFRESH").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("[done]").performClick()
        composeRule.onNodeWithContentDescription("tokenwatch", substring = true).assertIsDisplayed()
    }

    @Test
    fun apiKeyEntrySurvivesActivityRecreationWithoutPersistingAnAgent() {
        composeRule.onNodeWithText("[ + ADD AGENT ]").performClick()
        composeRule.onNodeWithText("openrouter").performScrollTo().performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput("dummy-key-for-rotation")

        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("dummy-key-for-rotation").assertIsDisplayed()
        composeRule.onNodeWithText("[esc]").performClick()
        composeRule.onNodeWithText("[ + ADD AGENT ]").assertIsDisplayed()
    }
}
