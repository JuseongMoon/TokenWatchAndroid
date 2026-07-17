package com.ScienceFiction.TokenWatchAndroid.network.providers.subscription

import com.ScienceFiction.TokenWatchAndroid.auth.OAuthTokens
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CodexAccountClientTest {
    @Test
    fun fetchesPreferredAccountPlanAndSendsAccountHeader() {
        val transport = RecordingTransport(
            jsonResponse(
                """
                {
                  "accounts": {
                    "acct_other": {"account": {"plan_type": "free"}},
                    "acct_preferred": {"account": {"plan_type": "chatgpt_plus"}}
                  },
                  "account_ordering": ["acct_other", "acct_preferred"]
                }
                """.trimIndent(),
            ),
        )
        val client = CodexAccountClient(transport, "https://example.com/accounts/check".toHttpUrl())

        val plan = runSuspend {
            client.fetchPlan(OAuthTokens("secret", accountId = "acct_preferred"))
        }

        assertEquals("Chatgpt Plus", plan)
        assertEquals("Bearer secret", transport.lastRequest.header("Authorization"))
        assertEquals("acct_preferred", transport.lastRequest.header("ChatGPT-Account-Id"))
    }

    @Test
    fun orderingIsUsedWhenPreferredAccountIsMissing() {
        val client = CodexAccountClient(
            RecordingTransport(),
            "https://example.com/accounts/check".toHttpUrl(),
        )
        val root = mapOf(
            "accounts" to mapOf(
                "first" to mapOf("account" to mapOf("plan_type" to "pro")),
                "second" to mapOf("account" to mapOf("plan_type" to "free")),
            ),
            "account_ordering" to listOf("second", "first"),
        )

        assertEquals("free", client.rawPlanType(root, preferredAccountId = null))
        assertNull(client.rawPlanType(emptyMap(), preferredAccountId = null))
    }
}
