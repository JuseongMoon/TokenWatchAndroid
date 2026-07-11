package com.ScienceFiction.TokenWatchAndroid.domain

/** Normalized provider service status used by the iOS clean baseline. */
enum class ServiceHealth {
    OPERATIONAL,
    DEGRADED,
    MAJOR,
    MAINTENANCE,
    UNKNOWN,
}
