package com.ScienceFiction.TokenWatchAndroid.network.core

import com.ScienceFiction.TokenWatchAndroid.auth.OAuthTokens
import com.ScienceFiction.TokenWatchAndroid.domain.UsageWindow

fun interface ProviderUsageClient {
    suspend fun fetch(tokens: OAuthTokens): List<UsageWindow>
}

/** Usage windows plus the plan label some providers include in the same response. */
data class ProviderUsage(
    val windows: List<UsageWindow>,
    val plan: String? = null,
)

/**
 * A client whose usage response also carries the account's plan (Grok, Cursor). The gateway stores
 * that plan on every fetch, so no extra request is needed to keep the label current.
 */
interface PlanReportingUsageClient : ProviderUsageClient {
    suspend fun fetchUsage(tokens: OAuthTokens): ProviderUsage

    override suspend fun fetch(tokens: OAuthTokens): List<UsageWindow> = fetchUsage(tokens).windows
}
