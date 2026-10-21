package com.ScienceFiction.TokenWatchAndroid.monster

import com.ScienceFiction.TokenWatchAndroid.account.FirestoreValue
import com.ScienceFiction.TokenWatchAndroid.domain.account.AvatarCatalog
import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkTransport
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * V2 server calls (Cloud Functions callables), mirroring iOS `MonsterAPIClient`. No SDK: a callable is
 * `POST https://{region}-{project}.cloudfunctions.net/{name}` with body `{"data": …}`, answering
 * `{"result": …}`. Errors are told apart by the `error.details.code` enumeration only — `message` is
 * neither shown nor logged. The ID token is fetched right before each request and only placed in its
 * header; a 401 force-refreshes it and retries once. The top 100 is read directly with a Firestore REST
 * `runQuery` (public read rules).
 *
 * What is sent: provider, window label, period, reset bucket, slot, platform and app version. Never a
 * usage figure, AI service account information or a device identifier.
 */
class MonsterApiClient(
    private val projectId: String,
    private val apiKey: String?,
    /** ID token supplier; `forceRefresh` drops the cached token. Null when nobody is signed in. */
    private val token: suspend (forceRefresh: Boolean) -> String?,
    private val transport: NetworkTransport,
    val appVersion: String,
) {
    /** Why a call failed. [Server] carries the contract's `details.code` (e.g. `consent_required`). */
    sealed class CallError {
        data object NotSignedIn : CallError()
        data object Transport : CallError()
        data class Server(val code: String) : CallError()
    }

    sealed class CallResult<out T> {
        data class Success<T>(val value: T) : CallResult<T>()
        data class Failure(val error: CallError) : CallResult<Nothing>()

        fun <R> map(transform: (T) -> R): CallResult<R> = when (this) {
            is Success -> Success(transform(value))
            is Failure -> this
        }
    }

    sealed class SpawnResult {
        data class Encounter(val encounter: MonsterEncounterDto) : SpawnResult()

        /** Not drawn by chance, or the concurrent cap. */
        data object None : SpawnResult()
    }

    /** Report reasons (the `reportPlayer` contract). */
    enum class ReportReason(val wireId: String) {
        NICKNAME("nickname"),
        AVATAR("avatar"),
        CHEATING("cheating"),
        OTHER("other"),
    }

    // MARK: Spawns and defeats

    suspend fun spawnCompletion(
        provider: String,
        windowLabel: String,
        periodSec: Long,
        resetBucketMs: Long,
        resetsAtMs: Long,
    ): CallResult<SpawnResult> = spawn(
        mapOf(
            "source" to "completion",
            "provider" to provider,
            "windowLabel" to windowLabel,
            "periodSec" to periodSec,
            "resetBucketMs" to resetBucketMs,
            "resetsAtMs" to resetsAtMs,
        ),
    )

    suspend fun spawnRandom(slotStartMs: Long, candidates: List<Pair<String, String>>): CallResult<SpawnResult> =
        spawn(
            mapOf(
                "source" to "random",
                "slotStartMs" to slotStartMs,
                "candidates" to candidates.map { (provider, label) -> mapOf("provider" to provider, "windowLabel" to label) },
            ),
        )

    private suspend fun spawn(data: Map<String, Any>): CallResult<SpawnResult> {
        val body = data + mapOf("platform" to PLATFORM, "appVersion" to appVersion, "spriteFormat" to SPRITE_FORMAT)
        return call("spawnEncounter", body).map { result ->
            (result["encounter"] as? Map<*, *>)?.let(MonsterEncounterDto::fromJson)
                ?.let { SpawnResult.Encounter(it) } ?: SpawnResult.None
        }
    }

    /**
     * Confirms a defeat. A defeat outside the time window comes back expired without `stats` (null): the
     * caller keeps the stats it has.
     */
    suspend fun defeat(key: String, defeatedAtMs: Long): CallResult<Pair<MonsterEncounterDto?, MonsterStats?>> =
        call(
            "defeatEncounter",
            mapOf("key" to key, "defeatedAtMs" to defeatedAtMs, "platform" to PLATFORM, "appVersion" to appVersion),
        ).map { result ->
            (result["encounter"] as? Map<*, *>)?.let(MonsterEncounterDto::fromJson) to
                (result["stats"] as? Map<*, *>)?.let(MonsterStats::fromJson)
        }

    // MARK: Account and guild

    suspend fun acceptTerms(version: Int): CallResult<Int> =
        call("acceptTerms", mapOf("version" to version)).map { it["termsAcceptedVersion"].asLooseInt() ?: version }

    suspend fun myRank(): CallResult<MyRank> = call("myRank", emptyMap()).map { r ->
        MyRank(listed = r["listed"] as? Boolean ?: false, rank = r["rank"].asLooseInt(), stats = MonsterStats.fromJson(r))
    }

    suspend fun mergeGuestHistory(progress: MonsterGuestProgress): CallResult<Boolean> {
        val data = buildMap<String, Any> {
            put("kills", cappedKills(progress.kills))
            progress.firstAtMs?.let { put("firstAtMs", it) }
            progress.lastAtMs?.let { put("lastAtMs", it) }
        }
        return call("mergeGuestHistory", data).map { it["merged"] as? Boolean ?: false }
    }

    suspend fun report(targetUid: String, reason: ReportReason): CallResult<Unit> =
        call("reportPlayer", mapOf("targetUid" to targetUid, "reason" to reason.wireId)).map { }

    // MARK: Leaderboard

    /** The top 100 (total score descending, ties to whoever got there first). Readable signed out too. */
    suspend fun leaderboardTop(limit: Int = 100): List<LeaderboardEntry>? {
        val url = HttpUrl.Builder()
            .scheme("https")
            .host("firestore.googleapis.com")
            .addPathSegment("v1")
            .addPathSegment("projects")
            .addPathSegment(projectId)
            .addPathSegment("databases")
            .addPathSegment("(default)")
            .addPathSegment("documents:runQuery")
            .apply { apiKey?.let { addQueryParameter("key", it) } }
            .build()
        val query = mapOf(
            "structuredQuery" to mapOf(
                "from" to listOf(mapOf("collectionId" to "leaderboard")),
                "orderBy" to listOf(
                    mapOf("field" to mapOf("fieldPath" to "totalScore"), "direction" to "DESCENDING"),
                    mapOf("field" to mapOf("fieldPath" to "scoreReachedAtMs"), "direction" to "ASCENDING"),
                ),
                "limit" to limit.coerceIn(1, 100),
            ),
        )
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("Cache-Control", "no-cache")
            .post(mapAdapter.toJson(query).toRequestBody(JSON))
        token(false)?.let { builder.header("Authorization", "Bearer $it") }
        val response = try {
            transport.execute(builder.build())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }
        if (response.statusCode !in 200..299) return null
        return decodeLeaderboard(response.bodyText())
    }

    // MARK: Transport

    /** One callable call. A 401 fetches a fresh token and tries once more. */
    suspend fun call(name: String, data: Map<String, Any?>): CallResult<Map<String, Any?>> {
        val url = "https://$REGION-$projectId.cloudfunctions.net/$name"
        val body = mapAdapter.toJson(mapOf("data" to data))
        for (attempt in 0..1) {
            val idToken = token(attempt > 0) ?: return CallResult.Failure(CallError.NotSignedIn)
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $idToken")
                .header("Cache-Control", "no-cache")
                .post(body.toRequestBody(JSON))
                .build()
            val response = try {
                transport.execute(request)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return CallResult.Failure(CallError.Transport)
            }
            if (response.statusCode == 401 && attempt == 0) continue
            return decodeCallResponse(response.bodyText(), response.statusCode)
        }
        return CallResult.Failure(CallError.Transport)
    }

    companion object {
        const val REGION = "asia-northeast3"
        const val PLATFORM = "android"
        const val SPRITE_FORMAT = 1

        /** The guest history the server accepts (the total over every species); it refuses more. */
        const val GUEST_HISTORY_CAP = 500

        /** Kills trimmed to [cap], the most-hunted species first (ties by id). Pure. */
        fun cappedKills(kills: Map<String, Int>, cap: Int = GUEST_HISTORY_CAP): Map<String, Int> {
            var remaining = cap
            val out = linkedMapOf<String, Int>()
            for ((id, count) in kills.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })) {
                if (remaining <= 0 || count <= 0) continue
                val take = minOf(count, remaining)
                out[id] = take
                remaining -= take
            }
            return out
        }

        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val moshi = Moshi.Builder().build()
        private val anyAdapter = moshi.adapter(Any::class.java)
        private val mapAdapter = moshi.adapter<Map<String, Any?>>(
            Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java),
        )

        /** `{"result": …}` → success, `{"error": {"details": {"code"}}}` → the server code. Pure. */
        @Suppress("UNCHECKED_CAST")
        fun decodeCallResponse(body: String, status: Int): CallResult<Map<String, Any?>> {
            val json = runCatching { anyAdapter.fromJson(body) }.getOrNull() as? Map<String, Any?>
            if (status in 200..299 && json != null && "result" in json) {
                when (val result = json["result"]) {
                    is Map<*, *> -> return CallResult.Success(result as Map<String, Any?>)
                    null -> return CallResult.Success(emptyMap())
                }
            }
            (json?.get("error") as? Map<*, *>)?.let { error ->
                ((error["details"] as? Map<*, *>)?.get("code") as? String)?.let {
                    return CallResult.Failure(CallError.Server(it))
                }
                (error["status"] as? String)?.let { return CallResult.Failure(CallError.Server(it.lowercase())) }
            }
            return CallResult.Failure(CallError.Transport)
        }

        /** runQuery's array (a `document` per element) → leaderboard rows. Documents missing fields are dropped. */
        fun decodeLeaderboard(body: String): List<LeaderboardEntry>? {
            val rows = runCatching { anyAdapter.fromJson(body) }.getOrNull() as? List<*> ?: return null
            return rows.mapNotNull { row ->
                val doc = (row as? Map<*, *>)?.get("document") as? Map<*, *> ?: return@mapNotNull null
                val name = doc["name"] as? String ?: return@mapNotNull null
                val raw = doc["fields"] as? Map<*, *> ?: return@mapNotNull null
                val fields = FirestoreValue.decodeFields(raw)
                LeaderboardEntry(
                    uid = name.substringAfterLast('/'),
                    nickname = fields["nickname"]?.stringValue ?: return@mapNotNull null,
                    tag = fields["tag"]?.stringValue ?: return@mapNotNull null,
                    avatarId = fields["avatar"]?.stringValue ?: AvatarCatalog.FALLBACK_ID,
                    totalScore = fields["totalScore"]?.integerValue?.toInt() ?: return@mapNotNull null,
                    scoreReachedAtMs = fields["scoreReachedAtMs"]?.integerValue ?: 0L,
                    commonKills = fields["commonKills"]?.integerValue?.toInt() ?: 0,
                    specialKills = fields["specialKills"]?.integerValue?.toInt() ?: 0,
                )
            }
        }
    }
}

/** An encounter the server returned (the `spawnEncounter`/`defeatEncounter` response). */
data class MonsterEncounterDto(
    val key: String,
    val source: String,
    val provider: String,
    val windowLabel: String,
    val resetBucketMs: Long?,
    val slotStartMs: Long?,
    val monsterId: String,
    val maxHp: Int,
    val state: String,
    val spawnedAtMs: Long,
    val expiresAtMs: Long,
    val defeatedAtMs: Long?,
    val awardedScore: Int?,
) {
    companion object {
        fun fromJson(json: Map<*, *>): MonsterEncounterDto? {
            return MonsterEncounterDto(
                key = json["key"] as? String ?: return null,
                source = json["source"] as? String ?: return null,
                provider = json["provider"] as? String ?: return null,
                windowLabel = json["windowLabel"] as? String ?: return null,
                resetBucketMs = json["resetBucketMs"].asLooseLong(),
                slotStartMs = json["slotStartMs"].asLooseLong(),
                monsterId = json["monsterId"] as? String ?: return null,
                maxHp = (json["maxHP"].asLooseInt() ?: return null).coerceAtLeast(1),
                state = json["state"] as? String ?: return null,
                spawnedAtMs = json["spawnedAtMs"].asLooseLong() ?: return null,
                expiresAtMs = json["expiresAtMs"].asLooseLong() ?: return null,
                defeatedAtMs = json["defeatedAtMs"].asLooseLong(),
                awardedScore = json["awardedScore"].asLooseInt(),
            )
        }
    }
}

data class MonsterStats(val totalScore: Int = 0, val commonKills: Int = 0, val specialKills: Int = 0) {
    companion object {
        fun fromJson(json: Map<*, *>?): MonsterStats = MonsterStats(
            totalScore = json?.get("totalScore").asLooseInt() ?: 0,
            commonKills = json?.get("commonKills").asLooseInt() ?: 0,
            specialKills = json?.get("specialKills").asLooseInt() ?: 0,
        )
    }
}

/** One leaderboard row (the public `leaderboard/{uid}` document). */
data class LeaderboardEntry(
    val uid: String,
    val nickname: String,
    val tag: String,
    val avatarId: String,
    val totalScore: Int,
    val scoreReachedAtMs: Long,
    val commonKills: Int,
    val specialKills: Int,
)

data class MyRank(val listed: Boolean, val rank: Int?, val stats: MonsterStats)

/** A number or a decimal string, truncated like iOS `NSNumber.intValue`. */
private fun Any?.asLooseLong(): Long? = when (this) {
    is Number -> toLong()
    is String -> toLongOrNull()
    else -> null
}

private fun Any?.asLooseInt(): Int? = asLooseLong()?.toInt()
