package com.ScienceFiction.TokenWatchAndroid.network.orchestration

import com.ScienceFiction.TokenWatchAndroid.auth.OAuthTokens
import com.ScienceFiction.TokenWatchAndroid.domain.AgentProvider
import com.ScienceFiction.TokenWatchAndroid.domain.UsageWindow
import com.ScienceFiction.TokenWatchAndroid.network.core.HttpTransport
import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkTransport
import com.ScienceFiction.TokenWatchAndroid.network.core.ProviderUsageClient
import com.ScienceFiction.TokenWatchAndroid.network.providers.apikey.DeepSeekUsageClient
import com.ScienceFiction.TokenWatchAndroid.network.providers.apikey.ElevenLabsUsageClient
import com.ScienceFiction.TokenWatchAndroid.network.providers.apikey.OpenRouterUsageClient
import com.ScienceFiction.TokenWatchAndroid.network.providers.apikey.PoeUsageClient
import com.ScienceFiction.TokenWatchAndroid.network.providers.subscription.ClaudeUsageClient
import com.ScienceFiction.TokenWatchAndroid.network.providers.subscription.CodexUsageClient
import com.ScienceFiction.TokenWatchAndroid.network.providers.subscription.CopilotUsageClient

/** Exhaustive provider-to-client dispatch. Construction fails if a provider is omitted. */
class ProviderUsageRegistry(clients: Map<AgentProvider, ProviderUsageClient>) {
    private val clients = clients.toMap()

    init {
        val expected = AgentProvider.entries.toSet()
        require(this.clients.keys == expected) {
            "Provider registry mismatch; missing=${expected - this.clients.keys}"
        }
    }

    val providers: Set<AgentProvider>
        get() = clients.keys

    fun clientFor(provider: AgentProvider): ProviderUsageClient = checkNotNull(clients[provider])

    suspend fun fetchWindows(provider: AgentProvider, tokens: OAuthTokens): List<UsageWindow> =
        clientFor(provider).fetch(tokens)

    companion object {
        fun create(
            transport: NetworkTransport = HttpTransport(),
            codexAdditionalLimitLabel: () -> String = { "Additional limit" },
        ): ProviderUsageRegistry = ProviderUsageRegistry(
            mapOf(
                AgentProvider.CLAUDE to ClaudeUsageClient(transport),
                AgentProvider.CODEX to CodexUsageClient(transport, additionalLimitLabel = codexAdditionalLimitLabel),
                AgentProvider.COPILOT to CopilotUsageClient(transport),
                AgentProvider.OPENROUTER to OpenRouterUsageClient(transport),
                AgentProvider.DEEPSEEK to DeepSeekUsageClient(transport),
                AgentProvider.POE to PoeUsageClient(transport),
                AgentProvider.ELEVENLABS to ElevenLabsUsageClient(transport),
            ),
        )
    }
}
