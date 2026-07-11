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
import java.util.concurrent.atomic.AtomicReference
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OAuthWebViewNavigationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun claudePostCallbackIsCapturedAfterNavigationStarts() {
        composeRule.onNodeWithText("[ + ADD AGENT ]").performClick()
        composeRule.onNodeWithText("claude").performClick()

        val webView = AtomicReference<WebView?>()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.activityRule.scenario.onActivity { activity ->
                webView.set(activity.window.decorView.findWebView())
            }
            webView.get() != null
        }

        // WebViewClient.shouldOverrideUrlLoading is not invoked for POST requests. A deliberately
        // wrong state keeps this test offline: successful interception fails PKCE validation before
        // any token-exchange request can be made.
        composeRule.activityRule.scenario.onActivity {
            webView.get()!!.postUrl(
                "${ClaudeOAuthClient.CALLBACK_PREFIX}?code=instrumented-code&state=wrong-state",
                "source=instrumentation".toByteArray(),
            )
        }

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText(
                "OAuth callback state did not match the request",
                substring = true,
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(
            "OAuth callback state did not match the request",
            substring = true,
        ).assertIsDisplayed()
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
