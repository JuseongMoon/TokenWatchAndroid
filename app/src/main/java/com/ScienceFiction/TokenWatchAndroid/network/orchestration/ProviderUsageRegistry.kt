package com.ScienceFiction.TokenWatchAndroid.network.orchestration

import com.ScienceFiction.TokenWatchAndroid.auth.OAuthTokens
import com.ScienceFiction.TokenWatchAndroid.domain.AgentProvider
import com.ScienceFiction.TokenWatchAndroid.domain.UsageWindow
import com.ScienceFiction.TokenWatchAndroid.network.core.HttpTransport
import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkTransport
import com.ScienceFiction.TokenWatchAndroid.network.core.PlanReportingUsageClient
import com.ScienceFiction.TokenWatchAndroid.network.core.ProviderUsage
import com.ScienceFiction.TokenWatchAndroid.network.core.ProviderUsageClient
import com.ScienceFiction.TokenWatchAndroid.network.providers.apikey.DeepSeekUsageClient
import com.ScienceFiction.TokenWatchAndroid.network.providers.apikey.ElevenLabsUsageClient
import com.ScienceFiction.TokenWatchAndroid.network.providers.apikey.KimiUsageClient
import com.ScienceFiction.TokenWatchAndroid.network.providers.apikey.OpenRouterUsageClient
import com.ScienceFiction.TokenWatchAndroid.network.providers.apikey.PoeUsageClient
import com.ScienceFiction.TokenWatchAndroid.network.providers.subscription.ClaudeUsageClient
import com.ScienceFiction.TokenWatchAndroid.network.providers.subscription.CodexUsageClient
import com.ScienceFiction.TokenWatchAndroid.network.providers.subscription.CopilotUsageClient
import com.ScienceFiction.TokenWatchAndroid.network.providers.subscription.CursorUsageClient
import com.ScienceFiction.TokenWatchAndroid.network.providers.subscription.GrokUsageClient

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

    /** Windows plus the plan label for providers whose usage response carries one (Grok, Cursor). */
    suspend fun fetchUsage(provider: AgentProvider, tokens: OAuthTokens): ProviderUsage =
        when (val client = clientFor(provider)) {
            is PlanReportingUsageClient -> client.fetchUsage(tokens)
            else -> ProviderUsage(client.fetch(tokens))
        }

    companion object {
        /**
         * @param noRedirectTransport used where a session cookie is attached by hand (Cursor); it
         *   must not follow redirects.
         */
        fun create(
            transport: NetworkTransport = HttpTransport(),
            noRedirectTransport: NetworkTransport = HttpTransport().withoutRedirects(),
            kimi: KimiUsageClient = KimiUsageClient(transport),
            codexAdditionalLimitLabel: () -> String = { "Additional limit" },
        ): ProviderUsageRegistry = ProviderUsageRegistry(
            mapOf(
                AgentProvider.CLAUDE to ClaudeUsageClient(transport),
                AgentProvider.CODEX to CodexUsageClient(transport, additionalLimitLabel = codexAdditionalLimitLabel),
                AgentProvider.COPILOT to CopilotUsageClient(transport),
                AgentProvider.GROK to GrokUsageClient(transport),
                AgentProvider.CURSOR to CursorUsageClient(noRedirectTransport),
                AgentProvider.KIMI to kimi,
                AgentProvider.OPENROUTER to OpenRouterUsageClient(transport),
                AgentProvider.DEEPSEEK to DeepSeekUsageClient(transport),
                AgentProvider.POE to PoeUsageClient(transport),
                AgentProvider.ELEVENLABS to ElevenLabsUsageClient(transport),
            ),
        )
    }
}
