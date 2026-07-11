package com.ScienceFiction.TokenWatchAndroid.auth.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionCaptureTest {
    @Test
    fun cursorExtractsUserIdAndPreservesCookie() {
        val tokens = CursorSessionAuth.probe(
            listOf(BrowserCookie(CursorSessionAuth.cookieName, "user_abc::jwtpayload", ".cursor.com")),
        )
        assertNotNull(tokens)
        assertEquals("user_abc", tokens?.accountId)
        assertEquals("user_abc::jwtpayload", tokens?.accessToken)
    }

    @Test
    fun cursorHandlesEncodedSeparatorAndRejectsIncompleteCookies() {
        val encoded = CursorSessionAuth.probe(
            listOf(BrowserCookie(CursorSessionAuth.cookieName, "user_xyz%3A%3Ajwt", "cursor.com")),
        )
        assertEquals("user_xyz", encoded?.accountId)
        assertNull(CursorSessionAuth.probe(listOf(BrowserCookie(CursorSessionAuth.cookieName, "pending", "cursor.com"))))
        assertNull(CursorSessionAuth.probe(emptyList()))
    }

    @Test
    fun grokIgnoresInfrastructureOnlyCookies() {
        val infrastructure = GrokSessionAuth.infrastructureCookies.map {
            BrowserCookie(it, "value", ".grok.com")
        }
        assertNull(GrokSessionAuth.probe(infrastructure))
    }

    @Test
    fun grokCapturesCompleteCookieHeader() {
        val tokens = GrokSessionAuth.probe(
            listOf(
                BrowserCookie("grok_device_id", "device", ".grok.com"),
                BrowserCookie("sso", "sessiontoken", "grok.com"),
                BrowserCookie("other", "ignored", "example.com"),
            ),
        )
        assertNotNull(tokens)
        assertTrue(tokens?.accessToken.orEmpty().contains("sso=sessiontoken"))
        assertTrue(tokens?.accessToken.orEmpty().contains("grok_device_id=device"))
    }

    @Test
    fun windsurfRequiresCoreTokenAndPacksHeaders() {
        assertNull(WindsurfSessionAuth.probe(mapOf("theme" to "dark")))

        val tokens = WindsurfSessionAuth.probe(
            linkedMapOf(
                "windsurf-auth-token" to "AUTH",
                "windsurf-account-id" to "acc1",
                "windsurf-primary-org-id" to "org1",
            ),
        )
        val headers = tokens?.let(WindsurfSessionAuth::unpackHeaders)
        assertEquals("AUTH", headers?.get("x-auth-token"))
        assertEquals("acc1", headers?.get("x-devin-account-id"))
        assertEquals("org1", headers?.get("x-devin-primary-org-id"))
    }
}
