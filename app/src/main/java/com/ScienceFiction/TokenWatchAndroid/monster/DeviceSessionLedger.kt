package com.ScienceFiction.TokenWatchAndroid.monster

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException

/**
 * The device session ledger: session and slot keys this device already received a monster for, and the
 * account that received it — independent of which account is signed in now. Signed in as another
 * account, the same key is not requested again, so switching accounts cannot collect the same usage
 * twice. It holds uids and session keys only (no token), stays on the device and is never sent anywhere.
 *
 * iOS keeps it in the Keychain. Android keeps it in Block Store (device-only, no cloud backup) with a
 * DataStore copy for when Play services is missing or a call fails; both are read and merged.
 */
data class DeviceSessionLedgerState(val entries: Map<String, Entry> = emptyMap()) {
    data class Entry(val uid: String, val atMs: Long)

    /** True when another account already received this key (do not request it). */
    fun blocks(key: String, uid: String): Boolean {
        val entry = entries[key] ?: return false
        return entry.uid != uid
    }

    /** The first receiver of a key is kept. */
    fun recording(key: String, uid: String, nowMs: Long): DeviceSessionLedgerState =
        if (key in entries) this else copy(entries = entries + (key to Entry(uid, nowMs)))

    fun purged(nowMs: Long): DeviceSessionLedgerState =
        copy(entries = entries.filterValues { nowMs - it.atMs < RETENTION_MS })

    /** Union of two copies; for a key in both, the earlier record (the first receiver) wins. */
    fun merged(other: DeviceSessionLedgerState): DeviceSessionLedgerState {
        val out = entries.toMutableMap()
        for ((key, entry) in other.entries) {
            val mine = out[key]
            if (mine == null || entry.atMs < mine.atMs) out[key] = entry
        }
        return DeviceSessionLedgerState(out)
    }

    companion object {
        const val RETENTION_MS = 40L * 86_400_000L
    }
}

interface DeviceSessionLedgerStorage {
    suspend fun load(): DeviceSessionLedgerState
    suspend fun save(state: DeviceSessionLedgerState)
}

/** Memory storage (previews and tests). */
class DeviceSessionLedgerMemory(var state: DeviceSessionLedgerState = DeviceSessionLedgerState()) :
    DeviceSessionLedgerStorage {
    override suspend fun load(): DeviceSessionLedgerState = state
    override suspend fun save(state: DeviceSessionLedgerState) {
        this.state = state
    }
}

/**
 * Block Store first, DataStore copy second. Every save goes to both; a load reads both and merges, so a
 * failing Block Store (no Play services) or a lost DataStore file still leaves the other one in force.
 */
class CombinedSessionLedger(
    private val primary: DeviceSessionLedgerStorage,
    private val fallback: DeviceSessionLedgerStorage,
) : DeviceSessionLedgerStorage {
    override suspend fun load(): DeviceSessionLedgerState =
        attempt { primary.load() }.orEmpty().merged(attempt { fallback.load() }.orEmpty())

    override suspend fun save(state: DeviceSessionLedgerState) {
        attempt { primary.save(state) }
        attempt { fallback.save(state) }
    }

    private fun DeviceSessionLedgerState?.orEmpty() = this ?: DeviceSessionLedgerState()

    private suspend fun <T> attempt(block: suspend () -> T): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}

/** The DataStore copy's JSON (`{"entries": {key: {"uid", "atMs"}}}`, the iOS Keychain shape). Broken records are dropped. */
object SessionLedgerJson {
    private val adapter = Moshi.Builder().build().adapter<Map<String, Any?>>(
        Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java),
    )

    fun encode(state: DeviceSessionLedgerState): String = adapter.toJson(
        mapOf("entries" to state.entries.mapValues { (_, entry) -> mapOf("uid" to entry.uid, "atMs" to entry.atMs) }),
    )

    fun decode(json: String): DeviceSessionLedgerState? {
        val root = runCatching { adapter.fromJson(json) }.getOrNull() ?: return null
        val entries = (root["entries"] as? Map<*, *>).orEmpty().entries.mapNotNull { (key, value) ->
            val record = value as? Map<*, *> ?: return@mapNotNull null
            val uid = record["uid"] as? String ?: return@mapNotNull null
            val atMs = record["atMs"].asLong() ?: return@mapNotNull null
            (key as? String ?: return@mapNotNull null) to DeviceSessionLedgerState.Entry(uid, atMs)
        }.toMap()
        return DeviceSessionLedgerState(entries)
    }
}

/**
 * Compact binary form for Block Store, whose entries hold 4 KB each (16 per app). Ten bytes a record:
 *
 * ```text
 * version u8 (1) · uid count u8 · [uid length u8 · UTF-8]… · record count u16 ·
 * [kind u8 (provider index, 255 = random slot) · key time u32 (minutes) · recorded at u32 (minutes) · uid index u8]…
 * ```
 *
 * Bucket and slot times are whole minutes (multiples of 300 s and of the slot length), so they round
 * trip exactly; the recording time is floored to the minute, which only shifts the 40-day purge by
 * under a minute. Sixty spawns a day for 40 days is 24 KB, well inside [MAX_CHUNKS] entries.
 */
object SessionLedgerCodec {
    private const val VERSION = 1
    private const val RANDOM_KIND = 255
    private const val MINUTE_MS = 60_000L

    /** Block Store entry size. */
    const val CHUNK_BYTES = 4096

    /** Entries the ledger may use (Block Store allows 16 per app; the rest stay free). */
    const val MAX_CHUNKS = 12

    /**
     * Provider order for the kind byte. Append only — an index never changes meaning. A unit test pins
     * that every [AgentProvider] has one; a key this table cannot hold is left out of Block Store and
     * kept only in the DataStore copy.
     */
    internal val providerOrder = listOf(
        "claude", "codex", "copilot", "grok", "cursor", "kimi", "openrouter", "deepseek", "poe", "elevenlabs",
    )

    fun encode(state: DeviceSessionLedgerState): ByteArray {
        // Newest first, so trimming to the size limit drops the oldest records.
        var records = state.entries.entries
            .mapNotNull { (key, entry) -> parseKey(key)?.let { Triple(it, entry, key) } }
            .sortedByDescending { it.second.atMs }
        while (true) {
            val bytes = write(records)
            if (bytes.size <= CHUNK_BYTES * MAX_CHUNKS || records.isEmpty()) return bytes
            records = records.dropLast(maxOf(1, records.size / 10))
        }
    }

    private fun write(records: List<Triple<Pair<Int, Long>, DeviceSessionLedgerState.Entry, String>>): ByteArray {
        val uids = records.map { it.second.uid }.distinct().take(255)
        val kept = records.filter { it.second.uid in uids }
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.writeByte(VERSION)
            out.writeByte(uids.size)
            for (uid in uids) {
                val utf8 = uid.toByteArray(Charsets.UTF_8)
                out.writeByte(utf8.size)
                out.write(utf8)
            }
            out.writeShort(kept.size.coerceAtMost(0xFFFF))
            for ((parsed, entry) in kept.take(0xFFFF)) {
                out.writeByte(parsed.first)
                out.writeInt((parsed.second / MINUTE_MS).toInt())
                out.writeInt((entry.atMs / MINUTE_MS).toInt())
                out.writeByte(uids.indexOf(entry.uid))
            }
        }
        return buffer.toByteArray()
    }

    fun decode(bytes: ByteArray): DeviceSessionLedgerState {
        if (bytes.isEmpty()) return DeviceSessionLedgerState()
        return try {
            DataInputStream(bytes.inputStream()).use { input ->
                if (input.readUnsignedByte() != VERSION) return DeviceSessionLedgerState()
                val uids = List(input.readUnsignedByte()) {
                    val utf8 = ByteArray(input.readUnsignedByte())
                    input.readFully(utf8)
                    utf8.toString(Charsets.UTF_8)
                }
                val count = input.readUnsignedShort()
                val entries = buildMap {
                    repeat(count) {
                        val kind = input.readUnsignedByte()
                        val keyMs = (input.readInt().toLong() and 0xFFFF_FFFFL) * MINUTE_MS
                        val atMs = (input.readInt().toLong() and 0xFFFF_FFFFL) * MINUTE_MS
                        val uid = uids.getOrNull(input.readUnsignedByte()) ?: return@repeat
                        val key = when (kind) {
                            RANDOM_KIND -> MonsterSessionKey.randomLedgerKey(keyMs)
                            else -> providerOrder.getOrNull(kind)?.let { "$it|$keyMs" } ?: return@repeat
                        }
                        put(key, DeviceSessionLedgerState.Entry(uid, atMs))
                    }
                }
                DeviceSessionLedgerState(entries)
            }
        } catch (_: IOException) {
            DeviceSessionLedgerState()
        }
    }

    /** Splits into Block Store-sized entries, in order. */
    fun chunks(bytes: ByteArray): List<ByteArray> =
        (bytes.indices step CHUNK_BYTES).map { start -> bytes.copyOfRange(start, minOf(bytes.size, start + CHUNK_BYTES)) }

    /** `provider|bucketMs` or `rand|slotMs` → (kind, time). Null for a key this format cannot hold. */
    private fun parseKey(key: String): Pair<Int, Long>? {
        val head = key.substringBefore('|', missingDelimiterValue = "")
        val ms = key.substringAfter('|', missingDelimiterValue = "").toLongOrNull() ?: return null
        if (ms < 0 || ms % MINUTE_MS != 0L || ms / MINUTE_MS > 0xFFFF_FFFFL) return null
        val kind = if (head == "rand") RANDOM_KIND else providerOrder.indexOf(head).takeIf { it >= 0 } ?: return null
        return kind to ms
    }
}
