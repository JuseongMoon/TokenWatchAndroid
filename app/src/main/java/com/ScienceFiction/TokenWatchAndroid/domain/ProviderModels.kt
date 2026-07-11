package com.ScienceFiction.TokenWatchAndroid.domain

/** How a provider obtains credentials from the user. */
enum class AuthKind {
    OAUTH_CODE,
    OAUTH_DEVICE_FLOW,
    SESSION_CAPTURE,
    API_KEY,
}

/** Whether usage represents a subscription quota or prepaid API credit. */
enum class UsageCategory {
    SUBSCRIPTION,
    API_CREDIT,
}

/** UI-independent key for the terminal palette color assigned to a provider. */
enum class TerminalColorKey {
    YELLOW,
    CYAN,
    ORANGE,
    MAGENTA,
    BLUE,
    GREEN,
    PINK,
    TEAL,
    FOREGROUND,
}

enum class StatusPlatform {
    ATLASSIAN,
    INSTATUS,
    BETTERSTACK,
}

data class ServiceStatusSource(
    val platform: StatusPlatform,
    val jsonUrl: String,
)

private fun atlassian(host: String) = ServiceStatusSource(
    platform = StatusPlatform.ATLASSIAN,
    jsonUrl = "https://$host/api/v2/status.json",
)

private fun instatus(host: String) = ServiceStatusSource(
    platform = StatusPlatform.INSTATUS,
    jsonUrl = "https://$host/summary.json",
)

/**
 * Provider catalog copied from the clean iOS baseline, commit 6df2689.
 *
 * URL values remain strings so the domain layer does not depend on Android's Uri type.
 */
enum class AgentProvider(
    val wireId: String,
    val displayName: String,
    val terminalTag: String,
    val terminalColorKey: TerminalColorKey,
    val authKind: AuthKind,
    val usageCategory: UsageCategory,
    val apiKeyUrl: String?,
    val statusPageUrl: String?,
    val statusSource: ServiceStatusSource?,
) {
    CLAUDE(
        wireId = "claude",
        displayName = "Claude",
        terminalTag = "[C]",
        terminalColorKey = TerminalColorKey.YELLOW,
        authKind = AuthKind.OAUTH_CODE,
        usageCategory = UsageCategory.SUBSCRIPTION,
        apiKeyUrl = null,
        statusPageUrl = "https://status.claude.com",
        statusSource = atlassian("status.claude.com"),
    ),
    CODEX(
        wireId = "codex",
        displayName = "Codex",
        terminalTag = "[X]",
        terminalColorKey = TerminalColorKey.CYAN,
        authKind = AuthKind.OAUTH_CODE,
        usageCategory = UsageCategory.SUBSCRIPTION,
        apiKeyUrl = null,
        statusPageUrl = "https://status.openai.com",
        statusSource = atlassian("status.openai.com"),
    ),
    ELEVENLABS(
        wireId = "elevenlabs",
        displayName = "ElevenLabs",
        terminalTag = "[11]",
        terminalColorKey = TerminalColorKey.ORANGE,
        authKind = AuthKind.API_KEY,
        usageCategory = UsageCategory.SUBSCRIPTION,
        apiKeyUrl = "https://elevenlabs.io/app/settings/api-keys",
        statusPageUrl = "https://status.elevenlabs.io",
        statusSource = atlassian("status.elevenlabs.io"),
    ),
    COPILOT(
        wireId = "copilot",
        displayName = "Copilot",
        terminalTag = "[cp]",
        terminalColorKey = TerminalColorKey.MAGENTA,
        authKind = AuthKind.OAUTH_DEVICE_FLOW,
        usageCategory = UsageCategory.SUBSCRIPTION,
        apiKeyUrl = null,
        statusPageUrl = "https://www.githubstatus.com",
        statusSource = atlassian("www.githubstatus.com"),
    ),
    CURSOR(
        wireId = "cursor",
        displayName = "Cursor",
        terminalTag = "[cr]",
        terminalColorKey = TerminalColorKey.BLUE,
        authKind = AuthKind.SESSION_CAPTURE,
        usageCategory = UsageCategory.SUBSCRIPTION,
        apiKeyUrl = null,
        statusPageUrl = "https://status.cursor.com",
        statusSource = atlassian("status.cursor.com"),
    ),
    OPENROUTER(
        wireId = "openrouter",
        displayName = "OpenRouter",
        terminalTag = "[or]",
        terminalColorKey = TerminalColorKey.GREEN,
        authKind = AuthKind.API_KEY,
        usageCategory = UsageCategory.API_CREDIT,
        apiKeyUrl = "https://openrouter.ai/settings/keys",
        statusPageUrl = "https://status.openrouter.ai",
        statusSource = null,
    ),
    DEEPSEEK(
        wireId = "deepseek",
        displayName = "DeepSeek",
        terminalTag = "[ds]",
        terminalColorKey = TerminalColorKey.PINK,
        authKind = AuthKind.API_KEY,
        usageCategory = UsageCategory.API_CREDIT,
        apiKeyUrl = "https://platform.deepseek.com/api_keys",
        statusPageUrl = "https://status.deepseek.com",
        statusSource = atlassian("deepseek.statuspage.io"),
    ),
    POE(
        wireId = "poe",
        displayName = "Poe",
        terminalTag = "[P]",
        terminalColorKey = TerminalColorKey.TEAL,
        authKind = AuthKind.API_KEY,
        usageCategory = UsageCategory.SUBSCRIPTION,
        apiKeyUrl = "https://poe.com/api_key",
        statusPageUrl = "https://status.poe.com",
        statusSource = atlassian("status.poe.com"),
    ),
    FAL(
        wireId = "fal",
        displayName = "Fal",
        terminalTag = "[fl]",
        terminalColorKey = TerminalColorKey.MAGENTA,
        authKind = AuthKind.API_KEY,
        usageCategory = UsageCategory.API_CREDIT,
        apiKeyUrl = "https://fal.ai/dashboard/keys",
        statusPageUrl = "https://status.fal.ai",
        statusSource = instatus("status.fal.ai"),
    ),
    STABILITY(
        wireId = "stability",
        displayName = "Stability",
        terminalTag = "[st]",
        terminalColorKey = TerminalColorKey.GREEN,
        authKind = AuthKind.API_KEY,
        usageCategory = UsageCategory.API_CREDIT,
        apiKeyUrl = "https://platform.stability.ai/account/keys",
        statusPageUrl = "https://status.stability.ai",
        statusSource = atlassian("status.stability.ai"),
    ),
    RECRAFT(
        wireId = "recraft",
        displayName = "Recraft",
        terminalTag = "[rc]",
        terminalColorKey = TerminalColorKey.YELLOW,
        authKind = AuthKind.API_KEY,
        usageCategory = UsageCategory.API_CREDIT,
        apiKeyUrl = "https://www.recraft.ai/profile/api",
        statusPageUrl = "https://status.recraft.ai",
        statusSource = instatus("recraft.instatus.com"),
    ),
    LUMA(
        wireId = "luma",
        displayName = "Luma",
        terminalTag = "[lm]",
        terminalColorKey = TerminalColorKey.CYAN,
        authKind = AuthKind.API_KEY,
        usageCategory = UsageCategory.API_CREDIT,
        apiKeyUrl = "https://lumalabs.ai/dream-machine/api/keys",
        statusPageUrl = "https://status.lumalabs.ai",
        statusSource = ServiceStatusSource(
            platform = StatusPlatform.BETTERSTACK,
            jsonUrl = "https://status.lumalabs.ai/index.json",
        ),
    ),
    RUNWAY(
        wireId = "runway",
        displayName = "Runway",
        terminalTag = "[rw]",
        terminalColorKey = TerminalColorKey.ORANGE,
        authKind = AuthKind.API_KEY,
        usageCategory = UsageCategory.API_CREDIT,
        apiKeyUrl = "https://dev.runwayml.com/",
        statusPageUrl = "https://status.runwayml.com",
        statusSource = atlassian("status.runwayml.com"),
    ),
    DID(
        wireId = "did",
        displayName = "D-ID",
        terminalTag = "[dd]",
        terminalColorKey = TerminalColorKey.PINK,
        authKind = AuthKind.API_KEY,
        usageCategory = UsageCategory.API_CREDIT,
        apiKeyUrl = "https://studio.d-id.com/account-settings",
        statusPageUrl = "https://status.d-id.com",
        statusSource = atlassian("status.d-id.com"),
    ),
    HEYGEN(
        wireId = "heygen",
        displayName = "HeyGen",
        terminalTag = "[hg]",
        terminalColorKey = TerminalColorKey.BLUE,
        authKind = AuthKind.API_KEY,
        usageCategory = UsageCategory.API_CREDIT,
        apiKeyUrl = "https://app.heygen.com/settings",
        statusPageUrl = "https://status.heygen.com",
        statusSource = atlassian("status.heygen.com"),
    ),
    LEONARDO(
        wireId = "leonardo",
        displayName = "Leonardo",
        terminalTag = "[le]",
        terminalColorKey = TerminalColorKey.YELLOW,
        authKind = AuthKind.API_KEY,
        usageCategory = UsageCategory.API_CREDIT,
        apiKeyUrl = "https://app.leonardo.ai/api-access",
        statusPageUrl = null,
        statusSource = null,
    ),
    GROK(
        wireId = "grok",
        displayName = "Grok",
        terminalTag = "[gr]",
        terminalColorKey = TerminalColorKey.FOREGROUND,
        authKind = AuthKind.SESSION_CAPTURE,
        usageCategory = UsageCategory.SUBSCRIPTION,
        apiKeyUrl = null,
        statusPageUrl = "https://status.x.ai",
        statusSource = null,
    ),
    WINDSURF(
        wireId = "windsurf",
        displayName = "Windsurf",
        terminalTag = "[ws]",
        terminalColorKey = TerminalColorKey.TEAL,
        authKind = AuthKind.SESSION_CAPTURE,
        usageCategory = UsageCategory.SUBSCRIPTION,
        apiKeyUrl = null,
        statusPageUrl = "https://status.windsurf.com",
        statusSource = atlassian("status.windsurf.com"),
    ),
    ;

    val id: String
        get() = wireId

    companion object {
        private val byWireId = entries.associateBy(AgentProvider::wireId)

        fun fromWireId(wireId: String): AgentProvider? = byWireId[wireId]
    }
}
