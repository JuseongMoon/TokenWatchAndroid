package com.ScienceFiction.TokenWatchAndroid

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LaunchSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun mainShellMatchesBaseline() {
        composeRule.onNodeWithContentDescription("tokenwatch", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("[SETTINGS]").assertIsDisplayed()
        composeRule.onNodeWithText("[ + ADD AGENT ]").assertIsDisplayed()
    }
}
