package com.ScienceFiction.TokenWatchAndroid.domain

import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkHoursScheduleTest {
    private val zone = ZoneOffset.UTC
    private fun at(value: String) = Instant.parse(value)
    private fun monThuMorning(): WorkHoursSchedule {
        var schedule = WorkHoursSchedule()
        for (hour in 0 until 7) {
            schedule = schedule.setting(0, hour, true).setting(3, hour, true)
        }
        return schedule
    }

    @Test fun encodingAndRectanglePaintingRoundTrip() {
        val painted = WorkHoursSchedule().settingRect(0, 9, 3, 12, true)
        assertEquals(16, painted.onHours)
        assertTrue(painted.isOn(2, 10))
        assertFalse(painted.isOn(4, 10))
        assertEquals(painted, WorkHoursSchedule.decode(painted.encoded))
        assertNull(WorkHoursSchedule.active("garbage"))
    }

    @Test fun markerFreezesBetweenConfiguredBlocks() {
        val schedule = monThuMorning()
        val start = at("2024-01-01T00:00:00Z")
        val end = at("2024-01-08T00:00:00Z")
        assertEquals(0.5, WorkHours.markerFraction(start, end, at("2024-01-03T12:00:00Z"), schedule, zone)!!, 0.001)
        assertEquals(0.75, WorkHours.markerFraction(start, end, at("2024-01-04T03:30:00Z"), schedule, zone)!!, 0.001)
        assertFalse(WorkHours.isWorkingTime(at("2024-01-03T12:00:00Z"), schedule, zone))
        assertTrue(WorkHours.isWorkingTime(at("2024-01-04T03:00:00Z"), schedule, zone))
    }
}
