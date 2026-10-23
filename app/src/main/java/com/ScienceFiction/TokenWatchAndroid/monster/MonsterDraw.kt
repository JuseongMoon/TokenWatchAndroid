package com.ScienceFiction.TokenWatchAndroid.monster

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Guest spawn draws (signed out, or before consenting) and the species and position draws both paths
 * share, mirroring iOS `MonsterDraw`. Pure. Signed-in players get their spawns, species and defeats
 * from the server, so this only decides guest monsters and drawing. Draws are deterministic in the
 * key: refreshing again never redraws the same slot or session.
 */
object MonsterDraw {
    private val seoul: ZoneId = ZoneId.of("Asia/Seoul")

    /** Text → deterministic [0, 1) (the first 8 bytes of SHA-256, top 53 bits). */
    fun unitRoll(text: String): Double {
        val hex = MonsterSessionKey.sha256Hex(text)
        val value = java.lang.Long.parseUnsignedLong(hex.substring(0, 16), 16)
        return (value ushr 11).toDouble() / (1L shl 53).toDouble()
    }

    /** The regular draw pool this app can show (released, minimum version met). */
    fun pool(config: MonsterConfig, appVersion: String, commonOnly: Boolean): List<MonsterCatalogEntry> =
        config.catalog.filter { entry ->
            entry.released && entry.weight > 0.0 &&
                (entry.minAppVersion?.let { AppVersionCompare.isAtLeast(appVersion, it) } ?: true) &&
                (!commonOnly || entry.isCommon)
        }

    /** Weighted draw. With no candidate it is a slime. */
    fun pick(config: MonsterConfig, appVersion: String, commonOnly: Boolean, roll: Double): MonsterCatalogEntry {
        var candidates = pool(config, appVersion, commonOnly)
        if (candidates.isEmpty() && commonOnly) candidates = pool(config, appVersion, commonOnly = false)
        val total = candidates.sumOf { it.weight }
        if (total <= 0.0) return config.entry("slime") ?: MonsterConfig.BUNDLED_DEFAULT.catalog[0]
        var target = roll.coerceIn(0.0, 0.999_999_999) * total
        for (entry in candidates) {
            if (target < entry.weight) return entry
            target -= entry.weight
        }
        return candidates.last()
    }

    /** A random spawn's place inside the gauge (0.1…0.9). */
    fun position(key: String): Double = 0.1 + unitRoll("$key|pos") * 0.8

    /** The KST date (the daily cap boundary — the server's). */
    fun kstDay(nowMs: Long): String = Instant.ofEpochMilli(nowMs).atZone(seoul).toLocalDate().toString()

    /**
     * The weekly ranks' week: it starts at Monday 00:00 KST and is named by that Monday's KST date — the
     * server's formula (1970-01-01 was a Thursday, hence the +3).
     */
    fun kstWeekId(nowMs: Long): String {
        val day = Math.floorDiv(nowMs + 9 * 3_600_000L, 86_400_000L)
        val monday = day - Math.floorMod(day + 3, 7L)
        return LocalDate.ofEpochDay(monday).toString()
    }
}

/** Guest spawn rules — the server's rules applied on the device. */
object GuestSpawnPolicy {
    sealed class Outcome {
        data class Spawn(val entry: MonsterCatalogEntry) : Outcome()

        /** Not drawn by chance, daily cap, concurrent cap, or switched off. */
        data object None : Outcome()
    }

    /**
     * [isCompletion]: a completion spawn always appears (no chance roll). [key] is the guest encounter
     * key; [aliveRandom] is how many guest random monsters are alive now.
     */
    fun decide(
        isCompletion: Boolean,
        key: String,
        salt: String,
        config: MonsterConfig,
        progress: MonsterGuestProgress,
        aliveRandom: Int,
        nowMs: Long,
        appVersion: String,
    ): Outcome {
        if (isCompletion) {
            if (!config.completionEnabled) return Outcome.None
        } else if (
            !config.random.enabled || aliveRandom >= config.random.maxAlivePerUser ||
            MonsterDraw.unitRoll("$salt|$key|chance") >= config.random.chancePerSlot
        ) {
            return Outcome.None
        }
        val today = MonsterDraw.kstDay(nowMs)
        val spawnsToday = if (progress.spawnDay == today) progress.spawnsToday else 0
        if (spawnsToday >= config.dailySpawnCap) return Outcome.None
        val commonOnly = progress.spawnCount < config.guaranteedSlimeFirstN || progress.nextCommonGuaranteed
        val entry = MonsterDraw.pick(config, appVersion, commonOnly, MonsterDraw.unitRoll("$salt|$key|species"))
        return Outcome.Spawn(entry)
    }

    /** Progress once a spawn is final. */
    fun recordSpawn(progress: MonsterGuestProgress, nowMs: Long): MonsterGuestProgress {
        val today = MonsterDraw.kstDay(nowMs)
        val spawnsToday = if (progress.spawnDay == today) progress.spawnsToday else 0
        return progress.copy(
            spawnDay = today,
            spawnsToday = spawnsToday + 1,
            spawnCount = progress.spawnCount + 1,
            nextCommonGuaranteed = false,
        )
    }

    /** A defeat. Right after a special defeat the next spawn is common. */
    fun recordKill(
        progress: MonsterGuestProgress,
        entry: MonsterCatalogEntry?,
        monsterId: String,
        nowMs: Long,
    ): MonsterGuestProgress = progress.copy(
        kills = progress.kills + (monsterId to (progress.kills[monsterId] ?: 0) + 1),
        firstAtMs = progress.firstAtMs ?: nowMs,
        lastAtMs = nowMs,
        nextCommonGuaranteed = if (entry != null && !entry.isCommon) true else progress.nextCommonGuaranteed,
    )
}
