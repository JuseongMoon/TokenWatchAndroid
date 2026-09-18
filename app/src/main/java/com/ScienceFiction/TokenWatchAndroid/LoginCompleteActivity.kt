package com.ScienceFiction.TokenWatchAndroid

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Target of `tokenwatch://login-complete`, the redirect the loopback listener answers with.
 *
 * With Auth Tab the tab consumes that redirect itself. A browser without Auth Tab support shows the
 * sign-in as a Custom Tab, which cannot close itself; its redirect lands here instead, and returning
 * to [MainActivity] with CLEAR_TOP removes the tab above it. The URL's code is deliberately ignored:
 * the listener already has it, so a forged intent can do no more than bring the app to the front.
 */
class LoginCompleteActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }
}
