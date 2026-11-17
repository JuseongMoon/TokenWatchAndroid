package com.ScienceFiction.TokenWatchAndroid.analytics

import com.ScienceFiction.TokenWatchAndroid.auth.CredentialUnavailableException
import com.ScienceFiction.TokenWatchAndroid.auth.OAuthTokens
import com.ScienceFiction.TokenWatchAndroid.auth.oauth.ClaudeOAuthClient
import com.ScienceFiction.TokenWatchAndroid.auth.oauth.CodexOAuthClient
import com.ScienceFiction.TokenWatchAndroid.auth.oauth.GrokOAuthClient
import com.ScienceFiction.TokenWatchAndroid.auth.oauth.OAuthException
import com.ScienceFiction.TokenWatchAndroid.domain.AgentProvider
import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkResponse
import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * D021: `usage_fetch_error.reason` for token failures, on the same table as iOS
 * `FetchErrorReasonAlignmentTests` (b313f3d). A token request is bucketed by its cause, so a
 * provider outage or throttling is not read as users having to sign in again, and secure storage
 * that cannot be read right now is `credential`.
 */
class FetchErrorReasonAlignmentTest {
    @Test fun signedOutAndRevokedAreAuth() {
        assertEquals(FetchErrorReason.AUTH, OAuthException.NotAuthenticated().toFetchErrorReason())
        assertEquals(FetchErrorReason.AUTH, OAuthException.RefreshRevoked().toFetchErrorReason())
    }

    /** The iOS case table, row for row, through both refresh and exchange failures. */
    @Test fun tokenFailuresAreBucketedByTheirCode() {
        val cases = listOf(
            "http_400" to FetchErrorReason.AUTH, "http_401" to FetchErrorReason.AUTH,
            "invalid_grant" to FetchErrorReason.AUTH, "invalid_client" to FetchErrorReason.AUTH,
            "redirect_mismatch" to FetchErrorReason.AUTH,
            "http_429" to FetchErrorReason.RATE_LIMIT,
            "http_500" to FetchErrorReason.HTTP_5XX, "http_502" to FetchErrorReason.HTTP_5XX,
            "http_599" to FetchErrorReason.HTTP_5XX,
            "http_403" to FetchErrorReason.HTTP_4XX, "http_404" to FetchErrorReason.HTTP_4XX,
            "parse" to FetchErrorReason.PARSE,
            "http_0" to FetchErrorReason.OTHER, "http_302" to FetchErrorReason.OTHER,
            "http_x" to FetchErrorReason.OTHER, null to FetchErrorReason.OTHER, "other" to FetchErrorReason.OTHER,
        )
        for ((code, expected) in cases) {
            assertEquals("refresh $code", expected, OAuthException.RefreshFailed("m", code).toFetchErrorReason())
            assertEquals("exchange $code", expected, OAuthException.ExchangeFailed("m", code).toFetchErrorReason())
        }
    }

    @Test fun anUnreadableCredentialIsCredential() {
        for (reason in CredentialUnavailableException.Reason.entries) {
            assertEquals(FetchErrorReason.CREDENTIAL, CredentialUnavailableException(reason).toFetchErrorReason())
        }
        assertEquals("credential", FetchErrorReason.CREDENTIAL.wireId)
        val event = AnalyticsEvent.UsageFetchError(AgentProvider.CLAUDE, FetchErrorReason.CREDENTIAL)
        assertEquals("credential", event.parameters["reason"])
    }

    // region Through the real refresh paths

    private fun answering(status: Int, body: String) =
        NetworkTransport { NetworkResponse(status, emptyMap(), body.toByteArray()) }

    private val stored = OAuthTokens("access", refreshToken = "refresh")

    private fun refreshReason(provider: AgentProvider, status: Int, body: String): FetchErrorReason {
        val transport = answering(status, body)
        val error = runCatching {
            runBlocking {
                when (provider) {
                    AgentProvider.CLAUDE -> ClaudeOAuthClient(transport).refresh(stored)
                    AgentProvider.CODEX -> CodexOAuthClient(transport).refresh(stored)
                    AgentProvider.GROK -> GrokOAuthClient(transport).refresh(stored)
                    else -> error("not a refreshing provider")
                }
            }
        }.exceptionOrNull() ?: error("$provider $status did not fail")
        return error.toFetchErrorReason()
    }

    @Test fun providerTroubleIsNotASignInProblem() {
        for (provider in listOf(AgentProvider.CLAUDE, AgentProvider.CODEX, AgentProvider.GROK)) {
            assertEquals("$provider 429", FetchErrorReason.RATE_LIMIT, refreshReason(provider, 429, "slow down"))
            assertEquals("$provider 503", FetchErrorReason.HTTP_5XX, refreshReason(provider, 503, "<html>"))
            assertEquals("$provider 500", FetchErrorReason.HTTP_5XX, refreshReason(provider, 500, ""))
            assertEquals("$provider 2xx not JSON", FetchErrorReason.PARSE, refreshReason(provider, 200, "<html>"))
            assertEquals("$provider no token", FetchErrorReason.PARSE, refreshReason(provider, 200, "{}"))
            assertEquals("$provider 404", FetchErrorReason.HTTP_4XX, refreshReason(provider, 404, ""))
        }
    }

    @Test fun rejectedCredentialsStayAuth() {
        for (provider in listOf(AgentProvider.CLAUDE, AgentProvider.CODEX, AgentProvider.GROK)) {
            assertEquals("$provider 401", FetchErrorReason.AUTH, refreshReason(provider, 401, ""))
            assertEquals(
                "$provider 400 invalid_grant",
                FetchErrorReason.AUTH,
                refreshReason(provider, 400, """{"error":"invalid_grant"}"""),
            )
            assertEquals("$provider 400", FetchErrorReason.AUTH, refreshReason(provider, 400, """{"error":"bad"}"""))
            // An OAuth rejection named in the body is a rejection whatever the status, as on iOS.
            assertEquals(
                "$provider 403 invalid_client",
                FetchErrorReason.AUTH,
                refreshReason(provider, 403, """{"error":"invalid_client"}"""),
            )
        }
    }

    // endregion
}
