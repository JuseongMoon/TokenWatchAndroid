package com.ScienceFiction.TokenWatchAndroid.domain

/** How a provider obtains credentials from the user. The add screen routes on this value. */
enum class AuthKind {
    /** In-app WebView login whose OAuth callback redirect is intercepted (Codex). */
    OAUTH_CODE,

    /**
     * In-app sign-in window (Auth Tab, falling back to a Custom Tab) that returns the code to a
     * one-shot loopback listener (Claude, Grok). Unlike a WebView it runs on the browser engine, so
     * popup-based social sign-in such as Google works. Claude also offers a paste-the-code fallback.
     */
    OAUTH_BROWSER,

    /**
     * Approval page opened in an in-app tab while the app polls for the token. Copilot shows a user
     * code to enter; Cursor only needs the page approved.
     */
    OAUTH_DEVICE_FLOW,

    /** The user pastes an API key they issued themselves. */
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
    FOREGROUND,
    BLUE,
    LIME,
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
 *
 * Grok and Cursor came back in 2026-09 with different mechanisms, matching iOS 8cff71c/d856eac:
 * Grok uses the official Grok CLI OAuth and a JSON usage endpoint that answers 401 for a bad token,
 * and Cursor uses the Cursor CLI's page-approval-plus-polling login with the dashboard usage summary
 * (not an official API). Kimi Code (iOS db7fc6b) is a user-issued API key.
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
        "claude", "Claude", "[C]", TerminalColorKey.YELLOW, AuthKind.OAUTH_BROWSER,
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

    /** status.x.ai blocks automated reads behind Cloudflare, so only the page link is offered. */
    GROK(
        "grok", "Grok", "[gr]", TerminalColorKey.FOREGROUND, AuthKind.OAUTH_BROWSER,
        UsageCategory.SUBSCRIPTION, null, "https://status.x.ai", null,
    ),
    CURSOR(
        "cursor", "Cursor", "[cr]", TerminalColorKey.BLUE, AuthKind.OAUTH_DEVICE_FLOW,
        UsageCategory.SUBSCRIPTION, null, "https://status.cursor.com",
        atlassian("status.cursor.com"),
    ),

    /** Moonshot's origin status host: status.moonshot.cn does not resolve everywhere. */
    KIMI(
        "kimi", "Kimi", "[km]", TerminalColorKey.LIME, AuthKind.API_KEY,
        UsageCategory.SUBSCRIPTION, "https://www.kimi.com/code/console",
        "https://moonshot.statuspage.io", atlassian("moonshot.statuspage.io"),
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
