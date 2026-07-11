package com.ScienceFiction.TokenWatchAndroid.network.parsing

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtobufTest {
    @Test
    fun protobufParsesScalarAndNestedFields() {
        val fields = Protobuf.fields(sampleMessage())

        assertEquals(3, fields.size)
        assertEquals(42uL, fields[0].varint)
        assertEquals(12.5, Double.fromBits(fields[1].fixed64!!.toLong()), 0.0)
        assertNotNull(fields[2].bytes)
    }

    @Test
    fun protobufCollectNumbersRecurses() {
        val numbers = Protobuf.collectNumbers(sampleMessage())

        assertTrue(numbers.doubles.contains(12.5))
        assertTrue(numbers.varints.contains(42uL))
        assertTrue(numbers.varints.contains(1_700_000_000uL))
    }

    @Test
    fun grpcWebFrameExtractsMessage() {
        val payload = sampleMessage()
        val length = payload.size
        val framed = byteArrayOf(
            0x00,
            ((length shr 24) and 0xff).toByte(),
            ((length shr 16) and 0xff).toByte(),
            ((length shr 8) and 0xff).toByte(),
            (length and 0xff).toByte(),
        ) + payload + byteArrayOf(0x80.toByte(), 0, 0, 0, 0)

        assertArrayEquals(payload, Protobuf.grpcWebMessage(framed))
    }

    /** field1=varint42, field2=double12.5, field3={field1=varint1700000000}. */
    private fun sampleMessage(): ByteArray {
        val nested = tag(field = 1, wireType = 0) + varint(1_700_000_000uL)
        return tag(field = 1, wireType = 0) + varint(42uL) +
            tag(field = 2, wireType = 1) + fixed64LittleEndian(12.5.toBits().toULong()) +
            tag(field = 3, wireType = 2) + varint(nested.size.toULong()) + nested
    }

    private fun tag(field: Int, wireType: Int): ByteArray =
        varint(((field shl 3) or wireType).toULong())

    private fun varint(input: ULong): ByteArray {
        var value = input
        val output = mutableListOf<Byte>()
        do {
            var byte = (value and 0x7fuL).toInt()
            value = value shr 7
            if (value != 0uL) byte = byte or 0x80
            output += byte.toByte()
        } while (value != 0uL)
        return output.toByteArray()
    }

    private fun fixed64LittleEndian(value: ULong): ByteArray =
        ByteArray(8) { offset -> ((value shr (8 * offset)) and 0xffuL).toByte() }
}
