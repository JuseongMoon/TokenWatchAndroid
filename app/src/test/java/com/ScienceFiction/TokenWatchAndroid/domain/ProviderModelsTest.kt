package com.ScienceFiction.TokenWatchAndroid.domain

import com.ScienceFiction.TokenWatchAndroid.analytics.analyticsShortTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderModelsTest {
    /** The 2026-08 audit's seven, plus Grok and Cursor re-introduced and Kimi added in 2026-09. */
    @Test fun providerCatalogMatchesIos() {
        assertEquals(
            listOf(
                "claude", "codex", "copilot", "grok", "cursor", "kimi",
                "openrouter", "deepseek", "poe", "elevenlabs",
            ),
            AgentProvider.entries.map(AgentProvider::wireId),
        )
        assertEquals(10, AgentProvider.entries.size)
        assertNull(AgentProvider.fromWireId("windsurf"))
        assertNull(AgentProvider.fromWireId("unknown"))
    }

    @Test fun authKindsMatchIos() {
        val expected = mapOf(
            AgentProvider.CLAUDE to AuthKind.OAUTH_BROWSER,
            AgentProvider.GROK to AuthKind.OAUTH_BROWSER,
            AgentProvider.CODEX to AuthKind.OAUTH_CODE,
            AgentProvider.COPILOT to AuthKind.OAUTH_DEVICE_FLOW,
            AgentProvider.CURSOR to AuthKind.OAUTH_DEVICE_FLOW,
        )
        AgentProvider.entries.forEach { provider ->
            assertEquals(provider.wireId, expected[provider] ?: AuthKind.API_KEY, provider.authKind)
        }
        assertEquals(
            setOf(AgentProvider.OPENROUTER, AgentProvider.DEEPSEEK),
            AgentProvider.entries.filter { it.usageCategory == UsageCategory.API_CREDIT }.toSet(),
        )
    }

    @Test fun apiKeyUrlsExistOnlyForApiKeyProviders() {
        AgentProvider.entries.forEach { provider ->
            if (provider.authKind == AuthKind.API_KEY) assertFalse(provider.apiKeyUrl.isNullOrBlank())
            else assertNull(provider.apiKeyUrl)
        }
    }

    /** OpenRouter has no machine-readable status, and status.x.ai blocks automated reads. */
    @Test fun onlyOpenRouterAndGrokLackMachineReadableStatus() {
        assertEquals(
            setOf(AgentProvider.OPENROUTER, AgentProvider.GROK),
            AgentProvider.entries.filter { it.statusSource == null }.toSet(),
        )
        assertTrue(AgentProvider.entries.all { it.statusPageUrl.isNotBlank() })
        AgentProvider.entries.mapNotNull { it.statusSource }.forEach {
            assertEquals(StatusPlatform.ATLASSIAN, it.platform)
            assertTrue(it.jsonUrl.endsWith("/api/v2/components.json"))
        }
    }

    @Test fun terminalTagsAndColorsAreDistinct() {
        assertEquals(AgentProvider.entries.size, AgentProvider.entries.map { it.terminalTag }.toSet().size)
        assertEquals(AgentProvider.entries.size, AgentProvider.entries.map { it.terminalColorKey }.toSet().size)
    }

    /** The `providers` user property joins short tags; they must not collide or exceed GA4's 36 characters. */
    @Test fun analyticsShortTagsAreUniqueAndFitTheUserPropertyLimit() {
        val tags = AgentProvider.entries.map { it.analyticsShortTag }
        assertEquals(tags.size, tags.toSet().size)
        assertTrue(tags.sorted().joinToString(",").length <= 36)
    }
}
