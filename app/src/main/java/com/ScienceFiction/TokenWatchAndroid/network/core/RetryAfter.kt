package com.ScienceFiction.TokenWatchAndroid.network.core

import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Parses either delta-seconds or an RFC 1123 HTTP-date, matching the iOS baseline. */
fun parseRetryAfter(value: String?, now: Instant = Instant.now()): Instant? {
    val normalized = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
    normalized.toDoubleOrNull()?.let { seconds ->
        if (seconds.isFinite()) {
            return now.plusMillis((seconds * 1_000.0).toLong())
        }
    }
    return runCatching {
        ZonedDateTime.parse(normalized, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
    }.getOrNull()
}
