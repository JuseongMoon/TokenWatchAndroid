package com.ScienceFiction.TokenWatchAndroid.network.parsing

/**
 * Minimal schema-less protobuf wire reader and gRPC-web frame extractor.
 *
 * Supported protobuf wire types are varint (0), fixed64 (1),
 * length-delimited (2), and fixed32 (5), matching the clean iOS baseline.
 */
object Protobuf {
    data class Field(
        val number: Int,
        val wireType: Int,
        val varint: ULong? = null,
        val fixed64: ULong? = null,
        val fixed32: UInt? = null,
        val bytes: ByteArray? = null,
    )

    data class NumberCollection(
        val doubles: List<Double>,
        val varints: List<ULong>,
    )

    /** Parses top-level fields, returning the valid prefix if input is malformed. */
    fun fields(data: ByteArray): List<Field> {
        val output = mutableListOf<Field>()
        var index = 0

        while (index < data.size) {
            val (tag, afterTag) = readVarint(data, index) ?: break
            index = afterTag

            val fieldNumber = tag shr 3
            if (fieldNumber == 0uL || fieldNumber > Int.MAX_VALUE.toULong()) break
            val number = fieldNumber.toInt()
            val wireType = (tag and 0x7uL).toInt()

            when (wireType) {
                0 -> {
                    val (value, next) = readVarint(data, index) ?: return output
                    index = next
                    output += Field(number = number, wireType = wireType, varint = value)
                }

                1 -> {
                    if (index > data.size - FIXED64_BYTES) return output
                    val value = readFixed(data, index, FIXED64_BYTES)
                    index += FIXED64_BYTES
                    output += Field(number = number, wireType = wireType, fixed64 = value)
                }

                2 -> {
                    val (length, start) = readVarint(data, index) ?: return output
                    val end = checkedEnd(start, length, data.size) ?: return output
                    index = end
                    output += Field(
                        number = number,
                        wireType = wireType,
                        bytes = data.copyOfRange(start, end),
                    )
                }

                5 -> {
                    if (index > data.size - FIXED32_BYTES) return output
                    val value = readFixed(data, index, FIXED32_BYTES).toUInt()
                    index += FIXED32_BYTES
                    output += Field(number = number, wireType = wireType, fixed32 = value)
                }

                else -> return output
            }
        }
        return output
    }

    /**
     * Extracts the first non-trailer gRPC-web payload.
     *
     * A frame consists of one flag byte, a four-byte big-endian length, and
     * the payload. Bit 0x80 marks a trailer. Non-empty unframed input falls
     * back to the original bytes so callers can also accept raw protobuf.
     */
    fun grpcWebMessage(data: ByteArray): ByteArray? {
        var index = 0
        while (index <= data.size - GRPC_HEADER_BYTES) {
            val flag = data[index].toInt() and 0xff
            val length =
                ((data[index + 1].toLong() and 0xff) shl 24) or
                    ((data[index + 2].toLong() and 0xff) shl 16) or
                    ((data[index + 3].toLong() and 0xff) shl 8) or
                    (data[index + 4].toLong() and 0xff)
            val start = index + GRPC_HEADER_BYTES
            if (length > Int.MAX_VALUE) break
            val end = start.toLong() + length
            if (end > data.size) break

            if (flag and TRAILER_FLAG == 0) {
                return data.copyOfRange(start, end.toInt())
            }
            index = end.toInt()
        }
        return data.takeIf { it.isNotEmpty() }
    }

    /**
     * Recursively collects numeric leaves for schema-less response heuristics.
     * fixed64 is interpreted as Double and fixed32 as Float. Length-delimited
     * values are traversed to the same maximum nesting depth as iOS.
     */
    fun collectNumbers(data: ByteArray, depth: Int = 0): NumberCollection {
        val doubles = mutableListOf<Double>()
        val varints = mutableListOf<ULong>()

        for (field in fields(data)) {
            field.varint?.let(varints::add)
            field.fixed64?.let { doubles += Double.fromBits(it.toLong()) }
            field.fixed32?.let { doubles += Float.fromBits(it.toInt()).toDouble() }

            val nested = field.bytes
            if (nested != null && depth < MAX_NESTED_DEPTH && looksLikeMessage(nested)) {
                val collected = collectNumbers(nested, depth + 1)
                doubles += collected.doubles
                varints += collected.varints
            }
        }
        return NumberCollection(doubles = doubles, varints = varints)
    }

    /** True only when all bytes form a complete supported protobuf message. */
    private fun looksLikeMessage(data: ByteArray): Boolean {
        if (data.isEmpty()) return false
        var index = 0

        while (index < data.size) {
            val (tag, afterTag) = readVarint(data, index) ?: return false
            index = afterTag
            val number = tag shr 3
            val wireType = (tag and 0x7uL).toInt()
            if (number == 0uL) return false

            when (wireType) {
                0 -> index = readVarint(data, index)?.second ?: return false
                1 -> index += FIXED64_BYTES
                2 -> {
                    val (length, start) = readVarint(data, index) ?: return false
                    index = checkedEnd(start, length, data.size) ?: return false
                }
                5 -> index += FIXED32_BYTES
                else -> return false
            }
            if (index > data.size) return false
        }
        return true
    }

    private fun readVarint(data: ByteArray, start: Int): Pair<ULong, Int>? {
        var result = 0uL
        var shift = 0
        var index = start

        while (index < data.size) {
            val byte = data[index].toInt() and 0xff
            result = result or ((byte and 0x7f).toULong() shl shift)
            index += 1
            if (byte and 0x80 == 0) return result to index
            shift += 7
            if (shift >= ULong.SIZE_BITS) return null
        }
        return null
    }

    private fun readFixed(data: ByteArray, start: Int, count: Int): ULong {
        var value = 0uL
        repeat(count) { offset ->
            value = value or ((data[start + offset].toInt() and 0xff).toULong() shl (8 * offset))
        }
        return value
    }

    private fun checkedEnd(start: Int, length: ULong, size: Int): Int? {
        if (length > Int.MAX_VALUE.toULong()) return null
        val end = start.toLong() + length.toLong()
        if (end > size) return null
        return end.toInt()
    }

    private const val FIXED32_BYTES = 4
    private const val FIXED64_BYTES = 8
    private const val GRPC_HEADER_BYTES = 5
    private const val TRAILER_FLAG = 0x80
    private const val MAX_NESTED_DEPTH = 3
}
