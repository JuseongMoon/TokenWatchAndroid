package com.ScienceFiction.TokenWatchAndroid.data

import com.ScienceFiction.TokenWatchAndroid.notifications.WindowObservation
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResetBaselineCodecTest {
    @Test
    fun roundTripsResetObservations() {
        val observations = linkedMapOf(
            "agent|session" to WindowObservation(Instant.parse("2026-07-16T01:00:00Z"), 42.5),
            "agent|weekly" to WindowObservation(null, 3.0),
        )

        assertEquals(observations, ResetBaselineCodec.decode(ResetBaselineCodec.encode(observations)))
    }

    @Test
    fun malformedOrNonFiniteEntriesAreIgnored() {
        assertTrue(ResetBaselineCodec.decode("not-json").isEmpty())
        val decoded = ResetBaselineCodec.decode(
            """{"good":{"resetsAt":null,"usedPercent":1},"bad":{"usedPercent":"x"}}""",
        )
        assertEquals(setOf("good"), decoded.keys)
    }
}
