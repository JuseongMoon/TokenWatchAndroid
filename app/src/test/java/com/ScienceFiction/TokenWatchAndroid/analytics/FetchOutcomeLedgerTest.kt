package com.ScienceFiction.TokenWatchAndroid.analytics

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.ScienceFiction.TokenWatchAndroid.domain.AgentProvider
import com.ScienceFiction.TokenWatchAndroid.domain.AgentSnapshot
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * D021 / I006: each account's last fetch outcome on disk, compared on every accepted result, so the
 * same error is not counted again by every new process. Real DataStore files; a "restart" closes one
 * DataStore and opens a new one on the same file.
 */
class FetchOutcomeLedgerTest {
    private val directory: File = Files.createTempDirectory("fetch-outcomes").toFile()
    private val file = File(directory, "outcomes.preferences_pb")
    private var job = Job()
    private var ledger = FetchOutcomeLedger(open())
    private val agent = UUID.randomUUID()
    private val provider = AgentProvider.CLAUDE

    private fun open(): DataStore<Preferences> {
        job = Job()
        return PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { file }
    }

    /** The process ends and a new one reads the same file. */
    private fun restart() = runBlocking {
        job.cancelAndJoin()
        ledger = FetchOutcomeLedger(open())
    }

    @After fun tearDown() = runBlocking {
        job.cancelAndJoin()
        directory.deleteRecursively()
        Unit
    }

    private fun ok() = AgentSnapshot(emptyList(), null, Instant.EPOCH, error = null)
    private fun failed(reason: FetchErrorReason?) =
        AgentSnapshot(emptyList(), null, Instant.EPOCH, error = "message", errorReason = reason)

    private fun record(
        snapshot: AgentSnapshot,
        id: UUID = agent,
        collecting: Boolean = true,
        accepted: Boolean = true,
    ): String? = runBlocking {
        ledger.record(provider, id, snapshot, collecting) { accepted }?.let { event ->
            if (event is AnalyticsEvent.UsageFetchError) "error:${event.parameters["reason"]}" else event.eventName
        }
    }

    private fun stored(id: UUID = agent) = runBlocking { ledger.value(id) }

    @Test fun aRestartDoesNotCountTheSameErrorAgain() {
        assertEquals("error:network", record(failed(FetchErrorReason.NETWORK)))
        restart()
        assertNull(record(failed(FetchErrorReason.NETWORK)))
        restart()
        assertEquals("usage_fetch_recover", record(ok()))
        restart()
        assertNull(record(ok()))
    }

    @Test fun aDifferentReasonIsANewError() {
        assertEquals("error:http_5xx", record(failed(FetchErrorReason.HTTP_5XX)))
        assertEquals("error:rate_limit", record(failed(FetchErrorReason.RATE_LIMIT)))
        assertNull(record(failed(FetchErrorReason.RATE_LIMIT)))
        assertEquals("rate_limit", stored())
    }

    @Test fun aFirstSuccessIsOnlyABaselineAndAnErrorWithoutAReasonIsOther() {
        assertNull(record(ok()))
        assertEquals(FetchOutcomeLedger.OK, stored())
        assertEquals("error:other", record(failed(null)))
        assertEquals("other", stored())
    }

    @Test fun aResultThatNoLongerCountsChangesNothing() {
        record(failed(FetchErrorReason.NETWORK))

        assertNull(record(ok(), accepted = false))
        assertEquals("network", stored())
        assertNull(record(failed(FetchErrorReason.AUTH), id = UUID.randomUUID(), accepted = false))
    }

    @Test fun whileNotCollectingAnAccountIsOnlyPausedAndItsNextResultIsABaseline() {
        record(failed(FetchErrorReason.NETWORK))

        // Collection off: no comparison, no event, only "paused".
        assertNull(record(ok(), collecting = false))
        assertEquals(FetchOutcomeLedger.PAUSED, stored())
        assertNull(record(failed(FetchErrorReason.AUTH), collecting = false))
        assertEquals(FetchOutcomeLedger.PAUSED, stored())

        // Back on: the first result only sets the baseline, so no unpaired recovery is sent.
        assertNull(record(ok()))
        assertEquals(FetchOutcomeLedger.OK, stored())
        assertEquals("error:auth", record(failed(FetchErrorReason.AUTH)))
    }

    @Test fun anAccountWithNoResultWhileCollectionWasOffKeepsItsBaseline() {
        val other = UUID.randomUUID()
        record(failed(FetchErrorReason.NETWORK), id = other)
        record(ok(), collecting = false)

        assertEquals("usage_fetch_recover", record(ok(), id = other))
    }

    @Test fun demoEntryPausesStoredAccountsAndAccountsWithoutAResultYet() {
        val fresh = UUID.randomUUID()
        record(failed(FetchErrorReason.NETWORK))

        runBlocking { ledger.pauseAll(listOf(agent, fresh)) }

        assertEquals(FetchOutcomeLedger.PAUSED, stored())
        assertEquals(FetchOutcomeLedger.PAUSED, stored(fresh))
        // After demo mode the same error is not sent again, and the next one is a baseline too.
        assertNull(record(failed(FetchErrorReason.NETWORK)))
        assertNull(record(failed(FetchErrorReason.AUTH), id = fresh))
    }

    @Test fun aRemovedAccountIsForgotten() {
        record(failed(FetchErrorReason.NETWORK))

        runBlocking { ledger.forget(listOf(agent)) }

        assertNull(stored())
        assertEquals("error:network", record(failed(FetchErrorReason.NETWORK)))
    }

    @Test fun onlyEnumStringsAreStored() {
        val values = mutableSetOf<String?>()
        for (snapshot in listOf(ok(), failed(FetchErrorReason.CREDENTIAL), failed(null))) {
            record(snapshot)
            values += stored()
        }
        record(ok(), collecting = false)
        values += stored()

        val allowed = FetchErrorReason.entries.map { it.wireId } + FetchOutcomeLedger.OK + FetchOutcomeLedger.PAUSED
        values.forEach { assertEquals(true, it in allowed) }
    }
}
