package com.ScienceFiction.TokenWatchAndroid.monster

import com.ScienceFiction.TokenWatchAndroid.domain.AgentProvider
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The device session ledger: account-free session keys → the uid that received them, kept 40 days.
 * Block Store holds it in a compact binary form split into 4 KB entries; a DataStore copy covers a
 * missing or failing Block Store, and the two are read merged.
 */
class DeviceSessionLedgerTest {
    private val now = 1_800_000_000_000L
    private val day = 86_400_000L

    @Test fun anotherAccountIsBlockedButTheSameOneIsNot() {
        val ledger = DeviceSessionLedgerState().recording("claude|1800000000000", "a", now)
        assertTrue(ledger.blocks("claude|1800000000000", "b"))
        assertFalse(ledger.blocks("claude|1800000000000", "a"))
        assertFalse(ledger.blocks("claude|1800000300000", "b"))
        // The first receiver stays.
        assertEquals("a", ledger.recording("claude|1800000000000", "b", now + 1).entries.getValue("claude|1800000000000").uid)
    }

    @Test fun recordsOlderThanFortyDaysArePurged() {
        val ledger = DeviceSessionLedgerState()
            .recording("claude|60000", "a", now - 40 * day)
            .recording("claude|120000", "a", now - 40 * day + 1)
            .purged(now)
        assertEquals(setOf("claude|120000"), ledger.entries.keys)
    }

    @Test fun mergingKeepsTheFirstReceiver() {
        val blockStore = DeviceSessionLedgerState(mapOf("codex|60000" to DeviceSessionLedgerState.Entry("a", now)))
        val copy = DeviceSessionLedgerState(
            mapOf(
                "codex|60000" to DeviceSessionLedgerState.Entry("b", now - 1),
                "kimi|300000" to DeviceSessionLedgerState.Entry("c", now),
            ),
        )
        val merged = blockStore.merged(copy)
        assertEquals("b", merged.entries.getValue("codex|60000").uid)
        assertEquals(setOf("codex|60000", "kimi|300000"), merged.entries.keys)
    }

    @Test fun compactCodecRoundTrips() {
        val ledger = DeviceSessionLedgerState(
            mapOf(
                "claude|1800000000000" to DeviceSessionLedgerState.Entry("uid-a", now),
                "elevenlabs|1800000300000" to DeviceSessionLedgerState.Entry("uid-b", now - 5 * 60_000),
                "kimi|1800003600000" to DeviceSessionLedgerState.Entry("uid-a", now - 60_000),
            ),
        )
        val bytes = SessionLedgerCodec.encode(ledger)
        // A record is ten bytes beside the uid table.
        assertEquals(1 + 1 + (1 + 5) * 2 + 2 + 10 * 3, bytes.size)
        assertEquals(ledger, SessionLedgerCodec.decode(bytes))
        assertEquals(DeviceSessionLedgerState(), SessionLedgerCodec.decode(ByteArray(0)))
        assertEquals(DeviceSessionLedgerState(), SessionLedgerCodec.decode(byteArrayOf(9, 9, 9)))
    }

    @Test fun oldRandomSlotRecordsAreSkipped() {
        // Random spawns no longer record here (they draw per agent); kind 255 in an older form is dropped.
        val minutes = 30_000_000 // 1_800_000_000_000 ms
        val buffer = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(buffer).use { out ->
            out.writeByte(1)
            out.writeByte(1)
            out.writeByte(1)
            out.write("a".toByteArray())
            out.writeShort(2)
            for (kind in listOf(255, 0)) {
                out.writeByte(kind)
                out.writeInt(minutes)
                out.writeInt(minutes)
                out.writeByte(0)
            }
        }
        val decoded = SessionLedgerCodec.decode(buffer.toByteArray())
        assertEquals(setOf("claude|1800000000000"), decoded.entries.keys)
        // A key the format cannot hold stays out of Block Store.
        assertEquals(
            DeviceSessionLedgerState(),
            SessionLedgerCodec.decode(SessionLedgerCodec.encode(DeviceSessionLedgerState().recording("rand|60000", "a", now))),
        )
    }

    @Test fun recordingTimeRoundsDownToTheMinute() {
        val ledger = DeviceSessionLedgerState().recording("claude|60000", "a", 125_000)
        assertEquals(120_000L, SessionLedgerCodec.decode(SessionLedgerCodec.encode(ledger)).entries.getValue("claude|60000").atMs)
    }

    @Test fun everyProviderHasAKindByte() {
        assertEquals(AgentProvider.entries.map { it.wireId }, SessionLedgerCodec.providerOrder)
    }

    @Test fun theWorstCaseFitsBlockStoreAndSplitsIntoFourKilobyteEntries() {
        // Sixty spawns a day for forty days, under a few accounts.
        val entries = (0 until 2_400).associate { i ->
            val key = if (i % 2 == 0) "codex|${i * 3_600_000L}" else "claude|${i * 300_000L}"
            key to DeviceSessionLedgerState.Entry("uid-${i % 3}", now - i * 60_000L)
        }
        val bytes = SessionLedgerCodec.encode(DeviceSessionLedgerState(entries))
        val chunks = SessionLedgerCodec.chunks(bytes)
        assertTrue(chunks.size <= SessionLedgerCodec.MAX_CHUNKS)
        assertTrue(chunks.dropLast(1).all { it.size == SessionLedgerCodec.CHUNK_BYTES })
        assertEquals(bytes.toList(), chunks.flatMap { it.toList() })
        assertEquals(2_400, SessionLedgerCodec.decode(bytes).entries.size)
    }

    @Test fun anOversizedLedgerDropsTheOldestRecords() {
        val entries = (0 until 6_000).associate { i ->
            "claude|${i * 60_000L}" to DeviceSessionLedgerState.Entry("u", now - i * 60_000L)
        }
        val decoded = SessionLedgerCodec.decode(SessionLedgerCodec.encode(DeviceSessionLedgerState(entries)))
        assertTrue(decoded.entries.size < 6_000)
        assertTrue("claude|0" in decoded.entries) // the newest stays
        assertFalse("claude|${5_999 * 60_000L}" in decoded.entries)
    }

    @Test fun jsonCopyRoundTrips() {
        val ledger = DeviceSessionLedgerState(mapOf("cursor|1800000000000" to DeviceSessionLedgerState.Entry("u", now)))
        assertEquals(ledger, SessionLedgerJson.decode(SessionLedgerJson.encode(ledger)))
        assertEquals(null, SessionLedgerJson.decode("nope"))
    }

    private class Failing : DeviceSessionLedgerStorage {
        override suspend fun load(): DeviceSessionLedgerState = throw IOException("no play services")
        override suspend fun save(state: DeviceSessionLedgerState) = throw IOException("no play services")
    }

    @Test fun aFailingBlockStoreFallsBackToTheCopy() = runBlocking {
        val copy = DeviceSessionLedgerMemory()
        val combined = CombinedSessionLedger(primary = Failing(), fallback = copy)
        val ledger = DeviceSessionLedgerState().recording("claude|60000", "a", now)
        combined.save(ledger)
        assertEquals(ledger, copy.state)
        assertEquals(ledger, combined.load())
    }

    @Test fun bothCopiesAreWrittenAndReadMerged() = runBlocking {
        val blockStore = DeviceSessionLedgerMemory(DeviceSessionLedgerState().recording("claude|60000", "a", now))
        val copy = DeviceSessionLedgerMemory(DeviceSessionLedgerState().recording("claude|120000", "b", now))
        val combined = CombinedSessionLedger(blockStore, copy)
        assertEquals(setOf("claude|60000", "claude|120000"), combined.load().entries.keys)
        val next = combined.load().recording("claude|180000", "a", now)
        combined.save(next)
        assertEquals(next, blockStore.state)
        assertEquals(next, copy.state)
    }
}
