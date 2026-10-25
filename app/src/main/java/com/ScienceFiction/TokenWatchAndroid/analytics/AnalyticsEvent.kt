package com.ScienceFiction.TokenWatchAndroid.analytics

import com.ScienceFiction.TokenWatchAndroid.account.AccountFailStage
import com.ScienceFiction.TokenWatchAndroid.domain.Announcement
import com.ScienceFiction.TokenWatchAndroid.domain.account.AccountMethod
import com.ScienceFiction.TokenWatchAndroid.domain.AgentProvider
import com.ScienceFiction.TokenWatchAndroid.domain.AuthKind
import com.ScienceFiction.TokenWatchAndroid.monster.MonsterEncounter
import com.ScienceFiction.TokenWatchAndroid.network.core.UsageException
import java.io.IOException

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

/** Buckets a fetch failure. Raw messages never leave the device, only this classification. */
fun Throwable.toFetchErrorReason(): FetchErrorReason = when (this) {
    is UsageException.Unauthorized -> FetchErrorReason.AUTH
    is UsageException.RateLimited -> FetchErrorReason.RATE_LIMIT
    is UsageException.Http -> if (statusCode in 400..499) FetchErrorReason.HTTP_4XX else FetchErrorReason.HTTP_5XX
    is UsageException.Decode -> FetchErrorReason.PARSE
    is UsageException.NoWindows -> FetchErrorReason.EMPTY
    is IOException -> FetchErrorReason.NETWORK
    else -> FetchErrorReason.OTHER
}

/**
 * The event a fetch outcome should produce, or null when nothing changed.
 *
 * Only transitions are worth reporting: a provider that keeps failing should log one error rather
 * than one per refresh, and the recovery is what closes that pair. [previouslyFailed] is the caller's
 * memory of the last outcome for the same agent.
 */
fun fetchOutcomeEvent(
    provider: AgentProvider,
    error: Throwable?,
    previouslyFailed: Boolean,
): AnalyticsEvent? = when {
    error != null && !previouslyFailed ->
        AnalyticsEvent.UsageFetchError(provider, error.toFetchErrorReason())
    error == null && previouslyFailed -> AnalyticsEvent.UsageFetchRecover(provider)
    else -> null
}

enum class ScreenName(val wireId: String, val screenClass: String) {
    MAIN("main", "MainScreen"),
    ADD_AGENT("add_agent", "AddAgentScreen"),
    AGENT_DETAIL("agent_detail", "DetailScreen"),
    SETTINGS("settings", "SettingsScreen"),
    WORK_HOURS("work_hours", "WorkHoursEditor"),
    ANNOUNCEMENTS("announcements", "AnnouncementsScreen"),
    ANNOUNCEMENT_DETAIL("announcement_detail", "AnnouncementDetailScreen"),
    // App account (TokenWatch's own account): logged once as each of the three screens appears, the
    // same unit iOS uses.
    ACCOUNT_LOGIN("account_login", "AccountLoginScreen"),
    PROFILE_SETUP("profile_setup", "ProfileSetupScreen"),
    ACCOUNT("account", "AccountScreen"),
    // Monster hunt and guild (V2).
    LEADERBOARD("leaderboard", "LeaderboardScreen"),
    MONSTER_CONSENT("monster_consent", "MonsterConsentScreen"),
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

/** Where a store review was requested from. */
enum class StoreReviewSource(val wireId: String) {
    PROMPT("prompt"),
    SETTINGS("settings"),
}

/** Which app account profile field changed. The value itself is never sent. */
enum class ProfileField(val wireId: String) {
    NICKNAME("nickname"),
    AVATAR("avatar"),
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
    class LoginStart(provider: AgentProvider) : AnalyticsEvent(
        "login_start",
        mapOf("provider" to provider.wireId, "auth_kind" to provider.analyticsAuthKind),
    )

    class LoginSuccess(provider: AgentProvider, agentsTotal: Int) : AnalyticsEvent(
        "login_success",
        mapOf(
            "provider" to provider.wireId,
            "auth_kind" to provider.analyticsAuthKind,
            "agents_total" to agentsTotal.toString(),
        ),
    )

    /**
     * The fields are kept because [com.ScienceFiction.TokenWatchAndroid.analytics.AnalyticsService]
     * forwards the same failure to the diagnostic report, which needs them unflattened.
     */
    class LoginFail(
        val provider: AgentProvider,
        val stage: LoginStage,
        val code: String,
    ) : AnalyticsEvent(
        "login_fail",
        mapOf(
            "provider" to provider.wireId,
            "auth_kind" to provider.analyticsAuthKind,
            "stage" to stage.wireId,
            // Bucketed at the call site; normalized again here as a second line of defence.
            "code" to LoginFailureCode.sanitized(code),
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

    /**
     * A review was *requested*: the in-app review flow was launched, or the settings row opened the
     * store listing. Play decides whether its own sheet actually appears, so this counts requests.
     */
    class StoreReview(source: StoreReviewSource) : AnalyticsEvent(
        "store_review",
        mapOf("source" to source.wireId),
        isProviderScoped = false,
    )

    // App account: method, stage and field enums only. No uid, nickname or email ever goes out, and
    // setUserId is not used. App account failures are deliberately not a [LoginFail] subtype — that
    // event is provider-only and is forwarded to the diagnostic report.

    class AccountLogin(method: AccountMethod, isNew: Boolean) : AnalyticsEvent(
        "account_login",
        // Strings, like every Android parameter (iOS sends is_new as an integer).
        mapOf("method" to method.wireId, "is_new" to if (isNew) "1" else "0"),
        isProviderScoped = false,
    )

    class AccountLoginFail(method: AccountMethod, stage: AccountFailStage) : AnalyticsEvent(
        "account_login_fail",
        mapOf("method" to method.wireId, "stage" to stage.wireId),
        isProviderScoped = false,
    )

    class AccountLink(method: AccountMethod) : AnalyticsEvent(
        "account_link",
        mapOf("method" to method.wireId),
        isProviderScoped = false,
    )

    data object AccountLogout : AnalyticsEvent("account_logout", emptyMap(), isProviderScoped = false)

    data object AccountDelete : AnalyticsEvent(
        "account_delete",
        mapOf("surface" to "app"),
        isProviderScoped = false,
    )

    class ProfileUpdate(field: ProfileField) : AnalyticsEvent(
        "profile_update",
        mapOf("field" to field.wireId),
        isProviderScoped = false,
    )

    // Monster hunt (V2): catalog ids (enumerations the developer defines), the spawn source, HP and
    // score only. No encounter key, window name, provider, nickname or uid. Provider-scoped, so demo
    // mode drops them entirely.

    class MonsterSpawn(monsterId: String, source: MonsterEncounter.Source) : AnalyticsEvent(
        "monster_spawn",
        mapOf("monster_id" to monsterId.take(40), "source" to source.wireId),
    )

    class MonsterKill(monsterId: String, hp: Int, score: Int, source: MonsterEncounter.Source) : AnalyticsEvent(
        "monster_kill",
        // Strings, like every Android parameter (iOS sends hp and score as integers).
        mapOf(
            "monster_id" to monsterId.take(40),
            "hp" to hp.toString(),
            "score" to score.toString(),
            "source" to source.wireId,
        ),
    )
}

/** The `auth_kind` parameter value, matching iOS `authKindTag`. */
val AgentProvider.analyticsAuthKind: String
    get() = when (authKind) {
        AuthKind.OAUTH_CODE -> "oauth"
        AuthKind.OAUTH_BROWSER -> "oauth_browser"
        AuthKind.OAUTH_DEVICE_FLOW -> "device"
        AuthKind.API_KEY -> "api_key"
    }

/**
 * Short tag for the `providers` user property. GA4 caps property values at 36 characters, and the
 * full wire IDs of every provider would not fit; all ten tags joined come to 26.
 */
val AgentProvider.analyticsShortTag: String
    get() = when (this) {
        AgentProvider.CLAUDE -> "c"
        AgentProvider.CODEX -> "x"
        AgentProvider.COPILOT -> "cp"
        AgentProvider.GROK -> "gr"
        AgentProvider.CURSOR -> "cr"
        AgentProvider.KIMI -> "km"
        AgentProvider.OPENROUTER -> "or"
        AgentProvider.DEEPSEEK -> "ds"
        AgentProvider.POE -> "p"
        AgentProvider.ELEVENLABS -> "11"
    }
