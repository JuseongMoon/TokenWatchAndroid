package com.ScienceFiction.TokenWatchAndroid

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ScienceFiction.TokenWatchAndroid.ui.screens.HeaderTags
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
        composeRule.onNodeWithTag(HeaderTags.SETTINGS).assertIsDisplayed()
        composeRule.onNodeWithTag(HeaderTags.ANNOUNCEMENTS).assertIsDisplayed()
        composeRule.onNodeWithText("[ + ADD AGENT ]").assertIsDisplayed()
    }
}
