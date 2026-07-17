package com.ScienceFiction.TokenWatchAndroid.network.providers.subscription

import com.ScienceFiction.TokenWatchAndroid.auth.OAuthTokens
import com.ScienceFiction.TokenWatchAndroid.network.core.HttpTransport
import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkTransport
import java.util.Locale
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/** Reads the current ChatGPT plan because the OAuth ID-token plan claim can remain stale. */
class CodexAccountClient(
    private val transport: NetworkTransport = HttpTransport(),
    private val endpoint: HttpUrl = DefaultEndpoint.toHttpUrl(),
) {
    suspend fun fetchPlan(tokens: OAuthTokens): String? {
        val requestBuilder = Request.Builder()
            .url(endpoint)
            .get()
            .header("Authorization", "Bearer ${tokens.accessToken}")
            .header("Accept", "application/json")
            .header("User-Agent", CodexUsageClient.UserAgent)
        tokens.accountId?.takeIf(String::isNotBlank)?.let { accountId ->
            requestBuilder.header("ChatGPT-Account-Id", accountId)
        }
        val response = transport.executeUsage(
            requestBuilder.build(),
            forbiddenIsUnauthorized = true,
        )
        return rawPlanType(
            root = SubscriptionProviderJson.root(response.body),
            preferredAccountId = tokens.accountId,
        )?.prettyPlan()
    }

    internal fun rawPlanType(
        root: Map<String, Any?>,
        preferredAccountId: String?,
    ): String? {
        val accounts = root["accounts"].asObject() ?: return null
        val orderedIds = root["account_ordering"].asArray()
            .orEmpty()
            .mapNotNull { it as? String }
        val candidates = buildList {
            preferredAccountId?.trim()?.takeIf(String::isNotEmpty)?.let(::add)
            addAll(orderedIds)
            addAll(accounts.keys)
        }.distinct()

        for (id in candidates) {
            planType(accounts[id])?.let { return it }
        }
        return accounts.values.firstNotNullOfOrNull(::planType)
    }

    private fun planType(entry: Any?): String? = entry.asObject()
        ?.get("account")
        .asObject()
        ?.get("plan_type")
        ?.let { it as? String }
        ?.trim()
        ?.takeIf(String::isNotEmpty)

    private fun String.prettyPlan(): String =
        split('_', '-')
            .filter(String::isNotEmpty)
            .joinToString(" ") { word ->
                word.lowercase(Locale.getDefault()).replaceFirstChar { it.titlecase(Locale.getDefault()) }
            }

    companion object {
        const val DefaultEndpoint = "https://chatgpt.com/backend-api/accounts/check/v4-2023-04-27"
    }
}
