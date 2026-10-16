package com.ScienceFiction.TokenWatchAndroid.account

import com.ScienceFiction.TokenWatchAndroid.domain.account.PlayerProfile
import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkTransport
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Reads and writes the app account profile (`players/{uid}`, `nicknames/{lower}`) over Firestore REST,
 * mirroring iOS `PlayerProfileClient`.
 *
 * Authentication is the Firebase ID token (`Authorization: Bearer`), fetched right before each request
 * and only ever placed in that header — never stored, logged or put in an exception. No `?key=` is
 * sent. Failures come back as result values rather than exceptions, and response bodies (raw server
 * errors) are neither shown nor logged. A 401 refreshes the token and retries once.
 */
class PlayerProfileClient(
    projectId: String,
    private val transport: NetworkTransport,
    /** ID token supplier; `forceRefresh` drops the cached token. Null when nobody is signed in. */
    private val token: suspend (forceRefresh: Boolean) -> String?,
    val clock: ServerClock = ServerClock(),
) {
    sealed class FetchResult {
        data class Found(val profile: PlayerProfile) : FetchResult()

        /** 404 — no profile yet (first sign-in, or after deletion). */
        data object NotFound : FetchResult()
        data object Failed : FetchResult()
    }

    enum class Availability { AVAILABLE, TAKEN, FAILED }

    enum class CommitResult {
        OK,

        /** The nickname reservation belongs to another account, so the whole commit was refused. */
        TAKEN,
        FAILED,
    }

    val documents = PlayerDocuments(projectId)

    /** Server time (skew-corrected). Plain device time until the skew is known. */
    val nowMs: Long get() = clock.nowMs()
    val knowsServerTime: Boolean get() = clock.offsetMs != null

    suspend fun fetchProfile(uid: String): FetchResult {
        val (status, body) = send("GET", listOf("players", uid)) ?: return FetchResult.Failed
        return when (status) {
            200 -> PlayerDocuments.decodeProfile(body)?.let { FetchResult.Found(it) } ?: FetchResult.Failed
            404 -> FetchResult.NotFound
            else -> FetchResult.Failed
        }
    }

    /** Nickname reservation check. 404 is free, and a reservation held by [uid] itself is available. */
    suspend fun nicknameAvailability(lower: String, uid: String): Availability {
        val (status, body) = send("GET", listOf("nicknames", lower)) ?: return Availability.FAILED
        return when (status) {
            404 -> Availability.AVAILABLE
            200 -> when (PlayerDocuments.decodeNicknameOwner(body)) {
                null -> Availability.FAILED
                uid -> Availability.AVAILABLE
                else -> Availability.TAKEN
            }
            else -> Availability.FAILED
        }
    }

    /**
     * Commits a write batch atomically. Given [nicknameLower], a failure re-reads the reservation to
     * tell `taken` apart: which HTTP status a duplicate reservation produces is not measured on the
     * server yet, so no status code is relied on.
     */
    suspend fun commit(writes: List<FirestoreWrite>, uid: String, nicknameLower: String? = null): CommitResult {
        val response = send("POST", COMMIT, PlayerDocuments.commitBody(writes))
        if (response != null && response.first in 200..299) return CommitResult.OK
        if (nicknameLower != null && nicknameAvailability(nicknameLower, uid) == Availability.TAKEN) {
            return CommitResult.TAKEN
        }
        return CommitResult.FAILED
    }

    /**
     * Makes sure the server skew is known before timestamps are written (±60 s rule). The profile GET
     * right after sign-in usually measures it already.
     */
    suspend fun ensureServerTime(uid: String) {
        if (!knowsServerTime) send("GET", listOf("players", uid))
    }

    /** The full request URL, exposed for tests. Korean document ids are percent-encoded per segment. */
    internal fun url(segments: List<String>): HttpUrl {
        val builder = HttpUrl.Builder()
            .scheme("https")
            .host(HOST)
            .addPathSegment("v1")
            .addPathSegment("projects")
            .addPathSegment(documents.projectId)
            .addPathSegment("databases")
            .addPathSegment("(default)")
        if (segments == COMMIT) {
            builder.addPathSegment("documents:commit")
        } else {
            builder.addPathSegment("documents")
            segments.forEach(builder::addPathSegment)
        }
        return builder.build()
    }

    /** Status and body, or null when nothing came back (no token, transport failure). */
    private suspend fun send(method: String, segments: List<String>, body: String? = null): Pair<Int, String>? {
        val url = url(segments)
        for (attempt in 0..1) {
            val idToken = token(attempt > 0) ?: return null
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $idToken")
                .header("Accept", "application/json")
                .header("Cache-Control", "no-cache")
                .method(method, body?.toRequestBody(JSON))
                .build()
            val response = try {
                transport.execute(request)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                return null
            }
            clock.record(response.header("Date"))
            if (response.statusCode == 401 && attempt == 0) continue
            return response.statusCode to response.bodyText()
        }
        return null
    }

    private companion object {
        const val HOST = "firestore.googleapis.com"
        val COMMIT = listOf(":commit")
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
