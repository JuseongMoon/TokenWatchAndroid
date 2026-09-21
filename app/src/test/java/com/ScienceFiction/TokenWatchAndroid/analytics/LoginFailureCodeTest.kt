package com.ScienceFiction.TokenWatchAndroid.analytics

import com.ScienceFiction.TokenWatchAndroid.auth.device.DeviceFlowException
import com.ScienceFiction.TokenWatchAndroid.auth.oauth.OAuthException
import com.ScienceFiction.TokenWatchAndroid.domain.AgentProvider
import com.ScienceFiction.TokenWatchAndroid.network.core.UsageException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginFailureCodeTest {
    @Test
    fun classifiesOAuthFailures() {
        assertEquals("state_mismatch", LoginFailureCode.from(OAuthException.StateMismatch()))
        assertEquals("invalid_grant", LoginFailureCode.from(OAuthException.RefreshRevoked()))
        assertEquals("expired", LoginFailureCode.from(OAuthException.CodeExpired()))
        assertEquals(
            "invalid_client",
            LoginFailureCode.from(OAuthException.ExchangeFailed("401 invalid_client")),
        )
        assertEquals("http_500", LoginFailureCode.from(OAuthException.RefreshFailed("HTTP 500 boom")))
    }

    @Test
    fun classifiesDeviceFlowAndUsageFailures() {
        assertEquals("denied", LoginFailureCode.from(DeviceFlowException.Denied()))
        assertEquals("timed_out", LoginFailureCode.from(DeviceFlowException.TimedOut()))
        assertEquals("http_401", LoginFailureCode.from(UsageException.Unauthorized()))
        assertEquals("http_429", LoginFailureCode.from(UsageException.RateLimited(null)))
        assertEquals("http_418", LoginFailureCode.from(UsageException.Http(418, "teapot")))
        assertEquals("parse", LoginFailureCode.from(UsageException.Decode("bad json")))
    }

    @Test
    fun classifiesTransportFailures() {
        assertEquals("network_offline", LoginFailureCode.from(UnknownHostException()))
        assertEquals("network_timeout", LoginFailureCode.from(SocketTimeoutException()))
        assertEquals("other", LoginFailureCode.from(IllegalStateException("?")))
    }

    @Test
    fun readsTheOAuthErrorOutOfABody() {
        assertEquals("invalid_grant", LoginFailureCode.http(400, """{"error":"invalid_grant"}"""))
        assertEquals(
            "redirect_mismatch",
            LoginFailureCode.http(
                400,
                """{"error":"invalid_request","error_description":"bad redirect_uri"}""",
            ),
        )
        // No specific reason in the body: the status alone is the code, never the body text.
        assertEquals("http_503", LoginFailureCode.http(503, "upstream unavailable"))
        assertNull(LoginFailureCode.oauthError("slow_down"))
    }

    @Test
    fun sanitizesBeforeTransmission() {
        assertEquals("http_401", LoginFailureCode.sanitized("HTTP_401"))
        assertEquals("a_b_c", LoginFailureCode.sanitized("a b/c"))
        assertEquals("other", LoginFailureCode.sanitized(""))
        assertEquals(40, LoginFailureCode.sanitized("x".repeat(80)).length)
    }

    @Test
    fun reportPayloadCarriesOnlyTheSevenContractKeys() {
        val payload = LoginFailureReporter.payload(
            provider = AgentProvider.CLAUDE,
            stage = LoginStage.EXCHANGE,
            code = "INVALID GRANT",
            appVersion = "1.3.0",
            build = "15",
        )
        requireNotNull(payload)
        assertEquals("android", payload.platform)
        assertEquals("claude", payload.provider)
        assertEquals("exchange", payload.stage)
        assertEquals("invalid_grant", payload.code)

        // The server rejects any key it does not expect, so the body is asserted key by key.
        val keys = Regex("\"([A-Za-z]+)\":").findAll(payload.toJson()).map { it.groupValues[1] }
        assertEquals(
            listOf("platform", "appVersion", "build", "provider", "authKind", "stage", "code"),
            keys.toList(),
        )
    }

    @Test
    fun refusesToReportAMalformedVersion() {
        assertTrue(LoginFailureReporter.isValidVersion("1.3.0"))
        assertTrue(LoginFailureReporter.isValidVersion("15"))
        assertNull(
            LoginFailureReporter.payload(
                provider = AgentProvider.CLAUDE,
                stage = LoginStage.EXCHANGE,
                code = "other",
                appVersion = "1.3.0 (debug build)",
                build = "15",
            ),
        )
    }
}
