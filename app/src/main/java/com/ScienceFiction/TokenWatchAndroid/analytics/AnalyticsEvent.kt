package com.ScienceFiction.TokenWatchAndroid.analytics

import com.ScienceFiction.TokenWatchAndroid.domain.Announcement
import com.ScienceFiction.TokenWatchAndroid.domain.AgentProvider

/** Where a login attempt stopped. Mirrors the iOS `LoginStage`. */
enum class LoginStage(val wireId: String) {
    AUTHORIZE("authorize"),
    BROWSER_WAIT("browser_wait"),
    CODE_ENTRY("code_entry"),
    STATE_MISMATCH("state_mismatch"),
    EXCHANGE("exchange"),
    DEVICE_POLL("device_poll"),
    API_KEY_ENTRY("api_key_entry"),
    KEYSTORE("keystore"),
}

/** Why a usage fetch failed, bucketed so no raw error text is ever transmitted. */
enum class FetchErrorReason(val wireId: String) {
    AUTH("auth"),
    RATE_LIMIT("rate_limit"),
    HTTP_4XX("http_4xx"),
    HTTP_5XX("http_5xx"),
    NETWORK("network"),
    PARSE("parse"),
    EMPTY("empty"),
    OTHER("other"),
}

enum class ScreenName(val wireId: String, val screenClass: String) {
    MAIN("main", "MainScreen"),
    ADD_AGENT("add_agent", "AddAgentScreen"),
    AGENT_DETAIL("agent_detail", "DetailScreen"),
    SETTINGS("settings", "SettingsScreen"),
    WORK_HOURS("work_hours", "WorkHoursEditor"),
    ANNOUNCEMENTS("announcements", "AnnouncementsScreen"),
    ANNOUNCEMENT_DETAIL("announcement_detail", "AnnouncementDetailScreen"),
}

enum class DemoSource(val wireId: String) {
    EMPTY_LIST("empty_list"),
    SETTINGS("settings"),
}

enum class RefreshSource(val wireId: String) {
    PULL("pull"),
    DETAIL("detail"),
    LIST("list"),
}

enum class AnnouncementAction(val wireId: String) {
    CLOSE("close"),
    NEVER("never"),
}

/**
 * Every analytics event the app can send, mirroring iOS `AnalyticsEvent`.
 *
 * The parameter maps are the whole contract: **no account label, account id, email, OAuth token,
 * API key, raw error text, OAuth URL, or usage figure may ever appear here.** Failures are reported
 * as bucketed reasons and announcements as truncated ids, which is what keeps the payload free of
 * anything identifying.
 */
sealed class AnalyticsEvent(
    val eventName: String,
    val parameters: Map<String, String>,
    /**
     * Provider-scoped events are suppressed entirely in demo mode: sample agents must not look
     * like real usage. Non-scoped events still fire, with any provider dimension stripped.
     */
    val isProviderScoped: Boolean = true,
) {
    class LoginStart(provider: AgentProvider) :
        AnalyticsEvent("login_start", mapOf("provider" to provider.wireId))

    class LoginSuccess(provider: AgentProvider, agentsTotal: Int) : AnalyticsEvent(
        "login_success",
        mapOf("provider" to provider.wireId, "agents_total" to agentsTotal.toString()),
    )

    class LoginFail(provider: AgentProvider, stage: LoginStage, code: String) : AnalyticsEvent(
        "login_fail",
        mapOf(
            "provider" to provider.wireId,
            "stage" to stage.wireId,
            // Already a bucketed code at the call site; truncated as a second line of defence.
            "code" to code.take(40),
        ),
    )

    class LoginAbandon(provider: AgentProvider, stage: LoginStage) : AnalyticsEvent(
        "login_abandon",
        mapOf("provider" to provider.wireId, "stage" to stage.wireId),
    )

    class ActivationComplete(provider: AgentProvider) :
        AnalyticsEvent("activation_complete", mapOf("provider" to provider.wireId))

    class DemoStart(source: DemoSource) :
        AnalyticsEvent("demo_start", mapOf("source" to source.wireId), isProviderScoped = false)

    data object DemoEnd : AnalyticsEvent("demo_end", emptyMap(), isProviderScoped = false)

    class ScreenView(screen: ScreenName, provider: AgentProvider? = null) : AnalyticsEvent(
        "screen_view",
        buildMap {
            put("screen_name", screen.wireId)
            // Set explicitly: letting the SDK infer it yields a generated class name that can blow
            // past the 100-character limit and drops the event, which bit the iOS build once.
            put("screen_class", screen.screenClass)
            provider?.let { put("provider", it.wireId) }
        },
        isProviderScoped = false,
    )

    class RefreshManual(source: RefreshSource) :
        AnalyticsEvent("refresh_manual", mapOf("source" to source.wireId), isProviderScoped = false)

    class SettingChange(setting: String, value: String) : AnalyticsEvent(
        "setting_change",
        mapOf("setting" to setting, "value" to value),
        isProviderScoped = false,
    )

    class AgentRemove(provider: AgentProvider, agentsTotal: Int) : AnalyticsEvent(
        "agent_remove",
        mapOf("provider" to provider.wireId, "agents_total" to agentsTotal.toString()),
    )

    class UsageFetchError(provider: AgentProvider, reason: FetchErrorReason) : AnalyticsEvent(
        "usage_fetch_error",
        mapOf("provider" to provider.wireId, "reason" to reason.wireId),
    )

    class UsageFetchRecover(provider: AgentProvider) :
        AnalyticsEvent("usage_fetch_recover", mapOf("provider" to provider.wireId))

    class AnnouncementShown(id: String, kind: Announcement.Kind) : AnalyticsEvent(
        "announcement_shown",
        mapOf("announcement_id" to id.take(40), "kind" to kind.wireId),
        isProviderScoped = false,
    )

    class AnnouncementActionEvent(id: String, action: AnnouncementAction) : AnalyticsEvent(
        "announcement_action",
        mapOf("announcement_id" to id.take(40), "action" to action.wireId),
        isProviderScoped = false,
    )

    class AnnouncementOpen(id: String, kind: Announcement.Kind) : AnalyticsEvent(
        "announcement_open",
        mapOf("announcement_id" to id.take(40), "kind" to kind.wireId),
        isProviderScoped = false,
    )
}
