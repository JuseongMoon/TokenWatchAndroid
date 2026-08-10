package com.ScienceFiction.TokenWatchAndroid

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ScienceFiction.TokenWatchAndroid.auth.oauth.ClaudeOAuthClient
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OAuthWebViewNavigationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    @Suppress("DEPRECATION")
    fun claudeCallbackIsCapturedByInstalledWebViewClient() {
        composeRule.onNodeWithText("[ + ADD AGENT ]").performClick()
        composeRule.onNodeWithText("claude").performClick()

        val webView = AtomicReference<WebView?>()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.activityRule.scenario.onActivity { activity ->
                webView.set(activity.window.decorView.findWebView())
            }
            webView.get() != null
        }

        // Invoke the installed client directly so the assertion is independent of external network
        // availability and WebView policies for app-initiated loadUrl/postUrl calls.
        val intercepted = AtomicBoolean(false)
        composeRule.activityRule.scenario.onActivity {
            intercepted.set(
                webView.get()!!.webViewClient.shouldOverrideUrlLoading(
                    webView.get()!!,
                    "${ClaudeOAuthClient.CALLBACK_PREFIX}?code=instrumented-code&state=wrong-state",
                ),
            )
        }
        assertTrue(intercepted.get())
        composeRule.waitForIdle()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("!! LOGIN FAILED").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("!! LOGIN FAILED").assertIsDisplayed()
    }
}

private fun View.findWebView(): WebView? {
    if (this is WebView) return this
    if (this !is ViewGroup) return null
    repeat(childCount) { index ->
        getChildAt(index).findWebView()?.let { return it }
    }
    return null
}
