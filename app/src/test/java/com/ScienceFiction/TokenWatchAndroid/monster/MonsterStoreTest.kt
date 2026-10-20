package com.ScienceFiction.TokenWatchAndroid.monster

import com.ScienceFiction.TokenWatchAndroid.analytics.AnalyticsEvent
import com.ScienceFiction.TokenWatchAndroid.domain.Agent
import com.ScienceFiction.TokenWatchAndroid.domain.AgentProvider
import com.ScienceFiction.TokenWatchAndroid.domain.UsageWindow
import com.ScienceFiction.TokenWatchAndroid.domain.WindowKind
import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkResponse
import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkTransport
import com.squareup.moshi.Moshi
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeAccount : MonsterAccount {
    override var uid: String? = null
    override var termsAcceptedVersion: Int? = null

    override fun markTermsAccepted(version: Int) {
        val current = termsAcceptedVersion ?: return
        if (current < version) termsAcceptedVersion = version
    }
}

private class MemoryPreferences(var state: MonsterPreferenceState = MonsterPreferenceState()) : MonsterPreferences {
    override suspend fun loadPreferences() = state
    override suspend fun saveConfig(payload: String) {
        state = state.copy(configPayload = payload)
    }
    override suspend fun saveConfigFetchedAt(fetchedAtMs: Long) {
        state = state.copy(configFetchedAtMs = fetchedAtMs)
    }
    override suspend fun saveBlocked(uids: Set<String>) {
        state = state.copy(blockedUids = uids)
    }
}

/** A callable fake server: queued responses per function name, every request's `data` recorded. */
private class FakeFunctions : NetworkTransport {
    val responses = mutableMapOf<String, ArrayDeque<NetworkResponse>>()
    var failTransport = false
    val calls = mutableListOf<Pair<String, Map<*, *>>>()
    val rawBodies = mutableListOf<String>()

    fun respond(name: String, status: Int, body: String) {
        responses.getOrPut(name) { ArrayDeque() } += NetworkResponse(status, emptyMap(), body.toByteArray())
    }

    override suspend fun execute(request: Request): NetworkResponse {
        if (failTransport) throw IOException("offline")
        val name = request.url.pathSegments.last()
        val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
        rawBodies += body
        val json = Moshi.Builder().build().adapter(Any::class.java).fromJson(body) as Map<*, *>
        calls += name to (json["data"] as? Map<*, *>).orEmpty()
        return responses[name]?.removeFirstOrNull() ?: NetworkResponse(500, emptyMap(), "{}".toByteArray())
    }

    fun count(name: String) = calls.count { it.first == name }
    fun data(name: String) = calls.first { it.first == name }.second
}

class MonsterStoreTest {
    private var clock = 1_800_000_000_000L
    private val agent = Agent(AgentProvider.CLAUDE)
    private val account = FakeAccount()
    private val functions = FakeFunctions()
    private val ledger = MonsterLedgerMemory()
    private val session = DeviceSessionLedgerMemory()
    private val preferences = MemoryPreferences()
    private val events = mutableListOf<AnalyticsEvent>()
    private var foreground = true
    private var critters = true
    private var demo = false
    private var snapshots: List<Pair<Agent, List<UsageWindow>>> = listOf(agent to emptyList())

    /** [terms]: null = guest; otherwise signed in as u1 with that terms version. */
    private fun store(terms: Int? = null, withApi: Boolean = true): MonsterStore = runBlocking {
        if (terms != null) {
            account.uid = "u1"
            account.termsAcceptedVersion = terms
        }
        MonsterStore(
            account = account,
            api = if (withApi) {
                MonsterApiClient("demo-proj", null, { if (account.uid == null) null else "token" }, functions, "2.0.0")
            } else {
                null
            },
            env = MonsterStore.Environment(
                isDemo = { demo },
                crittersEnabled = { critters },
                isForeground = { foreground },
                nowMs = { clock },
                appVersion = "2.0.0",
                snapshots = { snapshots },
            ),
            storage = ledger,
            sessionStorage = session,
            preferences = preferences,
            // Work launched by a hit (the defeat report, the save) runs right away.
            scope = CoroutineScope(Dispatchers.Unconfined),
            log = { events += it },
        ).also { it.load() }
    }

    private fun window(label: String = "Current week (all models)", used: Double = 100.0) =
        UsageWindow(label, used, Instant.ofEpochMilli(clock + 86_400_000), WindowKind.WEEKLY)

    private val completionKey = MonsterSessionKey.completionKey("u1", AgentProvider.CLAUDE, 1_800_086_400_000)

    private fun encounterJson(key: String, monster: String = "goblin", state: String = "alive") =
        """{"result":{"encounter":{"key":"$key","source":"completion","provider":"claude","windowLabel":"Current week (all models)","resetBucketMs":1800086400000,"monsterId":"$monster","maxHP":3,"state":"$state","spawnedAtMs":1800000000000,"expiresAtMs":1800086400000}}}"""

    // MARK: Guest

    @Test fun guestCompletionSpawnsOnce() = runBlocking {
        val s = store()
        val w = window()
        s.observe(agent, listOf(w))
        s.observe(agent, listOf(w))
        val shown = s.encounters(agent.id, AgentProvider.CLAUDE, w)
        assertEquals(1, shown.size)
        assertNull(shown.first().ownerUid)
        assertEquals("slime", shown.first().monsterId) // the first three are common
        assertTrue(functions.calls.isEmpty()) // a guest never calls the server
        assertTrue(ledger.saves > 0)
        assertEquals("monster_spawn", events.single().eventName)
    }

    @Test fun noSpawnInBackgroundWithCrittersOffOrInDemo() = runBlocking {
        for ((fg, on, isDemo) in listOf(Triple(false, true, false), Triple(true, false, false), Triple(true, true, true))) {
            foreground = fg
            critters = on
            demo = isDemo
            val s = store()
            s.observe(agent, listOf(window()))
            assertTrue(s.state.value.ledger.encounters.isEmpty())
        }
    }

    @Test fun backgroundRefreshOnlyExpires() = runBlocking {
        val s = store()
        s.observe(agent, listOf(window()))
        assertTrue(s.state.value.ledger.encounters.single().isAlive)
        foreground = false
        clock += 86_400_000 // the reset has passed
        s.observe(agent, emptyList())
        assertEquals(MonsterEncounter.State.EXPIRED, s.state.value.ledger.encounters.single().state)
    }

    @Test fun demoShowsMemoryOnlySampleMonster() = runBlocking {
        demo = true
        val s = store()
        s.syncAccount()
        val w = window()
        val shown = s.encounters(agent.id, AgentProvider.CLAUDE, w)
        assertEquals(1, shown.size)
        val key = shown[0].key
        if (shown[0].maxHp > 1) {
            assertEquals(MonsterStore.HitResult.Damaged(shown[0].maxHp - 1), s.hit(key))
        } else {
            assertEquals(MonsterStore.HitResult.Defeated, s.hit(key))
        }
        assertEquals(0, ledger.saves)
        assertTrue(functions.calls.isEmpty())
        assertTrue(events.isEmpty())
    }

    @Test fun hitsAreDebouncedAndDefeatCountsForGuest() = runBlocking {
        val s = store()
        val w = window()
        s.observe(agent, listOf(w))
        val key = s.state.value.ledger.encounters[0].key
        assertEquals(MonsterStore.HitResult.Defeated, s.hit(key)) // slime HP 1
        assertEquals(MonsterStore.HitResult.Ignored, s.hit(key)) // already defeated
        assertEquals(1, s.state.value.ledger.guest.kills["slime"])
        assertEquals(10, s.state.value.ledger.encounters[0].awardedScore)
        // A defeated monster keeps showing, with its halo, until the reset.
        assertEquals(MonsterEncounter.State.DEFEATED, s.encounters(agent.id, AgentProvider.CLAUDE, w).first().state)
        assertEquals(mapOf("monster_id" to "slime", "hp" to "1", "score" to "10", "source" to "completion"), events.last().parameters)
    }

    @Test fun debounceIgnoresRapidTaps() = runBlocking {
        // An HP 3 monster put in directly, to look at the tap interval.
        val goblin = MonsterEncounter(
            key = "g", ownerUid = null, source = MonsterEncounter.Source.COMPLETION, provider = "claude",
            windowLabel = "w", resetBucketMs = 0, slotStartMs = null, monsterId = "goblin", maxHp = 3, hp = 3,
            state = MonsterEncounter.State.ALIVE, spawnedAtMs = clock, expiresAtMs = clock + 1_000_000, position = 0.5,
        )
        ledger.state = MonsterLedgerState(encounters = listOf(goblin))
        val s = store(withApi = false)
        assertEquals(MonsterStore.HitResult.Damaged(2), s.hit("g"))
        clock += 50
        assertEquals(MonsterStore.HitResult.Ignored, s.hit("g"))
        clock += 200
        assertEquals(MonsterStore.HitResult.Damaged(1), s.hit("g"))
        clock += 200
        assertEquals(MonsterStore.HitResult.Defeated, s.hit("g"))
        assertTrue(s.state.value.ledger.guest.nextCommonGuaranteed) // a special defeat → the next is common
        assertEquals(clock, s.state.value.lastHitAtMs["g"])
    }

    @Test fun guestRandomSpawnLandsOnACandidateWindow() = runBlocking {
        preferences.state = MonsterPreferenceState(
            configPayload = """{"spawn":{"random":{"chancePerSlot":1}}}""",
            configFetchedAtMs = clock, // fresh: no config request
        )
        val half = window(used = 40.0)
        snapshots = listOf(agent to listOf(half))
        val s = store()
        s.observe(agent, listOf(half))
        val e = s.state.value.ledger.encounters.single()
        assertEquals(MonsterEncounter.Source.RANDOM, e.source)
        assertEquals(agent.id to half.label, e.agentId to e.displayLabel)
        assertEquals(clock + 60 * 60_000L, e.expiresAtMs)
        assertTrue(e.position in 0.1..0.9)
        assertEquals(listOf(e), s.encounters(agent.id, AgentProvider.CLAUDE, half))
        // The same slot never draws twice.
        s.observe(agent, listOf(half))
        assertEquals(1, s.state.value.ledger.encounters.size)
    }

    // MARK: Account

    @Test fun consentedAccountAsksTheServerOncePerSession() = runBlocking {
        val s = store(terms = 2)
        assertEquals("u1", s.activeOwner)
        val w = window()
        functions.respond("spawnEncounter", 200, encounterJson(completionKey))
        s.observe(agent, listOf(w))
        s.observe(agent, listOf(w))
        assertEquals(1, functions.count("spawnEncounter"))
        val sent = functions.data("spawnEncounter")
        // No usage figure, no account information.
        assertEquals(
            setOf("source", "provider", "windowLabel", "periodSec", "resetBucketMs", "resetsAtMs", "platform", "appVersion", "spriteFormat"),
            sent.keys,
        )
        assertEquals(604_800L, (sent["periodSec"] as Number).toLong())
        assertEquals(1_800_086_400_000L, (sent["resetBucketMs"] as Number).toLong())
        assertEquals("android", sent["platform"])
        assertEquals(1L, (sent["spriteFormat"] as Number).toLong())
        val shown = s.encounters(agent.id, AgentProvider.CLAUDE, w)
        assertEquals("goblin" to "u1", shown.first().monsterId to shown.first().ownerUid)
        // The device session ledger records this session as u1's.
        assertEquals("u1", session.state.entries["claude|1800086400000"]?.uid)
    }

    @Test fun randomSpawnSendsOnlyCandidateWindows() = runBlocking {
        val codex = Agent(AgentProvider.CODEX)
        snapshots = listOf(
            agent to listOf(window(used = 40.0), window(label = "Current session", used = 100.0)),
            codex to listOf(window(label = "추가 한도", used = 3.0)),
        )
        val s = store(terms = 2)
        functions.respond("spawnEncounter", 200, """{"result":{"encounter":null}}""")
        s.observe(agent, emptyList())
        val sent = functions.data("spawnEncounter")
        assertEquals(setOf("source", "slotStartMs", "candidates", "platform", "appVersion", "spriteFormat"), sent.keys)
        assertEquals(1_800_000_000_000L, (sent["slotStartMs"] as Number).toLong())
        // The completed session window is the completion monster's; the Codex label is the language-free key label.
        assertEquals(
            listOf(
                mapOf("provider" to "claude", "windowLabel" to "Current week (all models)"),
                mapOf("provider" to "codex", "windowLabel" to "Additional limit"),
            ),
            sent["candidates"],
        )
        assertFalse(functions.rawBodies.single().contains("usedPercent"))
        // "Nothing appeared" settles the slot for this run.
        s.observe(agent, emptyList())
        assertEquals(1, functions.count("spawnEncounter"))
    }

    @Test fun sessionLedgerBlocksAnotherAccount() = runBlocking {
        val s = store(terms = 2)
        session.state = DeviceSessionLedgerState(mapOf("claude|1800086400000" to DeviceSessionLedgerState.Entry("someone-else", clock)))
        s.observe(agent, listOf(window()))
        assertEquals(0, functions.count("spawnEncounter"))
    }

    @Test fun accountWithoutConsentStaysGuest() = runBlocking {
        val s = store(terms = 1)
        assertNull(s.activeOwner)
        assertTrue(s.needsConsent)
        assertTrue(s.state.value.needsConsent)
        s.observe(agent, listOf(window()))
        assertEquals(0, functions.count("spawnEncounter"))
        assertNull(s.state.value.ledger.encounters.first().ownerUid)
    }

    @Test fun consentRequiredFromServerIsRemembered() = runBlocking {
        val s = store(terms = 2)
        functions.respond(
            "spawnEncounter",
            400,
            """{"error":{"status":"FAILED_PRECONDITION","details":{"code":"consent_required"}}}""",
        )
        s.observe(agent, listOf(window()))
        assertTrue(s.state.value.consentRequested)
        assertTrue(s.state.value.ledger.encounters.isEmpty())
    }

    @Test fun refusalsAreNotAskedAgainButFailuresRetryLater() = runBlocking {
        val s = store(terms = 2)
        functions.respond("spawnEncounter", 400, """{"error":{"status":"INVALID_ARGUMENT","details":{"code":"invalid_reset"}}}""")
        s.observe(agent, listOf(window()))
        clock += 3_600_000
        s.observe(agent, listOf(window().copy(resetsAt = Instant.ofEpochMilli(1_800_086_400_000))))
        assertEquals(1, functions.count("spawnEncounter"))

        // A transport failure asks again after two minutes.
        val other = store(terms = 2)
        functions.calls.clear()
        val w = window(label = "Current session").copy(resetsAt = Instant.ofEpochMilli(clock + 18_000_000))
        functions.failTransport = true
        other.observe(agent, listOf(w))
        functions.failTransport = false
        clock += 60_000
        other.observe(agent, listOf(w))
        assertEquals(0, functions.count("spawnEncounter"))
        clock += 60_001
        other.observe(agent, listOf(w))
        assertEquals(1, functions.count("spawnEncounter"))
    }

    @Test fun acceptConsentUpdatesProfileAndMergesGuestHistory() = runBlocking {
        val s = store(terms = 1)
        // Defeat one slime as a guest.
        s.observe(agent, listOf(window()))
        s.hit(s.state.value.ledger.encounters[0].key)
        functions.respond("acceptTerms", 200, """{"result":{"termsAcceptedVersion":2}}""")
        functions.respond("mergeGuestHistory", 200, """{"result":{"merged":true}}""")
        assertTrue(s.acceptConsent())
        assertEquals(2, account.termsAcceptedVersion)
        assertEquals("u1", s.activeOwner)
        assertEquals("u1", s.state.value.owner)
        assertEquals(mapOf("version" to 2.0), functions.data("acceptTerms"))
        val kills = functions.data("mergeGuestHistory")["kills"] as Map<*, *>
        assertEquals(mapOf("slime" to 1.0), kills)
        assertEquals(listOf("u1"), s.state.value.ledger.mergedUids)
        // The guest's monsters no longer show for the account.
        assertTrue(s.encounters(agent.id, AgentProvider.CLAUDE, window()).isEmpty())
    }

    @Test fun defeatStaysPendingWhileOffline() = runBlocking {
        val s = store(terms = 2)
        functions.respond("spawnEncounter", 200, encounterJson(completionKey))
        s.observe(agent, listOf(window()))
        repeat(3) {
            clock += 500
            s.hit(completionKey)
        }
        functions.failTransport = true
        s.sendDefeat(completionKey)
        assertEquals(true, s.state.value.ledger.encounters.first { it.key == completionKey }.pendingDefeat)
        functions.failTransport = false
        functions.respond(
            "defeatEncounter",
            200,
            """{"result":{"encounter":{"key":"$completionKey","source":"completion","provider":"claude","windowLabel":"w","monsterId":"goblin","maxHP":3,"state":"defeated","spawnedAtMs":1,"expiresAtMs":2,"awardedScore":50},"stats":{"totalScore":50,"commonKills":0,"specialKills":1}}}""",
        )
        s.sendDefeat(completionKey)
        val e = s.state.value.ledger.encounters.first { it.key == completionKey }
        assertFalse(e.pendingDefeat)
        assertEquals(50, e.awardedScore)
        assertEquals(MonsterStats(50, 0, 1), s.state.value.stats)
        assertEquals(setOf("key", "defeatedAtMs", "platform", "appVersion"), functions.data("defeatEncounter").keys)
    }

    @Test fun forgettingAnAccountRemovesItsMonsters() = runBlocking {
        val s = store(terms = 2)
        functions.respond("spawnEncounter", 200, encounterJson(completionKey))
        s.observe(agent, listOf(window()))
        assertTrue(s.state.value.ledger.encounters.isNotEmpty())
        s.forgetAccount("u1")
        assertTrue(s.state.value.ledger.encounters.isEmpty())
        // The device session ledger stays.
        assertEquals("u1", session.state.entries["claude|1800086400000"]?.uid)
    }

    @Test fun theLedgerSurvivesARestart() = runBlocking {
        store().observe(agent, listOf(window()))
        val restored = store()
        assertEquals(1, restored.state.value.ledger.encounters.size)
        assertEquals(ledger.state?.deviceSalt, restored.state.value.ledger.deviceSalt)
    }

    @Test fun blockingHidesFromTheLeaderboardAndPersists() = runBlocking {
        val s = store()
        s.block("bad")
        assertEquals(setOf("bad"), preferences.state.blockedUids)
        assertEquals(setOf("bad"), store().state.value.blockedUids)
    }

    // MARK: Drawing (pure)

    @Test fun aRandomGhostLeavesTheListAfterThreeMinutes() {
        val w = window(used = 40.0)
        val ghost = MonsterEncounter(
            key = "r", ownerUid = null, source = MonsterEncounter.Source.RANDOM, provider = "claude",
            windowLabel = w.label, resetBucketMs = null, slotStartMs = 0, monsterId = "slime", maxHp = 1, hp = 0,
            state = MonsterEncounter.State.DEFEATED, spawnedAtMs = clock, expiresAtMs = clock + 3_600_000,
            defeatedAtMs = clock, position = 0.5,
        )
        val state = MonsterState(ledger = MonsterLedgerState(encounters = listOf(ghost)))
        assertEquals(1, MonsterStore.visible(state, agent.id, AgentProvider.CLAUDE, w, clock + 180_000).size)
        assertEquals(0, MonsterStore.visible(state, agent.id, AgentProvider.CLAUDE, w, clock + 180_001).size)
        // Another owner's monster never shows.
        assertEquals(0, MonsterStore.visible(state.copy(owner = "u1"), agent.id, AgentProvider.CLAUDE, w, clock).size)
    }
}
