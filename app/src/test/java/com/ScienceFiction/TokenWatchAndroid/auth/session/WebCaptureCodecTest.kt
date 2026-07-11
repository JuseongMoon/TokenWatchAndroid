package com.ScienceFiction.TokenWatchAndroid.auth.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebCaptureCodecTest {
    @Test
    fun decodesAndroidEvaluateJavascriptDoubleEncodedLocalStorage() {
        val androidResult =
            "\"{\\\"auth-token\\\":\\\"secret\\\",\\\"account-id\\\":\\\"user-1\\\"}\""

        assertEquals(
            mapOf("auth-token" to "secret", "account-id" to "user-1"),
            WebCaptureCodec.decodeLocalStorageEvaluation(androidResult),
        )
    }

    @Test
    fun preservesEscapesAndUnicodeAcrossBothJsonLayers() {
        val androidResult =
            "\"{\\\"quoted\\\":\\\"a\\\\\\\"b\\\",\\\"slash\\\":\\\"c\\\\\\\\d\\\",\\\"label\\\":\\\"한글\\\"}\""

        val decoded = WebCaptureCodec.decodeLocalStorageEvaluation(androidResult)

        assertEquals("a\"b", decoded?.get("quoted"))
        assertEquals("c\\d", decoded?.get("slash"))
        assertEquals("한글", decoded?.get("label"))
    }

    @Test
    fun rejectsMissingOuterJavascriptStringEncoding() {
        assertNull(WebCaptureCodec.decodeLocalStorageEvaluation("{\"token\":\"abc\"}"))
        assertNull(WebCaptureCodec.decodeLocalStorageEvaluation("null"))
        assertNull(WebCaptureCodec.decodeLocalStorageEvaluation(null))
    }

    @Test
    fun cookieHeaderPreservesEqualsInsideValues() {
        val cookies = WebCaptureCodec.decodeCookieHeader(
            header = "session=abc==; theme=dark; malformed",
            domain = "GROK.COM",
        )

        assertEquals(2, cookies.size)
        assertEquals(BrowserCookie("session", "abc==", "grok.com"), cookies[0])
        assertEquals(BrowserCookie("theme", "dark", "grok.com"), cookies[1])
        assertTrue(WebCaptureCodec.decodeCookieHeader(null, "grok.com").isEmpty())
    }
}
