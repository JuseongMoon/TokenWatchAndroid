package com.ScienceFiction.TokenWatchAndroid.monster

import com.ScienceFiction.TokenWatchAndroid.analytics.AnalyticsEvent
import com.ScienceFiction.TokenWatchAndroid.domain.Agent
import com.ScienceFiction.TokenWatchAndroid.domain.AgentProvider
import com.ScienceFiction.TokenWatchAndroid.domain.UsageStyle
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
}

/**
 * A callable fake server: queued responses per function name (a document GET answers under the last path
 * segment), every request's `data` recorded.
 */
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
        val body = request.body?.let { Buffer().also { buffer -> it.writeTo(buffer) }.readUtf8() } ?: "{}"
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

    /** A credit gauge: never a monster target. */
    private fun creditWindow() =
        UsageWindow("credits", 40.0, Instant.ofEpochMilli(clock + 86_400_000), WindowKind.WEEKLY, style = UsageStyle.CREDIT_GAUGE)

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
            windowLabel = "w", resetBucketMs = 0, drawAtMs = null, monsterId = "goblin", maxHp = 3, hp = 3,
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

    // MARK: Random spawns (one draw per agent every 20…40 minutes)

    /** Starts from a cached server config ([payload]), fresh so it is not fetched again. */
    private fun cachedConfig(payload: String) {
        preferences.state = MonsterPreferenceState(configPayload = payload, configFetchedAtMs = clock)
    }

    private val alwaysRandom = """{"spawn":{"random":{"chancePerSlot":1}}}"""

    @Test fun guestRandomWaitsTwentyToFortyMinutesThenDraws() = runBlocking {
        cachedConfig(alwaysRandom)
        val half = window(used = 40.0)
        snapshots = listOf(agent to listOf(half))
        val s = store()
        val start = clock
        s.observe(agent, listOf(half))
        assertTrue(s.state.value.ledger.encounters.isEmpty()) // a first sighting only sets the schedule
        val token = MonsterSessionKey.agentToken(agent.id)
        val due = s.state.value.ledger.nextDraw(token)!!
        assertTrue(due in start + 20 * 60_000L..start + 40 * 60_000L)
        clock = due - 1
        s.observe(agent, listOf(half))
        assertTrue(s.state.value.ledger.encounters.isEmpty())
        clock = due
        s.observe(agent, listOf(half))
        val e = s.state.value.ledger.encounters.single()
        assertEquals(MonsterEncounter.Source.RANDOM, e.source)
        assertEquals(due, e.drawAtMs)
        assertEquals(MonsterSessionKey.randomKey("guest", token, due), e.key)
        assertEquals(agent.id to half.label, e.agentId to e.displayLabel)
        assertEquals(clock + 60 * 60_000L, e.expiresAtMs)
        assertTrue(e.position in 0.1..0.9)
        assertEquals(listOf(e), s.encounters(agent.id, AgentProvider.CLAUDE, half))
        val next = s.state.value.ledger.nextDraw(token)!!
        assertTrue(next in due + 20 * 60_000L..due + 40 * 60_000L)
        assertTrue(functions.calls.isEmpty())
        // The same draw never spawns twice.
        s.observe(agent, listOf(half))
        assertEquals(1, s.state.value.ledger.encounters.size)
    }

    @Test fun eachAgentDrawsOnItsOwn() = runBlocking {
        cachedConfig(alwaysRandom)
        val second = Agent(AgentProvider.CLAUDE)
        val third = Agent(AgentProvider.CODEX)
        val half = window(used = 40.0)
        snapshots = listOf(agent to listOf(half), second to listOf(half), third to listOf(half))
        val s = store()
        s.observe(agent, listOf(half))
        assertEquals(3, s.state.value.ledger.randomSchedule.size)
        clock += 40 * 60_000L
        s.observe(agent, listOf(half))
        assertEquals(setOf(agent.id, second.id, third.id), s.state.value.ledger.encounters.mapNotNull { it.agentId }.toSet())
    }

    @Test fun onlyMaxAgentsPerUserAgentsDraw() = runBlocking {
        cachedConfig("""{"spawn":{"random":{"chancePerSlot":1,"maxAgentsPerUser":2}}}""")
        val half = window(used = 40.0)
        // An agent with nothing to draw on does not use up the allowance.
        snapshots = listOf(agent to listOf(creditWindow())) + List(3) { Agent(AgentProvider.CODEX) to listOf(half) }
        val s = store()
        s.observe(agent, emptyList())
        val drawing = snapshots.drop(1).take(2).map { MonsterSessionKey.agentToken(it.first.id) }.toSet()
        assertEquals(drawing, s.state.value.ledger.randomSchedule.keys)
    }

    @Test fun fullWindowJoinsTheDrawOnceItsCompletionMonsterIsDefeated() = runBlocking {
        cachedConfig(alwaysRandom)
        val w = window()
        snapshots = listOf(agent to listOf(w))
        val s = store()
        val token = MonsterSessionKey.agentToken(agent.id)
        s.observe(agent, listOf(w))
        val completion = s.state.value.ledger.encounters.single()
        assertEquals(MonsterEncounter.Source.COMPLETION, completion.source)
        assertNull(s.state.value.ledger.nextDraw(token)) // not a candidate while its completion monster lives
        assertEquals(MonsterStore.HitResult.Defeated, s.hit(completion.key)) // slime HP 1
        s.observe(agent, listOf(w))
        val due = s.state.value.ledger.nextDraw(token)!! // the schedule runs once it is defeated
        clock = due
        s.observe(agent, listOf(w))
        val random = s.state.value.ledger.encounters.first { it.source == MonsterEncounter.Source.RANDOM }
        assertEquals(agent.id to w.label, random.agentId to random.displayLabel)
    }

    @Test fun agentWithoutCandidateWindowIsSkipped() = runBlocking {
        cachedConfig(alwaysRandom)
        snapshots = listOf(agent to listOf(creditWindow())) // a credit gauge is never a target
        val s = store()
        s.observe(agent, emptyList())
        assertNull(s.state.value.ledger.nextDraw(MonsterSessionKey.agentToken(agent.id)))
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

    @Test fun randomSpawnSendsEachAgentsCandidateWindows() = runBlocking {
        val codex = Agent(AgentProvider.CODEX)
        snapshots = listOf(
            agent to listOf(window(used = 40.0), window(label = "Current session", used = 100.0)),
            codex to listOf(window(label = "추가 한도", used = 3.0)),
        )
        val s = store(terms = 2)
        repeat(2) { functions.respond("spawnEncounter", 200, """{"result":{"encounter":null,"nextDrawAtMs":${clock + 1_500_000}}}""") }
        s.observe(agent, emptyList())
        assertEquals(2, functions.count("spawnEncounter"))
        val (claudeSent, codexSent) = functions.calls.filter { it.first == "spawnEncounter" }.map { it.second }
        // No usage figure, no account information.
        assertEquals(setOf("source", "agentKey", "candidates", "platform", "appVersion", "spriteFormat"), claudeSent.keys)
        assertEquals(MonsterSessionKey.agentKey("u1", MonsterSessionKey.agentToken(agent.id)), claudeSent["agentKey"])
        assertEquals(MonsterSessionKey.agentKey("u1", MonsterSessionKey.agentToken(codex.id)), codexSent["agentKey"])
        // With no completion monster alive the 100% session window is a candidate too; the Codex label is the
        // language-free key label.
        assertEquals(
            listOf(
                mapOf("provider" to "claude", "windowLabel" to "Current week (all models)"),
                mapOf("provider" to "claude", "windowLabel" to "Current session"),
            ),
            claudeSent["candidates"],
        )
        assertEquals(listOf(mapOf("provider" to "codex", "windowLabel" to "Additional limit")), codexSent["candidates"])
        assertFalse(functions.rawBodies.any { it.contains("usedPercent") })
        // Nothing is asked again before the server's next draw time.
        s.observe(agent, emptyList())
        assertEquals(2, functions.count("spawnEncounter"))
    }

    @Test fun accountRandomFollowsTheServerSchedule() = runBlocking {
        val w = window(used = 40.0)
        snapshots = listOf(agent to listOf(w))
        val s = store(terms = 2)
        val token = MonsterSessionKey.agentToken(agent.id)
        val first = clock + 25 * 60_000L
        val key = MonsterSessionKey.randomKey("u1", MonsterSessionKey.agentKey("u1", token), first)
        functions.respond("spawnEncounter", 200, """{"result":{"encounter":null,"nextDrawAtMs":$first}}""")
        functions.respond(
            "spawnEncounter",
            200,
            """{"result":{"encounter":{"key":"$key","source":"random","provider":"claude","windowLabel":"Current week (all models)","drawAtMs":$first,"monsterId":"slime","maxHP":1,"state":"alive","spawnedAtMs":$first,"expiresAtMs":${first + 3_600_000}},"nextDrawAtMs":${first + 30 * 60_000L}}}""",
        )
        s.observe(agent, listOf(w)) // the first request only registers the agent
        assertEquals(1, functions.count("spawnEncounter"))
        assertEquals(first, s.state.value.ledger.nextDraw(token))
        clock = first - 1
        s.observe(agent, listOf(w))
        assertEquals(1, functions.count("spawnEncounter")) // not asked before it is due
        clock = first
        s.observe(agent, listOf(w))
        assertEquals(2, functions.count("spawnEncounter"))
        val shown = s.encounters(agent.id, AgentProvider.CLAUDE, w).single()
        assertEquals(Triple(key, "u1", first), Triple(shown.key, shown.ownerUid, shown.drawAtMs))
        assertEquals(first + 30 * 60_000L, s.state.value.ledger.nextDraw(token))
    }

    @Test fun aServerTimeAlreadyPastWaitsAMinute() = runBlocking {
        val w = window(used = 40.0)
        snapshots = listOf(agent to listOf(w))
        val s = store(terms = 2)
        // A device clock ahead of the server's.
        functions.respond("spawnEncounter", 200, """{"result":{"encounter":null,"nextDrawAtMs":${clock - 5_000}}}""")
        s.observe(agent, listOf(w))
        assertEquals(clock + 60_000L, s.state.value.ledger.nextDraw(MonsterSessionKey.agentToken(agent.id)))
    }

    @Test fun deviceScheduleHoldsAfterSwitchingAccounts() = runBlocking {
        // Another account just drew on this device: the new account waits for the same schedule.
        val w = window(used = 40.0)
        snapshots = listOf(agent to listOf(w))
        ledger.state = MonsterLedgerState().withNextDraw(clock + 10 * 60_000L, MonsterSessionKey.agentToken(agent.id), clock)
        val s = store(terms = 2)
        s.observe(agent, listOf(w))
        assertEquals(0, functions.count("spawnEncounter"))
    }

    @Test fun agentLimitWaitsAnHour() = runBlocking {
        val w = window(used = 40.0)
        snapshots = listOf(agent to listOf(w))
        val s = store(terms = 2)
        functions.respond("spawnEncounter", 400, """{"error":{"status":"RESOURCE_EXHAUSTED","details":{"code":"agent_limit"}}}""")
        s.observe(agent, listOf(w))
        clock += 3_599_999
        s.observe(agent, listOf(w))
        assertEquals(1, functions.count("spawnEncounter"))
        assertNull(s.state.value.ledger.nextDraw(MonsterSessionKey.agentToken(agent.id)))
        clock += 1
        s.observe(agent, listOf(w))
        assertEquals(2, functions.count("spawnEncounter"))
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

    @Test fun anExpiredDefeatKeepsTheStats() = runBlocking {
        val s = store(terms = 2)
        functions.respond("myRank", 200, """{"result":{"listed":true,"rank":4,"totalScore":120,"commonKills":3,"specialKills":1}}""")
        s.refreshGuild(LeaderboardScope.ALL_TIME)
        assertEquals(MonsterStats(120, 3, 1), s.state.value.stats)
        functions.respond("spawnEncounter", 200, encounterJson(completionKey))
        s.observe(agent, listOf(window()))
        functions.respond(
            "defeatEncounter",
            200,
            """{"result":{"encounter":{"key":"$completionKey","source":"completion","provider":"claude","windowLabel":"w","monsterId":"goblin","maxHP":3,"state":"expired","spawnedAtMs":1,"expiresAtMs":2}}}""",
        )
        repeat(3) {
            clock += 500
            s.hit(completionKey)
        }
        val e = s.state.value.ledger.encounters.first { it.key == completionKey }
        assertEquals(MonsterEncounter.State.EXPIRED, e.state)
        assertEquals(0, e.awardedScore)
        assertFalse(e.pendingDefeat)
        assertEquals(MonsterStats(120, 3, 1), s.state.value.stats)
    }

    @Test fun aRefusedGuestHistoryIsNotSentAgainButOfflineRetries() = runBlocking {
        val s = store(terms = 1)
        s.observe(agent, listOf(window()))
        s.hit(s.state.value.ledger.encounters[0].key)
        account.termsAcceptedVersion = 2
        s.syncAccount()
        // Offline: not settled, so the next observation tries again.
        functions.failTransport = true
        s.mergeGuestHistoryIfNeeded()
        functions.failTransport = false
        assertTrue(s.state.value.ledger.mergedUids.isEmpty())
        functions.respond("mergeGuestHistory", 400, """{"error":{"status":"INVALID_ARGUMENT","details":{"code":"invalid_argument"}}}""")
        s.mergeGuestHistoryIfNeeded()
        assertEquals(listOf("u1"), s.state.value.ledger.mergedUids)
        s.mergeGuestHistoryIfNeeded()
        assertEquals(1, functions.count("mergeGuestHistory"))
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

    @Test fun eachTabCachesItsListAndTheWeekTabRereadsWhenTheWeekTurns() = runBlocking {
        clock = 1_792_940_399_000 // Sun 2026-10-25 23:59:59 KST
        val s = store()
        val row = """[{"document":{"name":"x/leaderboard/a","fields":{"nickname":{"stringValue":"A"},"tag":{"stringValue":"0001"},"totalScore":{"integerValue":"9"},"weekScore":{"integerValue":"3"}}}}]"""
        functions.respond("documents:runQuery", 200, row)
        functions.respond("documents:runQuery", 200, row)
        functions.respond("documents:runQuery", 200, "[]")
        s.refreshGuild(LeaderboardScope.WEEK)
        s.refreshGuild(LeaderboardScope.ALL_TIME)
        assertEquals(3, s.state.value.leaderboard(LeaderboardScope.WEEK).single().score)
        assertEquals(9, s.state.value.leaderboard(LeaderboardScope.ALL_TIME).single().score)
        // Within five minutes both tabs are cached…
        s.refreshGuild(LeaderboardScope.WEEK)
        s.refreshGuild(LeaderboardScope.ALL_TIME)
        assertEquals(2, functions.count("documents:runQuery"))
        // …but the week tab reads again the second Monday 00:00 KST arrives.
        clock += 1_000
        s.refreshGuild(LeaderboardScope.WEEK)
        assertEquals(3, functions.count("documents:runQuery"))
        assertTrue(s.state.value.leaderboard(LeaderboardScope.WEEK).isEmpty())
        assertEquals(1, s.state.value.leaderboard(LeaderboardScope.ALL_TIME).size)
    }

    // MARK: Bestiary

    private fun killsDocument(uid: String, slime: Int) =
        """{"name":"projects/p/databases/(default)/documents/players/$uid","fields":{"stats":{"mapValue":{"fields":{""" +
            """"kills":{"mapValue":{"fields":{"slime":{"integerValue":"$slime"}}}}}}}}}"""

    @Test fun theBestiaryKeepsTheLastDefeatsAndNeverShowsAnotherAccounts() = runBlocking {
        val s = store(terms = 2)
        functions.respond("u1", 200, killsDocument("u1", 4))
        s.refreshBestiary()
        assertEquals(mapOf("slime" to 4), s.state.value.bestiaryKills)
        assertEquals(listOf("slime", "goblin", "skeleton", "orc", "mimic"), s.bestiaryRows()!!.map { it.id })
        // A failed re-read keeps what is on screen.
        s.refreshBestiary()
        assertEquals(mapOf("slime" to 4), s.state.value.bestiaryKills)
        assertFalse(s.state.value.bestiaryFailed)
        // Another account: the old defeats go at once, and its first failure offers a retry.
        account.uid = "u2"
        s.syncAccount()
        assertNull(s.bestiaryRows())
        s.refreshBestiary()
        assertTrue(s.state.value.bestiaryFailed)
        functions.respond("u2", 200, killsDocument("u2", 1))
        s.refreshBestiary()
        assertEquals(mapOf("slime" to 1), s.state.value.bestiaryKills)
        assertFalse(s.state.value.bestiaryFailed)
        // Deleting the account forgets them too.
        s.forgetAccount("u2")
        assertNull(s.state.value.bestiaryKills)
    }

    @Test fun theBestiaryAsksNothingInDemoOrSignedOut() = runBlocking {
        val s = store()
        s.refreshBestiary()
        demo = true
        account.uid = "u1"
        s.refreshBestiary()
        assertTrue(functions.calls.isEmpty())
        assertNull(s.bestiaryRows())
    }

    // MARK: Drawing (pure)

    @Test fun aRandomGhostLeavesTheListAfterThreeMinutes() {
        val w = window(used = 40.0)
        val ghost = MonsterEncounter(
            key = "r", ownerUid = null, source = MonsterEncounter.Source.RANDOM, provider = "claude",
            windowLabel = w.label, resetBucketMs = null, drawAtMs = 0, monsterId = "slime", maxHp = 1, hp = 0,
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
