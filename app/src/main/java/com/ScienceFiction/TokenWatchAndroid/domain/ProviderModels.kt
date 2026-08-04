package com.ScienceFiction.TokenWatchAndroid.domain

/** How a provider obtains credentials from the user. */
enum class AuthKind {
    OAUTH_CODE,
    OAUTH_DEVICE_FLOW,
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
    GREEN,
    PINK,
    TEAL,
}

enum class StatusPlatform { ATLASSIAN }

data class ServiceStatusSource(
    val platform: StatusPlatform,
    val jsonUrl: String,
)

private fun atlassian(host: String) = ServiceStatusSource(
    platform = StatusPlatform.ATLASSIAN,
    jsonUrl = "https://$host/api/v2/components.json",
)

/**
 * Providers backed by an official documented API or a verified endpoint.
 *
 * Session-capture providers and ambiguous developer-credit integrations were removed in the
 * iOS 31a2e2c audit. [fromWireId] deliberately returns null for their persisted wire IDs so an
 * older installation keeps its supported accounts instead of losing the whole decoded list.
 */
enum class AgentProvider(
    val wireId: String,
    val displayName: String,
    val terminalTag: String,
    val terminalColorKey: TerminalColorKey,
    val authKind: AuthKind,
    val usageCategory: UsageCategory,
    val apiKeyUrl: String?,
    val statusPageUrl: String,
    val statusSource: ServiceStatusSource?,
) {
    CLAUDE(
        "claude", "Claude", "[C]", TerminalColorKey.YELLOW, AuthKind.OAUTH_CODE,
        UsageCategory.SUBSCRIPTION, null, "https://status.claude.com",
        atlassian("status.claude.com"),
    ),
    CODEX(
        "codex", "Codex", "[X]", TerminalColorKey.CYAN, AuthKind.OAUTH_CODE,
        UsageCategory.SUBSCRIPTION, null, "https://status.openai.com",
        atlassian("status.openai.com"),
    ),
    COPILOT(
        "copilot", "Copilot", "[cp]", TerminalColorKey.MAGENTA, AuthKind.OAUTH_DEVICE_FLOW,
        UsageCategory.SUBSCRIPTION, null, "https://www.githubstatus.com",
        atlassian("www.githubstatus.com"),
    ),
    OPENROUTER(
        "openrouter", "OpenRouter", "[or]", TerminalColorKey.GREEN, AuthKind.API_KEY,
        UsageCategory.API_CREDIT, "https://openrouter.ai/settings/keys",
        "https://status.openrouter.ai", null,
    ),
    DEEPSEEK(
        "deepseek", "DeepSeek", "[ds]", TerminalColorKey.PINK, AuthKind.API_KEY,
        UsageCategory.API_CREDIT, "https://platform.deepseek.com/api_keys",
        "https://status.deepseek.com", atlassian("deepseek.statuspage.io"),
    ),
    POE(
        "poe", "Poe", "[P]", TerminalColorKey.TEAL, AuthKind.API_KEY,
        UsageCategory.SUBSCRIPTION, "https://poe.com/api_key", "https://status.poe.com",
        atlassian("status.poe.com"),
    ),
    ELEVENLABS(
        "elevenlabs", "ElevenLabs", "[11]", TerminalColorKey.ORANGE, AuthKind.API_KEY,
        UsageCategory.SUBSCRIPTION, "https://elevenlabs.io/app/settings/api-keys",
        "https://status.elevenlabs.io", atlassian("status.elevenlabs.io"),
    ),
    ;

    val id: String get() = wireId

    companion object {
        private val byWireId = entries.associateBy(AgentProvider::wireId)
        fun fromWireId(wireId: String): AgentProvider? = byWireId[wireId]
    }
}
