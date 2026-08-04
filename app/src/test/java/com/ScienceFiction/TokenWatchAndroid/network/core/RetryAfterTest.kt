package com.ScienceFiction.TokenWatchAndroid.network.core

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RetryAfterTest {
    private val now = Instant.parse("2026-07-11T00:00:00Z")

    @Test
    fun parsesSeconds() {
        assertEquals(now.plusSeconds(120), parseRetryAfter("120", now))
        assertEquals(now.plusMillis(1_500), parseRetryAfter(" 1.5 ", now))
    }

    @Test
    fun parsesHttpDate() {
        assertEquals(
            Instant.parse("2015-10-21T07:28:00Z"),
            parseRetryAfter("Wed, 21 Oct 2015 07:28:00 GMT", now),
        )
    }

    @Test
    fun rejectsMissingAndMalformedValues() {
        assertNull(parseRetryAfter(null, now))
        assertNull(parseRetryAfter("", now))
        assertNull(parseRetryAfter("later", now))
        assertNull(parseRetryAfter("inf", now))
        assertNull(parseRetryAfter("nan", now))
        assertNull(parseRetryAfter("-1", now))
    }

    @Test fun clampsHugeValuesAndFarDatesToOneDay() {
        listOf("99999999999999999999", "Fri, 01 Jan 2100 00:00:00 GMT").forEach { raw ->
            val parsed = parseRetryAfter(raw, now)
            assertTrue(parsed == null || !parsed.isAfter(now.plusSeconds(86_400)))
        }
    }
}
