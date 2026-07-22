package com.ScienceFiction.TokenWatchAndroid.domain

/** Provider service status classified from the proportion of unhealthy leaf components. */
enum class ServiceHealth {
    OPERATIONAL,
    CAUTION,
    MAJOR,
    TOTAL_OUTAGE,
    MAINTENANCE,
    UNKNOWN,
}
