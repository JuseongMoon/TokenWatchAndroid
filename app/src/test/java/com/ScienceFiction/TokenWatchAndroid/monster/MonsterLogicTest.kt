package com.ScienceFiction.TokenWatchAndroid.monster

import com.ScienceFiction.TokenWatchAndroid.domain.AgentProvider
import com.ScienceFiction.TokenWatchAndroid.domain.UsageStyle
import com.ScienceFiction.TokenWatchAndroid.domain.UsageWindow
import com.ScienceFiction.TokenWatchAndroid.domain.WindowKind
import com.ScienceFiction.TokenWatchAndroid.ui.components.PixelSprite
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The monster (V2) pure logic, ported from iOS `MonsterLogicTests`. Values both platforms must agree on
 * are pinned with the iOS constants: session keys (300 s buckets, Copilot's UTC midnight, the Codex label
 * constant, the period table), slots and key hashes, the deterministic draw, the gray tone, the KST day and
 * the sprite file's SHA-256.
 */
class MonsterLogicTest {
    // MARK: Session keys

    @Test fun resetBucketRoundsToFiveMinutes() {
        assertEquals(1_800_000_000_000, MonsterSessionKey.resetBucketMs(1_800_000_149_999))
        assertEquals(1_800_000_300_000, MonsterSessionKey.resetBucketMs(1_800_000_150_000))
        assertEquals(1_800_000_000_000, MonsterSessionKey.resetBucketMs(1_799_999_999_400))
    }

    @Test fun copilotUsesUtcMidnightOfTheLocalDate() {
        // "2026-11-01" parsed as midnight on a Seoul device → the key uses 2026-11-01T00:00Z.
        val seoul = ZoneId.of("Asia/Seoul")
        val local = LocalDate.of(2026, 11, 1).atStartOfDay(seoul).toInstant()
        assertEquals(1_793_491_200_000, MonsterSessionKey.keyResetsAtMs(AgentProvider.COPILOT, local, seoul))
        // Other providers keep the instant.
        assertEquals(local.toEpochMilli(), MonsterSessionKey.keyResetsAtMs(AgentProvider.CLAUDE, local, seoul))
    }

    @Test fun codexFallbackLabelIsLanguageFree() {
        assertEquals("Additional limit", MonsterSessionKey.keyLabel(AgentProvider.CODEX, "추가 한도"))
        assertEquals("Additional limit", MonsterSessionKey.keyLabel(AgentProvider.CODEX, "Additional limit"))
        assertEquals("GPT-5 Pro", MonsterSessionKey.keyLabel(AgentProvider.CODEX, "GPT-5 Pro"))
        assertEquals("추가 한도", MonsterSessionKey.keyLabel(AgentProvider.CLAUDE, "추가 한도"))
    }

    /** The period table — the same values as the iOS test. */
    @Test fun periodSecondsTable() {
        val month = 2_678_400L
        fun period(provider: AgentProvider, label: String, seconds: Double? = null, kind: WindowKind = WindowKind.WEEKLY) =
            MonsterSessionKey.periodSec(provider, label, seconds, kind)
        assertEquals(18_000L, period(AgentProvider.CLAUDE, "Current session", kind = WindowKind.SESSION))
        assertEquals(604_800L, period(AgentProvider.CLAUDE, "Current week (all models)"))
        assertEquals(604_800L, period(AgentProvider.CODEX, "Current week", seconds = 604_800.4))
        assertEquals(month, period(AgentProvider.COPILOT, "Premium requests"))
        assertEquals(month, period(AgentProvider.COPILOT, "Something new"))
        assertEquals(month, period(AgentProvider.KIMI, "Current month"))
        assertEquals(month, period(AgentProvider.KIMI, "Current month (Code)"))
        assertEquals(604_800L, period(AgentProvider.KIMI, "Current week"))
        assertEquals(month, period(AgentProvider.ELEVENLABS, "Monthly characters"))
        // A Cursor billing cycle comes through windowSeconds.
        assertEquals(2_592_000L, period(AgentProvider.CURSOR, "Cursor models", seconds = 2_592_000.0))
    }

    @Test fun slotsAndKeys() {
        assertEquals(1_800_000_000_000, MonsterSessionKey.slotStartMs(1_800_003_599_999, 60))
        assertEquals("claude|5", MonsterSessionKey.completionLedgerKey(AgentProvider.CLAUDE, 5))
        assertEquals("rand|7", MonsterSessionKey.randomLedgerKey(7))
        // The server's formula: sha256hex("{uid}|{provider}|{bucket}") and sha256hex("{uid}|rand|{slot}").
        assertEquals(
            "55d18a0162ca3f99e368ccdaff5a5437bca0748a731fb4edb955bbbadbc95f92",
            MonsterSessionKey.completionKey("u1", AgentProvider.CLAUDE, 1_800_000_000_000),
        )
        assertEquals(
            "2d448c530730f9dd58d1c359da42c4d7cacd46cb0305d136809704625c39196a",
            MonsterSessionKey.randomKey("u1", 1_800_000_000_000),
        )
        assertTrue(MonsterSessionKey.randomKey("u1", 1) != MonsterSessionKey.randomKey("u2", 1))
        // Every provider keys by its wire id, which both apps share.
        assertEquals(
            listOf("claude", "codex", "copilot", "grok", "cursor", "kimi", "openrouter", "deepseek", "poe", "elevenlabs"),
            AgentProvider.entries.map { it.wireId },
        )
    }

    @Test fun epochMillisRoundLikeIos() {
        assertEquals(1_000L, MonsterSessionKey.epochMs(Instant.ofEpochSecond(1, 499_999)))
        assertEquals(1_001L, MonsterSessionKey.epochMs(Instant.ofEpochSecond(1, 500_000)))
    }

    @Test fun eligibility() {
        val now = Instant.ofEpochSecond(1_800_000_000)
        val future = now.plusSeconds(3_600)
        val full = UsageWindow("w", 99.5, future, WindowKind.WEEKLY)
        val half = UsageWindow("w", 40.0, future, WindowKind.WEEKLY)
        val past = UsageWindow("w", 100.0, now.minusSeconds(1), WindowKind.WEEKLY)
        val noReset = UsageWindow("w", 100.0, null, WindowKind.WEEKLY)
        val credit = UsageWindow("w", 100.0, future, WindowKind.WEEKLY, style = UsageStyle.CREDIT_GAUGE)
        assertTrue(MonsterEligibility.isCompleted(full, now))
        assertFalse(MonsterEligibility.isCompleted(half, now))
        assertFalse(MonsterEligibility.isTarget(past, now) || MonsterEligibility.isTarget(noReset, now))
        assertFalse(MonsterEligibility.isTarget(credit, now))
        assertTrue(MonsterEligibility.isRandomCandidate(half, 0.0, now))
        assertFalse(MonsterEligibility.isRandomCandidate(half, 50.0, now))
        // A completed window belongs to its completion monster.
        assertFalse(MonsterEligibility.isRandomCandidate(full, 0.0, now))
    }

    // MARK: Config

    @Test fun configEnvelopeAndDefaults() {
        val payload = """{"balanceVersion":7,"spawn":{"random":{"chancePerSlot":1.5,"slotMinutes":30}},"dailySpawnCap":10,"future":1}"""
        val escaped = payload.replace("\"", "\\\"")
        val c = MonsterConfigDecoder.decodeEnvelope("""{"fields":{"payload":{"stringValue":"$escaped"}}}""")!!
        assertEquals(7, c.balanceVersion)
        assertEquals(1.0, c.random.chancePerSlot, 0.0) // clamped to 0…1
        assertEquals(30, c.random.slotMinutes)
        assertEquals(60, c.random.lifetimeMinutes) // missing fields keep the defaults
        assertEquals(10, c.dailySpawnCap)
        assertEquals(MonsterConfig.BUNDLED_DEFAULT.catalog, c.catalog)
        assertNull(MonsterConfigDecoder.decodePayload("[1]"))
    }

    @Test fun catalogDropsBrokenEntries() {
        val payload = """{"catalog":[{"id":"bat","tier":"special","hp":2,"score":30,"weight":3,"released":false,"minAppVersion":"9.0"},{"id":"","hp":1,"score":1},{"id":"x","hp":0,"score":1}]}"""
        val c = MonsterConfigDecoder.decodePayload(payload)!!
        assertEquals(listOf("bat"), c.catalog.map { it.id })
        assertFalse(c.catalog[0].released)
        assertEquals("9.0", c.catalog[0].minAppVersion)
    }

    @Test fun bundledDefaultsMatchTheServerSeed() {
        val c = MonsterConfig.BUNDLED_DEFAULT
        assertEquals(listOf("slime", "goblin", "skeleton", "orc", "mimic"), c.catalog.map { it.id })
        assertEquals(listOf(90.0, 6.0, 2.5, 1.0, 0.5), c.catalog.map { it.weight })
        assertEquals(listOf(1, 3, 5, 8, 12), c.catalog.map { it.hp })
        assertEquals(listOf(10, 50, 100, 190, 350), c.catalog.map { it.score })
        assertEquals(0.08, c.random.chancePerSlot, 0.0)
        assertEquals(listOf(60, 60, 3), listOf(c.random.slotMinutes, c.random.lifetimeMinutes, c.random.maxAlivePerUser))
        assertEquals(listOf(3, 120, 60), listOf(c.guaranteedSlimeFirstN, c.tapDebounceMs, c.dailySpawnCap))
    }

    @Test fun appVersionCompare() {
        assertTrue(AppVersionCompare.isAtLeast("1.10", "1.4.0"))
        assertTrue(AppVersionCompare.isAtLeast("1.4", "1.4.0"))
        assertFalse(AppVersionCompare.isAtLeast("1.3.9", "1.4"))
    }

    // MARK: Sprites

    /** Unit tests run from the module directory; AssetManager is not available on the JVM. */
    private val spriteFile = File("src/main/assets/monsters-v1.json").readBytes()

    @Test fun bundledSpritesMatchParityHash() {
        // The same constant is asserted by the iOS tests. Changing the file changes both.
        val digest = MessageDigest.getInstance("SHA-256").digest(spriteFile).joinToString("") { "%02x".format(it) }
        assertEquals("6c33cfb59dbcec67a0282d942a60d26eb2d5fde1adb1e05f12fd585b81ebbd06", digest)
        assertEquals("monsters-v1.json", MonsterSpriteDecoder.BUNDLED_FILE)
        val set = MonsterSpriteSet.fromFile(spriteFile)
        val sprites = set.bundled
        assertEquals(
            setOf(
                "goblin", "skeleton", "orc", "mimic", "fx-sword", "fx-halo",
                "slime-death", "slime-sky-death", "goblin-death", "skeleton-death", "orc-death", "mimic-death",
            ),
            sprites.keys,
        )
        for (id in listOf("goblin", "skeleton", "orc", "mimic")) {
            val s = sprites.getValue(id)
            val death = sprites.getValue("$id-death")
            assertEquals(2, s.frames.size)
            assertEquals(8, s.rows)
            // The death animation is the monster's size (it continues in place), four frames.
            assertEquals(4, death.frames.size)
            assertEquals(s.rows to s.cols, death.rows to death.cols)
        }
        val slimeDeath = sprites.getValue("slime-death")
        assertEquals(PixelSprite.Slime.rows to PixelSprite.Slime.columns, slimeDeath.rows to slimeDeath.cols)
        assertNotNull(set.death("slime", sessionWindow = true))
        assertEquals(sprites["slime-sky-death"], set.death("slime", sessionWindow = true))
        assertEquals(sprites["slime-death"], set.death("slime", sessionWindow = false))
        assertNull(set.death("pumpkin", sessionWindow = false))
        assertNotNull(set.sword)
        assertNotNull(set.halo)
    }

    @Test fun spriteLookupFallsBackToSlime() {
        val set = MonsterSpriteSet.fromFile(spriteFile)
        assertNull(set.body("slime", MonsterConfig.BUNDLED_DEFAULT.entry("slime")))
        assertNull(set.body("pumpkin", null)) // unknown id → slime
        assertEquals(set.bundled["orc"], set.body("orc", MonsterConfig.BUNDLED_DEFAULT.entry("orc")))
        val inline = MonsterSpriteData(1, 1, listOf(listOf(listOf(1))), mapOf(1 to 0xFF0000))
        val event = MonsterCatalogEntry("pumpkin", "event", 2, 30, 1.0, inlineSprite = inline)
        assertEquals(inline, set.body("pumpkin", event))
    }

    @Test fun inlineSpriteLimits() {
        val ok = mapOf("cols" to 2.0, "rows" to 1.0, "frames" to listOf(listOf(listOf(1.0, 0.0))), "palette" to mapOf("1" to "#FF0000"))
        assertNotNull(MonsterSpriteDecoder.decodeInline(ok))
        assertNull(MonsterSpriteDecoder.decodeInline(ok + ("cols" to 3.0)))
        val tooWide = mapOf("cols" to 25.0, "rows" to 1.0, "frames" to listOf(listOf(List(25) { 0.0 })), "palette" to emptyMap<String, String>())
        assertNull(MonsterSpriteDecoder.decodeInline(tooWide))
        val missingColor = mapOf("cols" to 1.0, "rows" to 1.0, "frames" to listOf(listOf(listOf(2.0))), "palette" to mapOf("1" to "#FF0000"))
        assertNull(MonsterSpriteDecoder.decodeInline(missingColor))
        val fiveFrames = mapOf("cols" to 1.0, "rows" to 1.0, "frames" to List(5) { listOf(listOf(0.0)) }, "palette" to emptyMap<String, String>())
        assertNull(MonsterSpriteDecoder.decodeInline(fiveFrames))
    }

    @Test fun grayToneFormula() {
        // Red F0574C → Y = (0.299·240 + 0.587·87 + 0.114·76)·0.8 = 105.19… → 105 (0x69). The iOS value.
        assertEquals(0x696969, MonsterSpriteDecoder.grayTone(0xF0574C))
        assertEquals(0xCCCCCC, MonsterSpriteDecoder.grayTone(0xFFFFFF))
        assertEquals(0, MonsterSpriteDecoder.grayTone(0x000000))
    }

    // MARK: Draws

    @Test fun unitRollIsTheIosDraw() {
        // SHA-256 → first 16 hex digits → UInt64 → >> 11 → / 2^53, computed independently.
        assertEquals(0.7283949105904021, MonsterDraw.unitRoll("abc"), 0.0)
        assertEquals(0.8639174744647738, MonsterDraw.unitRoll("s|k0|species"), 0.0)
        assertEquals(0.7795973610200768, MonsterDraw.position("k0"), 1e-15)
    }

    @Test fun weightedPickBoundaries() {
        val c = MonsterConfig.BUNDLED_DEFAULT // 90 / 6 / 2.5 / 1 / 0.5 = 100
        fun pick(roll: Double, commonOnly: Boolean = false) = MonsterDraw.pick(c, "2.0", commonOnly, roll).id
        assertEquals("slime", pick(0.0))
        assertEquals("slime", pick(0.8999))
        assertEquals("goblin", pick(0.9))
        assertEquals("mimic", pick(0.9999))
        assertEquals("slime", pick(0.9999, commonOnly = true))
    }

    @Test fun unreleasedAndNewerMonstersAreSkipped() {
        val c = MonsterConfig.BUNDLED_DEFAULT.copy(
            catalog = listOf(
                MonsterCatalogEntry("slime", "common", 1, 10, 1.0),
                MonsterCatalogEntry("bat", "special", 2, 30, 99.0, released = false),
                MonsterCatalogEntry("imp", "special", 2, 30, 99.0, minAppVersion = "9.0"),
            ),
        )
        for (roll in listOf(0.0, 0.5, 0.99)) assertEquals("slime", MonsterDraw.pick(c, "2.0", false, roll).id)
    }

    @Test fun guestPolicyGuaranteesAndCaps() {
        var c = MonsterConfig.BUNDLED_DEFAULT
        var progress = MonsterGuestProgress()
        val now = 1_800_000_000_000L
        fun decide(key: String, completion: Boolean = true, alive: Int = 0, p: MonsterGuestProgress = progress) =
            GuestSpawnPolicy.decide(completion, key, "s", c, p, alive, now, "2.0")
        // The first three are common.
        repeat(3) { i ->
            val outcome = decide("k$i") as GuestSpawnPolicy.Outcome.Spawn
            assertTrue(outcome.entry.isCommon)
            progress = GuestSpawnPolicy.recordSpawn(progress, now)
        }
        // Right after a special defeat the next one is common.
        progress = GuestSpawnPolicy.recordKill(progress, c.entry("orc"), "orc", now)
        assertTrue(progress.nextCommonGuaranteed)
        assertEquals(1, progress.kills["orc"])
        assertTrue((decide("z") as GuestSpawnPolicy.Outcome.Spawn).entry.isCommon)
        // The daily cap.
        c = c.copy(dailySpawnCap = 3)
        assertEquals(GuestSpawnPolicy.Outcome.None, decide("y"))
        // Chance 0 means no random spawn; the concurrent cap.
        c = MonsterConfig.BUNDLED_DEFAULT.copy(random = MonsterConfig.BUNDLED_DEFAULT.random.copy(chancePerSlot = 0.0))
        assertEquals(GuestSpawnPolicy.Outcome.None, decide("r", completion = false, p = MonsterGuestProgress()))
        c = c.copy(random = c.random.copy(chancePerSlot = 1.0))
        assertEquals(GuestSpawnPolicy.Outcome.None, decide("r", completion = false, alive = 3, p = MonsterGuestProgress()))
        assertTrue(decide("r", completion = false, p = MonsterGuestProgress()) is GuestSpawnPolicy.Outcome.Spawn)
    }

    @Test fun dailyCountRestartsOnAKstDay() {
        val evening = 1_792_421_999_000L // KST 2026-10-19 23:59:59
        val progress = GuestSpawnPolicy.recordSpawn(MonsterGuestProgress(spawnDay = "2026-10-19", spawnsToday = 59), evening)
        assertEquals(60, progress.spawnsToday)
        val nextDay = GuestSpawnPolicy.recordSpawn(progress, evening + 1_000)
        assertEquals("2026-10-20" to 1, nextDay.spawnDay to nextDay.spawnsToday)
    }

    @Test fun kstDayBoundary() {
        // 2026-10-19T14:59:59Z = KST 23:59:59; 15:00:00Z is the next KST day.
        assertEquals("2026-10-19", MonsterDraw.kstDay(1_792_421_999_000))
        assertEquals("2026-10-20", MonsterDraw.kstDay(1_792_422_000_000))
    }

    @Test fun positionStaysInsideTheBar() {
        repeat(200) { i ->
            val p = MonsterDraw.position("k$i")
            assertTrue(p in 0.1..0.9)
        }
    }

    // MARK: Server responses

    @Test fun callResponseDecoding() {
        val ok = MonsterApiClient.decodeCallResponse("""{"result":{"a":1}}""", 200)
        assertEquals(1.0, ((ok as MonsterApiClient.CallResult.Success).value["a"] as Number).toDouble(), 0.0)
        val err = MonsterApiClient.decodeCallResponse(
            """{"error":{"status":"FAILED_PRECONDITION","message":"secret text","details":{"code":"consent_required"}}}""",
            400,
        )
        assertEquals("consent_required", err.failureCode())
        val noDetails = MonsterApiClient.decodeCallResponse("""{"error":{"status":"UNAUTHENTICATED"}}""", 401)
        assertEquals("unauthenticated", noDetails.failureCode())
        assertNull(MonsterApiClient.decodeCallResponse("x", 500).failureCode())
        assertEquals(
            MonsterApiClient.CallResult.Success(emptyMap<String, Any?>()),
            MonsterApiClient.decodeCallResponse("""{"result":null}""", 200),
        )
    }

    @Test fun leaderboardDecoding() {
        val json = """
            [{"document":{"name":"projects/p/databases/(default)/documents/leaderboard/uid1","fields":{
              "nickname":{"stringValue":"David"},"tag":{"stringValue":"0000"},"avatar":{"stringValue":"operator"},
              "totalScore":{"integerValue":"420"},"scoreReachedAtMs":{"integerValue":"5"},
              "commonKills":{"integerValue":"7"},"specialKills":{"integerValue":"2"}}}},
             {"readTime":"2026-10-20T00:00:00Z"}]
        """.trimIndent()
        val rows = MonsterApiClient.decodeLeaderboard(json)!!
        assertEquals(listOf(LeaderboardEntry("uid1", "David", "0000", "operator", 420, 5, 7, 2)), rows)
    }

    /** The guest history goes up to 500 in total, the most-hunted species first (the iOS case). */
    @Test fun guestHistoryIsCapped() {
        val capped = MonsterApiClient.cappedKills(mapOf("slime" to 480, "goblin" to 30, "orc" to 2))
        assertEquals(500, capped.values.sum())
        assertEquals(mapOf("slime" to 480, "goblin" to 20), capped)
        assertEquals(mapOf("slime" to 3), MonsterApiClient.cappedKills(mapOf("slime" to 3)))
        // Ties go by id.
        assertEquals(mapOf("goblin" to 2, "orc" to 1), MonsterApiClient.cappedKills(mapOf("orc" to 2, "goblin" to 2), cap = 3))
    }

    @Test fun encounterDtoDecoding() {
        val dto = MonsterEncounterDto.fromJson(
            mapOf(
                "key" to "k", "source" to "completion", "provider" to "claude", "windowLabel" to "Current week",
                "resetBucketMs" to "1800000000000", "monsterId" to "goblin", "maxHP" to 3.0, "state" to "alive",
                "spawnedAtMs" to 1.0, "expiresAtMs" to 2.0,
            ),
        )
        assertEquals(1_800_000_000_000, dto?.resetBucketMs)
        assertEquals(3, dto?.maxHp)
        assertNull(MonsterEncounterDto.fromJson(mapOf("key" to "k")))
    }

    // MARK: Ledger

    @Test fun ledgerRoundTripsAndKeepsTheLatestTwoHundredFinished() {
        fun encounter(i: Int, state: MonsterEncounter.State, pending: Boolean = false) = MonsterEncounter(
            key = "k$i", ownerUid = if (i % 2 == 0) null else "u1", source = MonsterEncounter.Source.RANDOM,
            provider = "claude", windowLabel = "w", resetBucketMs = null, slotStartMs = i * 60_000L,
            monsterId = "slime", maxHp = 1, hp = 0, state = state, spawnedAtMs = i.toLong(), expiresAtMs = i + 10L,
            defeatedAtMs = i.toLong(), pendingDefeat = pending, position = 0.5,
        )
        val finished = (0 until 250).map { encounter(it, MonsterEncounter.State.DEFEATED) }
        val alive = encounter(900, MonsterEncounter.State.ALIVE).copy(hp = 1, defeatedAtMs = null)
        val pending = encounter(901, MonsterEncounter.State.DEFEATED, pending = true)
        val state = MonsterLedgerState(
            encounters = finished + alive + pending,
            guest = MonsterGuestProgress(spawnCount = 4, kills = mapOf("slime" to 3), firstAtMs = 1, lastAtMs = 9),
            mergedUids = listOf("u1"),
            deviceSalt = "salt",
        ).pruned()
        assertEquals(202, state.encounters.size)
        assertTrue(alive in state.encounters && pending in state.encounters)
        // The oldest finished ones go first.
        assertFalse(state.encounters.any { it.key == "k49" })
        assertTrue(state.encounters.any { it.key == "k50" })
        val decoded = MonsterLedgerCodec.decode(MonsterLedgerCodec.encode(state))
        assertEquals(state, decoded)
        assertNull(MonsterLedgerCodec.decode("not json"))
    }
}

/** The server error code, or null. */
internal fun MonsterApiClient.CallResult<*>.failureCode(): String? =
    ((this as? MonsterApiClient.CallResult.Failure)?.error as? MonsterApiClient.CallError.Server)?.code
