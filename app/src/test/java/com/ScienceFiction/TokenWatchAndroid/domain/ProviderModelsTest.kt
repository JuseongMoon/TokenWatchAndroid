package com.ScienceFiction.TokenWatchAndroid.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderModelsTest {
    private data class DisplayMetadata(
        val name: String,
        val tag: String,
        val color: TerminalColorKey,
        val auth: AuthKind,
        val category: UsageCategory,
    )

    @Test
    fun providerCatalogMatchesIosBaseline() {
        val expected = mapOf(
            AgentProvider.CLAUDE to DisplayMetadata("Claude", "[C]", TerminalColorKey.YELLOW, AuthKind.OAUTH_CODE, UsageCategory.SUBSCRIPTION),
            AgentProvider.CODEX to DisplayMetadata("Codex", "[X]", TerminalColorKey.CYAN, AuthKind.OAUTH_CODE, UsageCategory.SUBSCRIPTION),
            AgentProvider.ELEVENLABS to DisplayMetadata("ElevenLabs", "[11]", TerminalColorKey.ORANGE, AuthKind.API_KEY, UsageCategory.SUBSCRIPTION),
            AgentProvider.COPILOT to DisplayMetadata("Copilot", "[cp]", TerminalColorKey.MAGENTA, AuthKind.OAUTH_DEVICE_FLOW, UsageCategory.SUBSCRIPTION),
            AgentProvider.CURSOR to DisplayMetadata("Cursor", "[cr]", TerminalColorKey.BLUE, AuthKind.SESSION_CAPTURE, UsageCategory.SUBSCRIPTION),
            AgentProvider.OPENROUTER to DisplayMetadata("OpenRouter", "[or]", TerminalColorKey.GREEN, AuthKind.API_KEY, UsageCategory.API_CREDIT),
            AgentProvider.DEEPSEEK to DisplayMetadata("DeepSeek", "[ds]", TerminalColorKey.PINK, AuthKind.API_KEY, UsageCategory.API_CREDIT),
            AgentProvider.POE to DisplayMetadata("Poe", "[P]", TerminalColorKey.TEAL, AuthKind.API_KEY, UsageCategory.SUBSCRIPTION),
            AgentProvider.FAL to DisplayMetadata("Fal", "[fl]", TerminalColorKey.MAGENTA, AuthKind.API_KEY, UsageCategory.API_CREDIT),
            AgentProvider.STABILITY to DisplayMetadata("Stability", "[st]", TerminalColorKey.GREEN, AuthKind.API_KEY, UsageCategory.API_CREDIT),
            AgentProvider.RECRAFT to DisplayMetadata("Recraft", "[rc]", TerminalColorKey.YELLOW, AuthKind.API_KEY, UsageCategory.API_CREDIT),
            AgentProvider.LUMA to DisplayMetadata("Luma", "[lm]", TerminalColorKey.CYAN, AuthKind.API_KEY, UsageCategory.API_CREDIT),
            AgentProvider.RUNWAY to DisplayMetadata("Runway", "[rw]", TerminalColorKey.ORANGE, AuthKind.API_KEY, UsageCategory.API_CREDIT),
            AgentProvider.DID to DisplayMetadata("D-ID", "[dd]", TerminalColorKey.PINK, AuthKind.API_KEY, UsageCategory.API_CREDIT),
            AgentProvider.HEYGEN to DisplayMetadata("HeyGen", "[hg]", TerminalColorKey.BLUE, AuthKind.API_KEY, UsageCategory.API_CREDIT),
            AgentProvider.LEONARDO to DisplayMetadata("Leonardo", "[le]", TerminalColorKey.YELLOW, AuthKind.API_KEY, UsageCategory.API_CREDIT),
            AgentProvider.GROK to DisplayMetadata("Grok", "[gr]", TerminalColorKey.FOREGROUND, AuthKind.SESSION_CAPTURE, UsageCategory.SUBSCRIPTION),
            AgentProvider.WINDSURF to DisplayMetadata("Windsurf", "[ws]", TerminalColorKey.TEAL, AuthKind.SESSION_CAPTURE, UsageCategory.SUBSCRIPTION),
        )

        assertEquals(18, AgentProvider.entries.size)
        assertEquals(expected.keys, AgentProvider.entries.toSet())
        expected.forEach { (provider, metadata) ->
            assertEquals(provider.name.lowercase(), provider.wireId)
            assertEquals(provider.wireId, provider.id)
            assertEquals(metadata.name, provider.displayName)
            assertEquals(metadata.tag, provider.terminalTag)
            assertEquals(metadata.color, provider.terminalColorKey)
            assertEquals(metadata.auth, provider.authKind)
            assertEquals(metadata.category, provider.usageCategory)
            assertEquals(provider, AgentProvider.fromWireId(provider.wireId))
        }
        assertNull(AgentProvider.fromWireId("unknown"))
    }

    @Test
    fun apiKeyUrlsExistOnlyForApiKeyProviders() {
        AgentProvider.entries.forEach { provider ->
            if (provider.authKind == AuthKind.API_KEY) {
                assertFalse("${provider.name} is missing its key URL", provider.apiKeyUrl.isNullOrBlank())
            } else {
                assertNull("${provider.name} must not expose a key URL", provider.apiKeyUrl)
            }
        }

        assertEquals("https://elevenlabs.io/app/settings/api-keys", AgentProvider.ELEVENLABS.apiKeyUrl)
        assertEquals("https://app.leonardo.ai/api-access", AgentProvider.LEONARDO.apiKeyUrl)
    }

    @Test
    fun statusSourcesMatchSupportedStatusPlatforms() {
        val noMachineReadableSource = setOf(
            AgentProvider.OPENROUTER,
            AgentProvider.GROK,
            AgentProvider.LEONARDO,
        )
        assertEquals(noMachineReadableSource, AgentProvider.entries.filter { it.statusSource == null }.toSet())

        assertEquals(
            ServiceStatusSource(StatusPlatform.ATLASSIAN, "https://deepseek.statuspage.io/api/v2/components.json"),
            AgentProvider.DEEPSEEK.statusSource,
        )
        assertEquals(
            ServiceStatusSource(StatusPlatform.INSTATUS, "https://recraft.instatus.com/v2/components.json"),
            AgentProvider.RECRAFT.statusSource,
        )
        assertEquals(
            ServiceStatusSource(StatusPlatform.BETTERSTACK, "https://status.lumalabs.ai/index.json"),
            AgentProvider.LUMA.statusSource,
        )

        assertEquals("https://status.openrouter.ai", AgentProvider.OPENROUTER.statusPageUrl)
        assertEquals("https://status.x.ai", AgentProvider.GROK.statusPageUrl)
        assertNull(AgentProvider.LEONARDO.statusPageUrl)
        assertTrue(AgentProvider.entries.filterNot { it == AgentProvider.LEONARDO }.all { it.statusPageUrl != null })

        AgentProvider.entries.mapNotNull { it.statusSource }.forEach { source ->
            when (source.platform) {
                StatusPlatform.ATLASSIAN -> assertTrue(source.jsonUrl.endsWith("/api/v2/components.json"))
                StatusPlatform.INSTATUS -> assertTrue(source.jsonUrl.endsWith("/v2/components.json"))
                StatusPlatform.BETTERSTACK -> assertTrue(source.jsonUrl.endsWith("/index.json"))
            }
        }
    }
}
