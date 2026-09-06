package com.ScienceFiction.TokenWatchAndroid.domain

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Weekly 7 x 24 work-hour grid, day 0 = Monday. */
data class WorkHoursSchedule private constructor(private val slots: List<Boolean>) {
    constructor() : this(List(SlotCount) { false })

    fun isOn(day: Int, hour: Int): Boolean =
        day in 0 until Days && hour in 0 until Hours && slots[index(day, hour)]

    fun setting(day: Int, hour: Int, on: Boolean): WorkHoursSchedule {
        if (day !in 0 until Days || hour !in 0 until Hours) return this
        return WorkHoursSchedule(slots.toMutableList().apply { this[index(day, hour)] = on })
    }

    fun settingRect(aDay: Int, aHour: Int, bDay: Int, bHour: Int, on: Boolean): WorkHoursSchedule {
        val dayRange = minOf(aDay, bDay).coerceAtLeast(0)..maxOf(aDay, bDay).coerceAtMost(Days - 1)
        val hourRange = minOf(aHour, bHour).coerceAtLeast(0)..maxOf(aHour, bHour).coerceAtMost(Hours - 1)
        if (dayRange.isEmpty() || hourRange.isEmpty()) return this
        val next = slots.toMutableList()
        for (day in dayRange) for (hour in hourRange) next[index(day, hour)] = on
        return WorkHoursSchedule(next)
    }

    val isEmpty: Boolean get() = slots.none { it }
    val onHours: Int get() = slots.count { it }
    val encoded: String get() = slots.joinToString("") { if (it) "1" else "0" }

    companion object {
        const val Days = 7
        const val Hours = 24
        const val SlotCount = Days * Hours
        fun index(day: Int, hour: Int): Int = day * Hours + hour
        fun decode(raw: String): WorkHoursSchedule = if (raw.length == SlotCount) {
            WorkHoursSchedule(raw.map { it == '1' })
        } else {
            WorkHoursSchedule()
        }

        /**
         * Effective on/off. The flag is tri-state: absent means "derive from the schedule", so an
         * existing user with painted hours keeps the feature after updating.
         */
        fun isEnabled(raw: String, enabled: Boolean?): Boolean = enabled ?: !decode(raw).isEmpty

        /**
         * Schedule to drive weekly markers with, or null to fall back to a uniform flow. There is no
         * single-argument overload on purpose so every call site has to state the flag.
         */
        fun active(raw: String, enabled: Boolean?): WorkHoursSchedule? =
            if (!isEnabled(raw, enabled)) null else decode(raw).takeUnless { it.isEmpty }
    }
}

object WorkHours {
    fun isWorkingTime(
        at: Instant,
        schedule: WorkHoursSchedule,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): Boolean {
        val local = at.atZone(zoneId)
        return schedule.isOn(local.dayOfWeek.value - 1, local.hour)
    }

    fun workingSeconds(
        from: Instant,
        to: Instant,
        schedule: WorkHoursSchedule,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): Double {
        if (!to.isAfter(from) || schedule.isEmpty) return 0.0
        var total = 0.0
        var cursor = from
        var guardCount = 0
        while (cursor.isBefore(to) && guardCount++ < 400) {
            val local = cursor.atZone(zoneId)
            var next = local.truncatedTo(ChronoUnit.HOURS).plusHours(1).toInstant()
            if (!next.isAfter(cursor)) next = cursor.plusSeconds(3600)
            val segmentEnd = minOf(next, to)
            if (schedule.isOn(local.dayOfWeek.value - 1, local.hour)) {
                total += Duration.between(cursor, segmentEnd).toNanos() / 1_000_000_000.0
            }
            cursor = segmentEnd
        }
        return total
    }

    fun markerFraction(
        windowStart: Instant,
        windowEnd: Instant,
        now: Instant,
        schedule: WorkHoursSchedule,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): Double? {
        if (!windowEnd.isAfter(windowStart)) return null
        val total = workingSeconds(windowStart, windowEnd, schedule, zoneId)
        if (total <= 0.0) return null
        val clamped = maxOf(windowStart, minOf(now, windowEnd))
        return (workingSeconds(windowStart, clamped, schedule, zoneId) / total).coerceIn(0.0, 1.0)
    }
}
