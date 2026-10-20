package com.ScienceFiction.TokenWatchAndroid.monster

import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkTransport
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl
import okhttp3.Request

/**
 * Monster catalog and spawn settings (the server's `config/monster`, published from the admin page),
 * mirroring iOS `MonsterConfig`. The app starts from the bundled defaults and re-reads the public
 * document at most hourly, caching it. Only guest draws and drawing use it; for signed-in players the
 * server decides spawns and defeats from the same settings. Unknown fields and ids are ignored and
 * missing fields keep their defaults, so a partial edit on the admin page never breaks the app.
 */
data class MonsterConfig(
    val schemaVersion: Int,
    val balanceVersion: Int,
    val catalog: List<MonsterCatalogEntry>,
    val completionEnabled: Boolean,
    val random: RandomSpawn,
    val guaranteedSlimeFirstN: Int,
    val tapDebounceMs: Int,
    val dailySpawnCap: Int,
) {
    data class RandomSpawn(
        val enabled: Boolean,
        val chancePerSlot: Double,
        val slotMinutes: Int,
        val lifetimeMinutes: Int,
        val minUsedPercent: Double,
        val maxAlivePerUser: Int,
    )

    fun entry(id: String): MonsterCatalogEntry? = catalog.firstOrNull { it.id == id }

    companion object {
        /** Bundled defaults — the same values as the server seed. */
        val BUNDLED_DEFAULT = MonsterConfig(
            schemaVersion = 1,
            balanceVersion = 1,
            catalog = listOf(
                MonsterCatalogEntry(id = "slime", tier = "common", hp = 1, score = 10, weight = 90.0),
                MonsterCatalogEntry(id = "goblin", tier = "special", hp = 3, score = 50, weight = 6.0),
                MonsterCatalogEntry(id = "skeleton", tier = "special", hp = 5, score = 100, weight = 2.5),
                MonsterCatalogEntry(id = "orc", tier = "special", hp = 8, score = 190, weight = 1.0),
                MonsterCatalogEntry(id = "mimic", tier = "special", hp = 12, score = 350, weight = 0.5),
            ),
            completionEnabled = true,
            random = RandomSpawn(
                enabled = true,
                chancePerSlot = 0.08,
                slotMinutes = 60,
                lifetimeMinutes = 60,
                minUsedPercent = 0.0,
                maxAlivePerUser = 3,
            ),
            guaranteedSlimeFirstN = 3,
            tapDebounceMs = 120,
            dailySpawnCap = 60,
        )
    }
}

data class MonsterCatalogEntry(
    val id: String,
    /** common · special · event. Unknown values count as special (the server does the ranking tally). */
    val tier: String,
    val hp: Int,
    val score: Int,
    val weight: Double,
    val released: Boolean = true,
    val bundledSprite: String? = null,
    val inlineSprite: MonsterSpriteData? = null,
    val minAppVersion: String? = null,
) {
    val isCommon: Boolean get() = tier == "common"
}

/** Settings JSON decoding (pure). The payload rides the same `payload` string envelope as announcements. */
object MonsterConfigDecoder {
    private val adapter = Moshi.Builder().build().adapter(Any::class.java)

    fun decodeEnvelope(body: String): MonsterConfig? = payload(body)?.let(::decodePayload)

    /** The `payload` string from the Firestore document envelope. */
    fun payload(body: String): String? {
        val root = runCatching { adapter.fromJson(body) }.getOrNull() as? Map<*, *> ?: return null
        val fields = root["fields"] as? Map<*, *> ?: return null
        return (fields["payload"] as? Map<*, *>)?.get("stringValue") as? String
    }

    /** The payload JSON. Null unless the top level is an object; missing fields keep the defaults. */
    fun decodePayload(json: String): MonsterConfig? {
        val root = runCatching { adapter.fromJson(json) }.getOrNull() as? Map<*, *> ?: return null
        val base = MonsterConfig.BUNDLED_DEFAULT
        var catalog = base.catalog
        (root["catalog"] as? List<*>)?.let { list ->
            val entries = list.mapNotNull { entry(it as? Map<*, *>) }
            if (entries.isNotEmpty()) catalog = entries
        }
        var completionEnabled = base.completionEnabled
        var random = base.random
        (root["spawn"] as? Map<*, *>)?.let { spawn ->
            ((spawn["completion"] as? Map<*, *>)?.get("enabled") as? Boolean)?.let { completionEnabled = it }
            (spawn["random"] as? Map<*, *>)?.let { r ->
                random = MonsterConfig.RandomSpawn(
                    enabled = r["enabled"] as? Boolean ?: base.random.enabled,
                    chancePerSlot = (r["chancePerSlot"].asDouble() ?: base.random.chancePerSlot).coerceIn(0.0, 1.0),
                    slotMinutes = (r["slotMinutes"].asInt() ?: base.random.slotMinutes).coerceAtLeast(1),
                    lifetimeMinutes = (r["lifetimeMinutes"].asInt() ?: base.random.lifetimeMinutes).coerceAtLeast(1),
                    minUsedPercent = r["minUsedPercent"].asDouble() ?: base.random.minUsedPercent,
                    maxAlivePerUser = (r["maxAlivePerUser"].asInt() ?: base.random.maxAlivePerUser).coerceAtLeast(0),
                )
            }
        }
        return MonsterConfig(
            schemaVersion = root["schemaVersion"].asInt() ?: base.schemaVersion,
            balanceVersion = root["balanceVersion"].asInt() ?: base.balanceVersion,
            catalog = catalog,
            completionEnabled = completionEnabled,
            random = random,
            guaranteedSlimeFirstN = (root["guaranteedSlimeFirstN"].asInt() ?: base.guaranteedSlimeFirstN).coerceAtLeast(0),
            tapDebounceMs = (root["tapDebounceMs"].asInt() ?: base.tapDebounceMs).coerceAtLeast(0),
            dailySpawnCap = (root["dailySpawnCap"].asInt() ?: base.dailySpawnCap).coerceAtLeast(0),
        )
    }

    private fun entry(e: Map<*, *>?): MonsterCatalogEntry? {
        e ?: return null
        val id = (e["id"] as? String)?.takeIf(String::isNotEmpty) ?: return null
        val hp = e["hp"].asInt()?.takeIf { it >= 1 } ?: return null
        val score = e["score"].asInt()?.takeIf { it >= 0 } ?: return null
        val sprite = e["sprite"] as? Map<*, *>
        return MonsterCatalogEntry(
            id = id,
            tier = e["tier"] as? String ?: "special",
            hp = hp,
            score = score,
            weight = (e["weight"].asDouble() ?: 0.0).coerceAtLeast(0.0),
            released = e["released"] as? Boolean ?: true,
            bundledSprite = sprite?.get("bundled") as? String,
            inlineSprite = (sprite?.get("inline") as? Map<*, *>)?.let(MonsterSpriteDecoder::decodeInline),
            minAppVersion = e["minAppVersion"] as? String,
        )
    }
}

/** Moshi reads JSON numbers as Double; only exact integers count. */
internal fun Any?.asInt(): Int? {
    val number = (this as? Number)?.toDouble() ?: return null
    val int = number.toInt()
    return int.takeIf { it.toDouble() == number }
}

internal fun Any?.asDouble(): Double? = (this as? Number)?.toDouble()

/** App version comparison ("1.4.0" < "1.10"). Non-numeric parts count as 0. */
object AppVersionCompare {
    fun isAtLeast(version: String, minimum: String): Boolean {
        val a = version.split('.').map { it.toIntOrNull() ?: 0 }
        val b = minimum.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return true
    }
}

/**
 * Reads the public `config/monster` document (no authentication, like the announcement feed). Returns
 * the payload JSON for caching, or null on any failure; decoding is [MonsterConfigDecoder.decodePayload].
 */
class MonsterConfigClient(
    private val transport: NetworkTransport,
    private val projectId: String,
    private val apiKey: String?,
) {
    suspend fun fetchPayload(): String? {
        val url = HttpUrl.Builder()
            .scheme("https")
            .host("firestore.googleapis.com")
            .addPathSegments("v1/projects/$projectId/databases/(default)/documents/config/monster")
            .apply { apiKey?.let { addQueryParameter("key", it) } }
            .build()
        return try {
            val response = transport.execute(
                Request.Builder().url(url).get()
                    .header("Accept", "application/json")
                    .header("Cache-Control", "no-cache")
                    .build(),
            )
            if (response.statusCode in 200..299) MonsterConfigDecoder.payload(response.bodyText()) else null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }
}
