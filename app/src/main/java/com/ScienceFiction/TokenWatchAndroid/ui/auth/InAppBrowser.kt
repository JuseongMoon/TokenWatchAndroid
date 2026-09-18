package com.ScienceFiction.TokenWatchAndroid.ui.auth

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import androidx.browser.auth.AuthTabIntent
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import com.ScienceFiction.TokenWatchAndroid.MainActivity
import com.ScienceFiction.TokenWatchAndroid.auth.oauth.LoopbackCallbackServer

/**
 * In-app browser surfaces for sign-in related pages, the Android counterpart of iOS
 * `ASWebAuthenticationSession` and `InAppSafari` (7d0d9da).
 *
 * - [launchSignIn] opens an Auth Tab, which closes itself when the loopback listener redirects to
 *   `tokenwatch://login-complete`. A browser without Auth Tab support opens the same intent as a
 *   Custom Tab, and that redirect then reaches `LoginCompleteActivity` instead.
 * - [open] shows approval, API-key and code pages in a Custom Tab, so the user stays in the app.
 *
 * Both run on the browser engine, where popup-based sign-in (Google) works, unlike a WebView.
 */
internal object InAppBrowser {
    /** The Custom Tabs browser that would open, or null when none is installed. */
    private fun customTabsPackage(context: Context): String? =
        runCatching { CustomTabsClient.getPackageName(context, null) }.getOrNull()

    /**
     * Whether "sign in with another account" can really start without the browser's signed-in
     * session. Without that support the button would behave exactly like the normal sign-in.
     */
    fun supportsEphemeralSignIn(context: Context): Boolean = customTabsPackage(context)
        ?.let { runCatching { CustomTabsClient.isEphemeralBrowsingSupported(context, it) }.getOrDefault(false) }
        ?: false

    fun launchSignIn(launcher: ActivityResultLauncher<Intent>, url: String, ephemeral: Boolean): Boolean {
        val intent = AuthTabIntent.Builder()
            .setEphemeralBrowsingEnabled(ephemeral)
            .setColorScheme(CustomTabsIntent.COLOR_SCHEME_DARK)
            .build()
        return try {
            intent.launch(launcher, url.toUri(), LoopbackCallbackServer.SESSION_CALLBACK_SCHEME)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    fun open(context: Context, url: String): Boolean {
        val intent = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .setColorScheme(CustomTabsIntent.COLOR_SCHEME_DARK)
            .build()
        return try {
            intent.launchUrl(context, url.toUri())
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    /**
     * Returns to the app over a tab that cannot be closed programmatically, once a login finished
     * while the tab still covered it. Best effort: newer Android versions may block an activity start
     * from under another app's window, and the user then closes the tab themselves.
     */
    fun bringAppToFront(context: Context) {
        runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }
    }
}
