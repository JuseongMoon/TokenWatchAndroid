package com.ScienceFiction.TokenWatchAndroid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CreditPeaksCodecTest {
    @Test
    fun `round trip preserves agent-window keys and finite peaks`() {
        val peaks = linkedMapOf(
            "00000000-0000-0000-0000-000000000001|Credits" to 500.0,
            "00000000-0000-0000-0000-000000000002|Balance" to 94.25,
        )

        assertEquals(peaks, CreditPeaksCodec.decode(CreditPeaksCodec.encode(peaks)))
    }

    @Test
    fun `missing malformed and non-object payloads recover without crashing`() {
        assertTrue(CreditPeaksCodec.decode(null).isEmpty())
        assertTrue(CreditPeaksCodec.decode("").isEmpty())
        assertTrue(CreditPeaksCodec.decode("not-json").isEmpty())
        assertTrue(CreditPeaksCodec.decode("[]").isEmpty())
    }

    @Test
    fun `invalid values are ignored while valid neighbors survive`() {
        assertEquals(
            mapOf("valid" to 42.5, "negative" to -1.0),
            CreditPeaksCodec.decode(
                """{"valid":42.5,"string":"9","null":null,"negative":-1,"tail":{}}""",
            ),
        )
    }

    @Test
    fun `encoder skips non-finite values rejected by JSON`() {
        val encoded = CreditPeaksCodec.encode(
            mapOf("valid" to 10.0, "nan" to Double.NaN, "infinite" to Double.POSITIVE_INFINITY),
        )

        assertEquals(mapOf("valid" to 10.0), CreditPeaksCodec.decode(encoded))
    }
}
