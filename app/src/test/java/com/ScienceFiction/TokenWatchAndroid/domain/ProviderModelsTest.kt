package com.ScienceFiction.TokenWatchAndroid.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderModelsTest {
    @Test fun providerCatalogMatchesIosAudit() {
        assertEquals(
            listOf("claude", "codex", "copilot", "openrouter", "deepseek", "poe", "elevenlabs"),
            AgentProvider.entries.map(AgentProvider::wireId),
        )
        assertEquals(7, AgentProvider.entries.size)
        assertNull(AgentProvider.fromWireId("cursor"))
        assertNull(AgentProvider.fromWireId("unknown"))
    }

    @Test fun apiKeyUrlsExistOnlyForApiKeyProviders() {
        AgentProvider.entries.forEach { provider ->
            if (provider.authKind == AuthKind.API_KEY) assertFalse(provider.apiKeyUrl.isNullOrBlank())
            else assertNull(provider.apiKeyUrl)
        }
    }

    @Test fun onlyOpenRouterLacksMachineReadableStatus() {
        assertEquals(setOf(AgentProvider.OPENROUTER), AgentProvider.entries.filter { it.statusSource == null }.toSet())
        assertTrue(AgentProvider.entries.all { it.statusPageUrl.isNotBlank() })
        AgentProvider.entries.mapNotNull { it.statusSource }.forEach {
            assertEquals(StatusPlatform.ATLASSIAN, it.platform)
            assertTrue(it.jsonUrl.endsWith("/api/v2/components.json"))
        }
    }
}
