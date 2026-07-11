package com.ScienceFiction.TokenWatchAndroid.network.core

import com.ScienceFiction.TokenWatchAndroid.auth.OAuthTokens
import com.ScienceFiction.TokenWatchAndroid.domain.UsageWindow

fun interface ProviderUsageClient {
    suspend fun fetch(tokens: OAuthTokens): List<UsageWindow>
}
