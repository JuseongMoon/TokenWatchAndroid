package com.ScienceFiction.TokenWatchAndroid.localization

import com.ScienceFiction.TokenWatchAndroid.domain.ServiceHealth
import com.ScienceFiction.TokenWatchAndroid.domain.UsageCategory
import com.ScienceFiction.TokenWatchAndroid.domain.WindowKind
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max

const val APP_LANGUAGE_STORAGE_KEY = "tokenwatch.language"

enum class AppLanguage(val wireId: String, val segmentLabel: String) {
    SYSTEM("system", "auto"),
    KOREAN("korean", "한국어"),
    ENGLISH("english", "English"),
    ;

    fun resolved(systemLanguageTag: String = Locale.getDefault().toLanguageTag()): Lang = when (this) {
        KOREAN -> Lang.KO
        ENGLISH -> Lang.EN
        SYSTEM -> if (systemLanguageTag.startsWith("ko", ignoreCase = true)) Lang.KO else Lang.EN
    }

    companion object {
        fun fromWireId(value: String?): AppLanguage = entries.firstOrNull { it.wireId == value } ?: SYSTEM
    }
}

enum class Lang { KO, EN }

/**
 * Korean/English catalog mirrored through iOS parity commit `565cfff`.
 *
 * Terminal chrome intentionally remains English in both languages; this catalog contains natural
 * language help, error, date, dialog, and accessibility text.
 */
data class L10n(val lang: Lang) {
    val a11ySettings get() = choose("설정", "Settings")
    val a11yRefresh get() = choose("새로고침", "Refresh")
    val menuRefresh get() = choose("새로고침", "Refresh")
    val menuDelete get() = choose("삭제", "Delete")
    val a11yMoveUp get() = choose("위로 이동", "Move up")
    val a11yMoveDown get() = choose("아래로 이동", "Move down")

    val settingsRefreshHelp: String
        get() = choose(
            "화면이 켜져 있을 때만 갱신. 너무 짧으면 429 제한에 걸릴 수 있습니다. 창이 리셋되는 시각에는 한 번 더 갱신합니다.",
            "Refreshes only while the screen is on. Too short may hit the 429 rate limit. An extra refresh runs when a window resets.",
        )

    fun settingsRefreshAutoHelp(current: String) = choose(
        "auto: 사용량이 빠르게 오르면 간격을 줄이고, 멈추면 늘립니다(10초~5분). 현재 $current",
        "auto: shortens the interval while usage climbs and relaxes it when idle (10s–5m). now $current",
    )

    val settingsScreenHelp get() = choose(
        "켜면 앱을 보는 동안 화면이 꺼지지 않습니다.",
        "When on, the screen stays awake while you view the app.",
    )
    val settingsHideUnusedHelp get() = choose(
        "사용률이 0%인(전혀 쓰지 않은) 그래프를 목록·상세에서 숨깁니다.",
        "Hides usage graphs sitting at 0% from the list and detail.",
    )
    val settingsGaugeCritterHelp get() = choose(
        "사용률 100%가 된 게이지 위를 픽셀 슬라임이 통통 튀며 지나갑니다.",
        "A pixel slime hops across any gauge that hits 100%.",
    )
    val settingsHeartbeatHelp get() = choose(
        "'$ watching …' 뒤 커서를 언더바 대신 하트로 표시합니다.",
        "Shows a heart instead of the underscore cursor after '$ watching …'.",
    )
    val settingsHeartbeatModeHelp get() = choose(
        "usage를 고르면 선택한 그래프의 잔여량을 하트 5칸으로 표시합니다(10%당 반 칸).",
        "With usage, the selected graph's remaining amount shows as 5 hearts (half a heart per 10%).",
    )
    val settingsHeartbeatNoGraphs get() = choose(
        "추적할 그래프가 없습니다. 먼저 게이지형 에이전트를 추가하세요.",
        "No graphs to track. Add a gauge-based agent first.",
    )
    val settingsHeartbeatMultiHelp get() = choose(
        "여러 개를 고르면 남은 비율의 평균을 하트로 표시합니다.",
        "Pick several and the heart shows the average of their remaining amounts.",
    )
    val settingsLanguageHelp get() = choose(
        "시스템 언어를 따르거나 직접 선택합니다.",
        "Follow the system language or pick one manually.",
    )

    val settingsNotifHelp get() = choose(
        "사용량 한도가 리셋되면 알림을 보냅니다. 세션은 5시간마다 리셋되어 자주 올 수 있습니다.",
        "Notifies you when a usage limit resets. Sessions reset every 5 hours, so they can be frequent.",
    )
    val settingsNotifDenied get() = choose(
        "알림이 꺼져 있습니다. 아래에서 Android 설정을 열어 켜세요.",
        "Notifications are off. Open Android Settings below to turn them on.",
    )
    val settingsNotifOpenSettings get() = choose(
        "[ Android 설정 열기 ↗ ]",
        "[ open Android Settings ↗ ]",
    )

    fun notifResetTitle(provider: String, account: String?): String =
        account?.takeIf(String::isNotBlank)?.let { "$provider · $it" } ?: provider

    fun notifResetBody(session: Boolean, weekly: Boolean): String = when (lang) {
        Lang.KO -> when {
            session && weekly -> "사용량 한도가 리셋되었습니다. 다시 사용할 수 있어요."
            session -> "세션 한도가 리셋되었습니다. 다시 사용할 수 있어요."
            else -> "주간 한도가 리셋되었습니다. 다시 사용할 수 있어요."
        }
        Lang.EN -> when {
            session && weekly -> "Your usage limits have reset — you're good to go."
            session -> "Your session limit has reset — you're good to go."
            else -> "Your weekly limit has reset — you're good to go."
        }
    }

    val notifDefaultTitle get() = "TokenWatch"

    val a11yBack get() = choose("뒤로", "Back")
    val a11yStatusPage get() = choose("서비스 상태 페이지 열기", "Open service status page")
    val logoutConfirmTitle get() = choose("로그아웃하시겠어요?", "Log out?")
    val logout get() = choose("로그아웃", "Log out")
    val cancel get() = choose("취소", "Cancel")
    fun logoutMessage(provider: String) = choose(
        "$provider 계정의 저장된 토큰이 이 기기에서 삭제됩니다.",
        "The saved token for your $provider account will be removed from this device.",
    )
    val checking get() = choose("확인 중…", "Checking…")
    val unavailable get() = choose("정보 없음", "No info")
    val usageLegend get() = choose(
        "= 현재 시각 · 채움이 이 선보다 앞서면 시간보다 빠른 소비",
        "= current time · fill past this line means faster-than-time usage",
    )
    val usageAllUnusedHidden get() = choose("미사용(0%) 창은 숨김", "unused (0%) windows hidden")

    fun paceAhead(percent: Int) = choose("↑ 시간 대비 $percent%p 빠름", "↑ $percent%p ahead of pace")
    fun paceUnder(percent: Int) = choose("↓ 시간 대비 $percent%p 여유", "↓ $percent%p under pace")
    val paceEven get() = choose("≈ 시간과 비슷한 속도", "≈ on pace with time")

    fun depletionEta(days: Int, hours: Int, minutes: Int): String = when (lang) {
        Lang.KO -> when {
            days > 0 -> "약 ${days}일 ${hours}시간 후"
            hours > 0 -> "약 ${hours}시간 ${minutes}분 후"
            else -> "약 ${max(1, minutes)}분 후"
        }
        Lang.EN -> when {
            days > 0 -> "in ~${days}d ${hours}h"
            hours > 0 -> "in ~${hours}h ${minutes}m"
            else -> "in ~${max(1, minutes)}m"
        }
    }

    fun depletionWarning(eta: String) = choose(
        "이 속도면 리셋 전 소진 예상 ($eta)",
        "At this rate, will run out before reset ($eta)",
    )

    fun a11yUsed(percent: Int) = choose("$percent% 사용", "$percent% used")
    fun a11yRemaining(percent: Int) = choose("$percent% 남음", "$percent% left")
    val creditApproxNote get() = choose(
        "총액은 관측된 최고 잔액 기준 추정",
        "total estimated from highest observed balance",
    )
    val creditResetButton get() = choose("[재설정]", "[reset]")
    val creditResetTitle get() = choose("게이지 기준 재설정", "Reset gauge scale")
    val creditResetConfirm get() = choose("재설정", "Reset")
    val creditResetMessage get() = choose(
        "현재 잔액을 100%(가득)로 삼아 이 게이지의 기준을 다시 잡습니다. 이상값으로 게이지가 낮게 굳었을 때 사용하세요.",
        "Re-baselines this gauge, treating the current balance as 100% (full). Use when a spike has frozen the gauge too low.",
    )
    val resetDone get() = choose("리셋됨", "reset")

    fun resetExact(date: Instant, kind: WindowKind, zoneId: ZoneId = ZoneId.systemDefault()): String {
        val pattern = when (lang to kind) {
            Lang.KO to WindowKind.SESSION -> "a h:mm"
            Lang.KO to WindowKind.WEEKLY -> "M월 d일 a h:mm"
            Lang.EN to WindowKind.SESSION -> "h:mm a"
            Lang.EN to WindowKind.WEEKLY -> "MMM d, h:mm a"
            else -> error("Unsupported language/window kind")
        }
        return DateTimeFormatter.ofPattern(pattern, dateLocale)
            .format(date.atZone(zoneId))
    }

    fun resetRemaining(kind: WindowKind, days: Int, hours: Int, minutes: Int): String = when (kind) {
        WindowKind.SESSION -> when (lang) {
            Lang.KO -> if (hours > 0) "${hours}시간 ${minutes}분 남음" else "${minutes}분 남음"
            Lang.EN -> if (hours > 0) "${hours}h ${minutes}m left" else "${minutes}m left"
        }
        WindowKind.WEEKLY -> when (lang) {
            Lang.KO -> if (days > 0) "${days}일 ${hours}시간 남음" else "${hours}시간 남음"
            Lang.EN -> if (days > 0) "${days}d ${hours}h left" else "${hours}h left"
        }
    }

    fun resetLine(exact: String, remaining: String?): String = when (lang) {
        Lang.KO -> remaining?.let { "리셋 $exact · $it" } ?: "리셋 $exact"
        Lang.EN -> remaining?.let { "resets $exact · $it" } ?: "resets $exact"
    }

    val errAuthExpired get() = choose(
        "인증이 만료되었습니다. 다시 로그인해 주세요.",
        "Authentication expired. Please log in again.",
    )
    val errRateLimited get() = choose("요청이 많아 잠시 대기 중입니다.", "Too many requests. Waiting a moment.")
    fun errHttp(code: Int) = choose("사용량 조회 실패 (HTTP $code).", "Failed to fetch usage (HTTP $code).")
    fun errDecode(message: String) = choose("응답 해석 실패: $message", "Failed to read response: $message")
    val errNoWindows get() = choose("표시할 사용량 창이 없습니다.", "No usage windows to display.")
    fun errRateLimitedRetry(minutes: Int) = choose(
        "요청이 많아 잠시 대기 중입니다. 약 ${minutes}분 후 재시도합니다.",
        "Too many requests. Retrying in ~$minutes min.",
    )
    fun errRateLimitedRetryStale(minutes: Int) = choose(
        "요청이 많아 대기 중입니다. 약 ${minutes}분 후 재시도 · 아래 그래프는 갱신되지 않습니다.",
        "Too many requests. Retrying in ~$minutes min · usage below isn't updating.",
    )
    fun errTokenExchange(message: String) = choose("토큰 교환 실패: $message", "Token exchange failed: $message")
    fun errTokenRefresh(message: String) = choose("토큰 갱신 실패: $message", "Token refresh failed: $message")
    val errNotAuthenticated get() = choose("로그인이 필요합니다.", "Login required.")
    fun errParse(message: String) = choose("응답 파싱 실패: $message", "Failed to parse response: $message")
    val errAuthMethodUnavailable get() = choose(
        "이 로그인 방식은 곧 지원됩니다.",
        "This sign-in method is coming soon.",
    )

    val deviceFlowRequesting get() = choose("코드 요청 중…", "requesting code…")
    val deviceFlowPrompt get() = choose(
        "브라우저에서 아래 코드를 입력해 인증하세요.",
        "Enter this code in your browser to authorize.",
    )
    val deviceFlowOpen get() = choose("[ 브라우저 열기 ↗ ]", "[ open browser ↗ ]")
    val deviceFlowWaiting get() = choose("인증 대기 중…", "waiting for authorization…")
    val deviceFlowExpired get() = choose(
        "코드가 만료되었습니다. 다시 시도해 주세요.",
        "The code expired. Please try again.",
    )
    val deviceFlowDenied get() = choose("인증이 거부되었습니다.", "Authorization was denied.")
    val codexAdditionalLimit get() = choose("추가 한도", "Additional limit")

    fun usageCategoryLabel(category: UsageCategory) = when (category) {
        UsageCategory.SUBSCRIPTION -> choose("구독 사용량", "subscription")
        UsageCategory.API_CREDIT -> choose("API 크레딧", "API credit")
    }

    fun serviceHealthLabel(health: ServiceHealth) = when (health) {
        ServiceHealth.OPERATIONAL -> choose("정상", "operational")
        ServiceHealth.CAUTION -> choose("주의", "caution")
        ServiceHealth.MAJOR -> choose("이상", "outage")
        ServiceHealth.TOTAL_OUTAGE -> choose("전체 이상", "total outage")
        ServiceHealth.MAINTENANCE -> choose("전체 점검중", "under maintenance")
        ServiceHealth.UNKNOWN -> choose("알 수 없음", "unknown")
    }

    val serviceMaintenanceBadge get() = choose("점검중", "maintenance")

    val dateLocale: Locale
        get() = Locale.forLanguageTag(if (lang == Lang.KO) "ko-KR" else "en-US")

    fun relativeTime(date: Instant, relativeTo: Instant = Instant.now()): String {
        val duration = Duration.between(relativeTo, date)
        val future = !duration.isNegative
        val seconds = kotlin.math.abs(duration.seconds)
        val amountAndUnit = when {
            seconds < 60 -> return choose("방금", "now")
            seconds < 3_600 -> (seconds / 60) to choose("분", "m")
            seconds < 86_400 -> (seconds / 3_600) to choose("시간", "h")
            else -> (seconds / 86_400) to choose("일", "d")
        }
        val (amount, unit) = amountAndUnit
        return when (lang) {
            Lang.KO -> if (future) "$amount$unit 후" else "$amount$unit 전"
            Lang.EN -> if (future) "in $amount$unit" else "$amount$unit ago"
        }
    }

    fun absoluteDateTime(date: Instant, zoneId: ZoneId = ZoneId.systemDefault()): String {
        val pattern = if (lang == Lang.KO) "M월 d일 a h:mm" else "MMM d, h:mm a"
        return DateTimeFormatter.ofPattern(pattern, dateLocale).format(date.atZone(zoneId))
    }

    private fun choose(korean: String, english: String): String = if (lang == Lang.KO) korean else english
}
