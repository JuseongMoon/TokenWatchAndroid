package com.ScienceFiction.TokenWatchAndroid.monster

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.util.UUID

/**
 * Monster spawn and defeat records on this device (the ledger), mirroring iOS `MonsterLedger`.
 *
 * Only living monsters, defeats not yet confirmed by the server and the latest 200 finished ones are
 * kept; guest defeats are also tallied per monster in [MonsterGuestProgress]. The ledger lives in its own
 * DataStore file rather than the shared settings file, which is rewritten whole on every change.
 *
 * It also holds each agent's next random draw, independent of the account, so switching accounts never
 * adds draws. Agents are created anew on a reinstall, so this needs no Block Store copy.
 */
data class MonsterEncounter(
    val key: String,
    /** Null = guest (device draw, unranked). Otherwise that account's server-decided monster. */
    val ownerUid: String?,
    val source: Source,
    val provider: String,
    /** The key label (Codex's additional limit uses the constant). */
    val windowLabel: String,
    val resetBucketMs: Long?,
    /** A random spawn's draw due time. */
    val drawAtMs: Long?,
    val monsterId: String,
    val maxHp: Int,
    /** HP left (device-local: hits are never sent to the server). */
    val hp: Int,
    val state: State,
    val spawnedAtMs: Long,
    val expiresAtMs: Long,
    val defeatedAtMs: Long? = null,
    val awardedScore: Int? = null,
    /** Defeated but not yet confirmed by the server (offline and the like) — sent again later. */
    val pendingDefeat: Boolean = false,
    /** The window it is drawn on (this device's agent and display label). A monster received on another device is found by provider and label instead. */
    val agentId: UUID? = null,
    val displayLabel: String? = null,
    /** A random spawn's place inside the gauge (0…1, fixed by the key). */
    val position: Double,
) {
    enum class Source(val wireId: String) {
        COMPLETION("completion"),
        RANDOM("random"),
        EVENT("event"),
        ;

        companion object {
            fun fromWireId(value: String?): Source? = entries.firstOrNull { it.wireId == value }
        }
    }

    enum class State(val wireId: String) {
        ALIVE("alive"),
        DEFEATED("defeated"),
        EXPIRED("expired"),
        ;

        companion object {
            fun fromWireId(value: String?): State? = entries.firstOrNull { it.wireId == value }
        }
    }

    val isAlive: Boolean get() = state == State.ALIVE
}

/** Guest progress (unranked). Merged once into the account's history at the first consent. */
data class MonsterGuestProgress(
    val spawnCount: Int = 0,
    val nextCommonGuaranteed: Boolean = false,
    val spawnDay: String = "",
    val spawnsToday: Int = 0,
    val kills: Map<String, Int> = emptyMap(),
    val firstAtMs: Long? = null,
    val lastAtMs: Long? = null,
)

data class MonsterLedgerState(
    val encounters: List<MonsterEncounter> = emptyList(),
    val guest: MonsterGuestProgress = MonsterGuestProgress(),
    /** Accounts whose guest history has been merged (or found to need none). */
    val mergedUids: List<String> = emptyList(),
    /** Random per install — the guest draw seed. Never sent to the server. */
    val deviceSalt: String = UUID.randomUUID().toString(),
    /** The device's random draw schedule: agent ([MonsterSessionKey.agentToken]) → next draw request (ms). */
    val randomSchedule: Map<String, Long> = emptyMap(),
) {
    fun nextDraw(agent: String): Long? = randomSchedule[agent]

    /** Sets an agent's next draw and drops schedules over 40 days past (removed agents). */
    fun withNextDraw(atMs: Long, agent: String, nowMs: Long): MonsterLedgerState = copy(
        randomSchedule = randomSchedule.filterValues { nowMs - it < DeviceSessionLedgerState.RETENTION_MS } + (agent to atMs),
    )

    /** Retention: everything alive or unconfirmed, plus the latest 200 finished. */
    fun pruned(): MonsterLedgerState {
        val active = encounters.filter { it.isAlive || it.pendingDefeat }
        val finished = encounters.filter { !it.isAlive && !it.pendingDefeat }
            .sortedByDescending { it.defeatedAtMs ?: it.expiresAtMs }
            .take(KEEP_FINISHED)
        return copy(encounters = active + finished)
    }

    companion object {
        const val KEEP_FINISHED = 200
    }
}

/** The ledger's storage. Tests use the memory implementation. */
interface MonsterLedgerStorage {
    suspend fun load(): MonsterLedgerState?
    suspend fun save(state: MonsterLedgerState)
}

/** Memory storage (previews and tests — nothing is written). */
class MonsterLedgerMemory(var state: MonsterLedgerState? = null) : MonsterLedgerStorage {
    var saves = 0
        private set

    override suspend fun load(): MonsterLedgerState? = state

    override suspend fun save(state: MonsterLedgerState) {
        this.state = state
        saves += 1
    }
}

/** Ledger JSON (pure). Field names follow the iOS file; unknown or broken records are dropped. */
object MonsterLedgerCodec {
    private val adapter = Moshi.Builder().build().adapter<Map<String, Any?>>(
        Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java),
    )

    fun encode(state: MonsterLedgerState): String = adapter.toJson(
        mapOf(
            "encounters" to state.encounters.map(::encodeEncounter),
            "guest" to state.guest.let { g ->
                buildMap {
                    put("spawnCount", g.spawnCount)
                    put("nextCommonGuaranteed", g.nextCommonGuaranteed)
                    put("spawnDay", g.spawnDay)
                    put("spawnsToday", g.spawnsToday)
                    put("kills", g.kills)
                    g.firstAtMs?.let { put("firstAtMs", it) }
                    g.lastAtMs?.let { put("lastAtMs", it) }
                }
            },
            "mergedUIDs" to state.mergedUids,
            "deviceSalt" to state.deviceSalt,
        ) + if (state.randomSchedule.isEmpty()) emptyMap() else mapOf("randomSchedule" to state.randomSchedule),
    )

    fun decode(json: String): MonsterLedgerState? {
        val root = runCatching { adapter.fromJson(json) }.getOrNull() ?: return null
        val guest = root["guest"] as? Map<*, *>
        return MonsterLedgerState(
            encounters = (root["encounters"] as? List<*>).orEmpty().mapNotNull { decodeEncounter(it as? Map<*, *>) },
            guest = MonsterGuestProgress(
                spawnCount = guest?.get("spawnCount").asInt() ?: 0,
                nextCommonGuaranteed = guest?.get("nextCommonGuaranteed") as? Boolean ?: false,
                spawnDay = guest?.get("spawnDay") as? String ?: "",
                spawnsToday = guest?.get("spawnsToday").asInt() ?: 0,
                kills = (guest?.get("kills") as? Map<*, *>).orEmpty().entries.mapNotNull { (k, v) ->
                    val id = k as? String ?: return@mapNotNull null
                    val count = v.asInt() ?: return@mapNotNull null
                    id to count
                }.toMap(),
                firstAtMs = guest?.get("firstAtMs").asLong(),
                lastAtMs = guest?.get("lastAtMs").asLong(),
            ),
            mergedUids = (root["mergedUIDs"] as? List<*>).orEmpty().filterIsInstance<String>(),
            deviceSalt = (root["deviceSalt"] as? String)?.takeIf(String::isNotEmpty) ?: UUID.randomUUID().toString(),
            // Missing in older files.
            randomSchedule = (root["randomSchedule"] as? Map<*, *>).orEmpty().entries.mapNotNull { (k, v) ->
                val agent = k as? String ?: return@mapNotNull null
                val atMs = v.asLong() ?: return@mapNotNull null
                agent to atMs
            }.toMap(),
        )
    }

    private fun encodeEncounter(e: MonsterEncounter): Map<String, Any> = buildMap {
        put("key", e.key)
        e.ownerUid?.let { put("ownerUID", it) }
        put("source", e.source.wireId)
        put("provider", e.provider)
        put("windowLabel", e.windowLabel)
        e.resetBucketMs?.let { put("resetBucketMs", it) }
        e.drawAtMs?.let { put("drawAtMs", it) }
        put("monsterID", e.monsterId)
        put("maxHP", e.maxHp)
        put("hp", e.hp)
        put("state", e.state.wireId)
        put("spawnedAtMs", e.spawnedAtMs)
        put("expiresAtMs", e.expiresAtMs)
        e.defeatedAtMs?.let { put("defeatedAtMs", it) }
        e.awardedScore?.let { put("awardedScore", it) }
        put("pendingDefeat", e.pendingDefeat)
        e.agentId?.let { put("agentID", it.toString()) }
        e.displayLabel?.let { put("displayLabel", it) }
        put("position", e.position)
    }

    private fun decodeEncounter(m: Map<*, *>?): MonsterEncounter? {
        m ?: return null
        return MonsterEncounter(
            key = m["key"] as? String ?: return null,
            ownerUid = m["ownerUID"] as? String,
            source = MonsterEncounter.Source.fromWireId(m["source"] as? String) ?: return null,
            provider = m["provider"] as? String ?: return null,
            windowLabel = m["windowLabel"] as? String ?: return null,
            resetBucketMs = m["resetBucketMs"].asLong(),
            drawAtMs = m["drawAtMs"].asLong(),
            monsterId = m["monsterID"] as? String ?: return null,
            maxHp = m["maxHP"].asInt() ?: return null,
            hp = m["hp"].asInt() ?: return null,
            state = MonsterEncounter.State.fromWireId(m["state"] as? String) ?: return null,
            spawnedAtMs = m["spawnedAtMs"].asLong() ?: return null,
            expiresAtMs = m["expiresAtMs"].asLong() ?: return null,
            defeatedAtMs = m["defeatedAtMs"].asLong(),
            awardedScore = m["awardedScore"].asInt(),
            pendingDefeat = m["pendingDefeat"] as? Boolean ?: false,
            agentId = (m["agentID"] as? String)?.let { runCatching { UUID.fromString(it) }.getOrNull() },
            displayLabel = m["displayLabel"] as? String,
            position = m["position"].asDouble() ?: 0.5,
        )
    }
}

/** Moshi reads JSON numbers as Double; only exact integers count (epoch ms stay below 2^53). */
internal fun Any?.asLong(): Long? {
    val number = (this as? Number)?.toDouble() ?: return null
    val long = number.toLong()
    return long.takeIf { it.toDouble() == number }
}
