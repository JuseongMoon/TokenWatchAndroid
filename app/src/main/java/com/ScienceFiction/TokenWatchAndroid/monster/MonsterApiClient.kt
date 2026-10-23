package com.ScienceFiction.TokenWatchAndroid.monster

import com.ScienceFiction.TokenWatchAndroid.account.FirestoreValue
import com.ScienceFiction.TokenWatchAndroid.domain.account.AvatarCatalog
import com.ScienceFiction.TokenWatchAndroid.domain.account.NicknamePolicy
import com.ScienceFiction.TokenWatchAndroid.domain.account.PlayerProfile
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
 * header; a 401 force-refreshes it and retries once. The top 100 (this week and all time) is read
 * directly with a Firestore REST `runQuery` (public read rules). The app account's profile writes (`setProfile`) and in-app deletion
 * (`deleteAccount`) go through this client too (AccountStore).
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

    suspend fun myRank(): CallResult<MyRank> = call("myRank", emptyMap()).map(::decodeMyRank)

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

    // MARK: App account

    /** A profile write. Nickname rules, banned words, the tag draw and the 30-day interval are the server's. */
    sealed class ProfileChange {
        data class Create(val nickname: String, val avatarId: String, val termsVersion: Int) : ProfileChange()
        data class Nickname(val nickname: String) : ProfileChange()
        data class Avatar(val avatarId: String) : ProfileChange()
    }

    /** Writes the profile; the answer is the server's profile, which the screen and the cache take as is. */
    suspend fun setProfile(change: ProfileChange): CallResult<PlayerProfile> {
        val data = when (change) {
            is ProfileChange.Create -> mapOf(
                "op" to "create",
                "nickname" to change.nickname,
                "avatarId" to change.avatarId,
                "termsAcceptedVersion" to change.termsVersion,
            )
            is ProfileChange.Nickname -> mapOf("op" to "nickname", "nickname" to change.nickname)
            is ProfileChange.Avatar -> mapOf("op" to "avatar", "avatarId" to change.avatarId)
        }
        return when (val result = call("setProfile", data)) {
            is CallResult.Success -> (result.value["profile"] as? Map<*, *>)?.let(::decodeProfile)
                ?.let { CallResult.Success(it) }
                ?: CallResult.Failure(CallError.Transport)
            is CallResult.Failure -> result
        }
    }

    /**
     * Deletes the account. The server erases the profile, monster records, rank and reports, and the Auth
     * user too. It only takes a token from a sign-in within five minutes (`requires_recent_login`).
     */
    suspend fun deleteAccount(): CallResult<Unit> = call("deleteAccount", emptyMap()).map { }

    // MARK: Leaderboard

    /**
     * The top 100 (score descending, ties to whoever got there first). Readable signed out too. The week
     * tab ranks only the documents of [weekId] (that week's Monday, KST) by their weekly score.
     */
    suspend fun leaderboardTop(scope: LeaderboardScope, weekId: String, limit: Int = 100): List<LeaderboardEntry>? {
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
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("Cache-Control", "no-cache")
            .post(mapAdapter.toJson(leaderboardQuery(scope, weekId, limit)).toRequestBody(JSON))
        token(false)?.let { builder.header("Authorization", "Bearer $it") }
        val response = try {
            transport.execute(builder.build())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }
        if (response.statusCode !in 200..299) return null
        return decodeLeaderboard(response.bodyText(), scope)
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

        /**
         * `setProfile`'s `profile` (plain JSON, no Firestore value wrapping) → profile. An avatar of any kind
         * other than `preset` reads as the fallback; a profile without a valid tag is null.
         */
        fun decodeProfile(json: Map<*, *>): PlayerProfile? {
            val nickname = json["nickname"] as? String ?: return null
            val lower = json["nicknameLower"] as? String ?: return null
            val tag = (json["tag"] as? String)?.takeIf(NicknamePolicy::isValidTag) ?: return null
            val changed = json["nicknameChangedAtMs"].asLooseLong() ?: return null
            val created = json["createdAtMs"].asLooseLong() ?: return null
            val avatar = json["avatar"] as? Map<*, *>
            val avatarId = (avatar?.get("id") as? String).takeIf { avatar?.get("kind") == "preset" }
            return PlayerProfile(
                nickname = nickname,
                nicknameLower = lower,
                tag = tag,
                nicknameChangedAtMs = changed,
                avatarId = avatarId ?: AvatarCatalog.FALLBACK_ID,
                termsAcceptedVersion = json["termsAcceptedVersion"].asLooseInt() ?: 0,
                createdAtMs = created,
                updatedAtMs = json["updatedAtMs"].asLooseLong() ?: created,
            )
        }

        /** The tab's field names: score, reached-at, common and special kills. */
        private fun fieldNames(scope: LeaderboardScope): List<String> = when (scope) {
            LeaderboardScope.WEEK -> listOf("weekScore", "weekReachedAtMs", "weekCommonKills", "weekSpecialKills")
            LeaderboardScope.ALL_TIME -> listOf("totalScore", "scoreReachedAtMs", "commonKills", "specialKills")
        }

        /** The runQuery body. Pure. */
        fun leaderboardQuery(scope: LeaderboardScope, weekId: String, limit: Int): Map<String, Any> {
            val (score, reachedAt) = fieldNames(scope)
            val structured = buildMap<String, Any> {
                put("from", listOf(mapOf("collectionId" to "leaderboard")))
                if (scope == LeaderboardScope.WEEK) {
                    put(
                        "where",
                        mapOf(
                            "fieldFilter" to mapOf(
                                "field" to mapOf("fieldPath" to "weekId"),
                                "op" to "EQUAL",
                                "value" to mapOf("stringValue" to weekId),
                            ),
                        ),
                    )
                }
                put(
                    "orderBy",
                    listOf(
                        mapOf("field" to mapOf("fieldPath" to score), "direction" to "DESCENDING"),
                        mapOf("field" to mapOf("fieldPath" to reachedAt), "direction" to "ASCENDING"),
                    ),
                )
                put("limit", limit.coerceIn(1, 100))
            }
            return mapOf("structuredQuery" to structured)
        }

        /**
         * runQuery's array (a `document` per element) → leaderboard rows, with the tab's score and kills.
         * Documents missing fields (the tab's score included) are dropped.
         */
        fun decodeLeaderboard(body: String, scope: LeaderboardScope): List<LeaderboardEntry>? {
            val (score, reachedAt, common, special) = fieldNames(scope)
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
                    score = fields[score]?.integerValue?.toInt() ?: return@mapNotNull null,
                    reachedAtMs = fields[reachedAt]?.integerValue ?: 0L,
                    commonKills = fields[common]?.integerValue?.toInt() ?: 0,
                    specialKills = fields[special]?.integerValue?.toInt() ?: 0,
                )
            }
        }

        /** `myRank`'s result → the all-time rank plus this week's. Without a `week` the week is unranked. Pure. */
        fun decodeMyRank(r: Map<String, Any?>): MyRank {
            val week = (r["week"] as? Map<*, *>)?.let { w ->
                MyRank.Week(
                    weekId = w["weekId"] as? String,
                    listed = w["listed"] as? Boolean ?: false,
                    rank = w["rank"].asLooseInt(),
                    score = w["score"].asLooseInt() ?: 0,
                    commonKills = w["commonKills"].asLooseInt() ?: 0,
                    specialKills = w["specialKills"].asLooseInt() ?: 0,
                )
            } ?: MyRank.Week()
            return MyRank(
                listed = r["listed"] as? Boolean ?: false,
                rank = r["rank"].asLooseInt(),
                stats = MonsterStats.fromJson(r),
                week = week,
            )
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

/** The ranks' tabs. This week starts over at Monday 00:00 KST. */
enum class LeaderboardScope { WEEK, ALL_TIME }

/** One leaderboard row (the public `leaderboard/{uid}` document). Score and kills are the tab's (this week or all time). */
data class LeaderboardEntry(
    val uid: String,
    val nickname: String,
    val tag: String,
    val avatarId: String,
    val score: Int,
    val reachedAtMs: Long,
    val commonKills: Int,
    val specialKills: Int,
)

data class MyRank(
    val listed: Boolean,
    val rank: Int?,
    val stats: MonsterStats,
    /** This week's rank. Unranked (0 points) until the server sends weekly values. */
    val week: Week = Week(),
) {
    data class Week(
        val weekId: String? = null,
        val listed: Boolean = false,
        val rank: Int? = null,
        val score: Int = 0,
        val commonKills: Int = 0,
        val specialKills: Int = 0,
    )
}

/** A number or a decimal string, truncated like iOS `NSNumber.intValue`. */
private fun Any?.asLooseLong(): Long? = when (this) {
    is Number -> toLong()
    is String -> toLongOrNull()
    else -> null
}

private fun Any?.asLooseInt(): Int? = asLooseLong()?.toInt()
