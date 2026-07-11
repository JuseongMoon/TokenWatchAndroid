package com.ScienceFiction.TokenWatchAndroid.network.orchestration

import com.ScienceFiction.TokenWatchAndroid.auth.OAuthTokens
import com.ScienceFiction.TokenWatchAndroid.domain.AgentProvider
import com.ScienceFiction.TokenWatchAndroid.domain.UsageWindow
import com.ScienceFiction.TokenWatchAndroid.network.core.HttpTransport
import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkTransport
import com.ScienceFiction.TokenWatchAndroid.network.core.ProviderUsageClient
import com.ScienceFiction.TokenWatchAndroid.network.providers.apikey.DIDUsageClient
import com.ScienceFiction.TokenWatchAndroid.network.providers.apikey.DeepSeekUsageClient
import com.ScienceFiction.TokenWatchAndroid.network.providers.apikey.ElevenLabsUsageClient
import com.ScienceFiction.TokenWatchAndroid.network.providers.apikey.FalUsageClient
import com.ScienceFiction.TokenWatchAndroid.network.providers.apikey.HeyGenUsageClient
import com.ScienceFiction.TokenWatchAndroid.network.providers.apikey.LeonardoUsageClient
import com.ScienceFiction.TokenWatchAndroid.network.providers.apikey.LumaUsageClient
import com.ScienceFiction.TokenWatchAndroid.network.providers.apikey.OpenRouterUsageClient
import com.ScienceFiction.TokenWatchAndroid.network.providers.apikey.PoeUsageClient
import com.ScienceFiction.TokenWatchAndroid.network.providers.apikey.RecraftUsageClient
import com.ScienceFiction.TokenWatchAndroid.network.providers.apikey.RunwayUsageClient
import com.ScienceFiction.TokenWatchAndroid.network.providers.apikey.StabilityUsageClient
import com.ScienceFiction.TokenWatchAndroid.network.providers.session.GrokUsageClient
import com.ScienceFiction.TokenWatchAndroid.network.providers.session.WindsurfUsageClient
import com.ScienceFiction.TokenWatchAndroid.network.providers.subscription.ClaudeUsageClient
import com.ScienceFiction.TokenWatchAndroid.network.providers.subscription.CodexUsageClient
import com.ScienceFiction.TokenWatchAndroid.network.providers.subscription.CopilotUsageClient
import com.ScienceFiction.TokenWatchAndroid.network.providers.subscription.CursorUsageClient

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
                AgentProvider.ELEVENLABS to ElevenLabsUsageClient(transport),
                AgentProvider.COPILOT to CopilotUsageClient(transport),
                AgentProvider.CURSOR to CursorUsageClient(transport),
                AgentProvider.OPENROUTER to OpenRouterUsageClient(transport),
                AgentProvider.DEEPSEEK to DeepSeekUsageClient(transport),
                AgentProvider.POE to PoeUsageClient(transport),
                AgentProvider.FAL to FalUsageClient(transport),
                AgentProvider.STABILITY to StabilityUsageClient(transport),
                AgentProvider.RECRAFT to RecraftUsageClient(transport),
                AgentProvider.LUMA to LumaUsageClient(transport),
                AgentProvider.RUNWAY to RunwayUsageClient(transport),
                AgentProvider.DID to DIDUsageClient(transport),
                AgentProvider.HEYGEN to HeyGenUsageClient(transport),
                AgentProvider.LEONARDO to LeonardoUsageClient(transport),
                AgentProvider.GROK to GrokUsageClient(transport),
                AgentProvider.WINDSURF to WindsurfUsageClient(transport),
            ),
        )
    }
}
