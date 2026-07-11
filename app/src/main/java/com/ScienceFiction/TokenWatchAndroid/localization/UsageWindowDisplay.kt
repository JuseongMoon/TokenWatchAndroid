package com.ScienceFiction.TokenWatchAndroid.localization

import com.ScienceFiction.TokenWatchAndroid.domain.UsageWindow
import com.ScienceFiction.TokenWatchAndroid.domain.WindowKind
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

fun UsageWindow.resetExactText(loc: L10n, zoneId: ZoneId = ZoneId.systemDefault()): String? =
    resetsAt?.let { loc.resetExact(it, kind, zoneId) }

fun UsageWindow.resetRemainingText(loc: L10n, at: Instant = Instant.now()): String? {
    val reset = resetsAt ?: return null
    if (!reset.isAfter(at)) return null
    val duration = Duration.between(at, reset)
    return when (kind) {
        WindowKind.SESSION -> {
            val totalMinutes = duration.toMinutes().coerceAtLeast(0)
            loc.resetRemaining(
                kind = kind,
                days = 0,
                hours = (totalMinutes / 60).toInt(),
                minutes = (totalMinutes % 60).toInt(),
            )
        }
        WindowKind.WEEKLY -> {
            val totalHours = duration.toHours().coerceAtLeast(0)
            loc.resetRemaining(
                kind = kind,
                days = (totalHours / 24).toInt(),
                hours = (totalHours % 24).toInt(),
                minutes = 0,
            )
        }
    }
}

fun UsageWindow.resetSummary(
    loc: L10n,
    at: Instant = Instant.now(),
    zoneId: ZoneId = ZoneId.systemDefault(),
): String {
    val reset = resetsAt ?: return ""
    if (!reset.isAfter(at)) return loc.resetDone
    val exact = resetExactText(loc, zoneId).orEmpty()
    return loc.resetLine(exact, resetRemainingText(loc, at)?.takeIf(String::isNotEmpty))
}
