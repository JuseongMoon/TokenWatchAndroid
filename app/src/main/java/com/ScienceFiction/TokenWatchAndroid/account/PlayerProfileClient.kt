package com.ScienceFiction.TokenWatchAndroid.account

import com.ScienceFiction.TokenWatchAndroid.domain.account.PlayerProfile
import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkTransport
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl
import okhttp3.Request

/**
 * Reads the app account profile (`players/{uid}`) over Firestore REST, mirroring iOS
 * `PlayerProfileClient`. Writes go through the V2 server callable (`setProfile`).
 *
 * Authentication is the Firebase ID token (`Authorization: Bearer`), fetched right before each request
 * and only ever placed in that header — never stored, logged or put in an exception. No `?key=` is
 * sent. Failures come back as result values rather than exceptions, and response bodies (raw server
 * errors) are neither shown nor logged. A 401 or 403 refreshes the token and retries once — 403 covers an
 * operator claim set after the cached token was issued.
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

    val documents = PlayerDocuments(projectId)

    /** Server time (skew-corrected). Plain device time until the skew is known. */
    val nowMs: Long get() = clock.nowMs()
    val knowsServerTime: Boolean get() = clock.offsetMs != null

    suspend fun fetchProfile(uid: String): FetchResult {
        val (status, body) = get(listOf("players", uid)) ?: return FetchResult.Failed
        return when (status) {
            200 -> PlayerDocuments.decodeProfile(body)?.let { FetchResult.Found(it) } ?: FetchResult.Failed
            404 -> FetchResult.NotFound
            else -> FetchResult.Failed
        }
    }

    /**
     * Makes sure the server skew is known, so the 30-day nickname interval is checked on the server's
     * clock. The profile GET right after sign-in usually measures it already.
     */
    suspend fun ensureServerTime(uid: String) {
        if (!knowsServerTime) get(listOf("players", uid))
    }

    /** The full request URL, exposed for tests. Document ids are percent-encoded per segment. */
    internal fun url(segments: List<String>): HttpUrl {
        val builder = HttpUrl.Builder()
            .scheme("https")
            .host(HOST)
            .addPathSegment("v1")
            .addPathSegment("projects")
            .addPathSegment(documents.projectId)
            .addPathSegment("databases")
            .addPathSegment("(default)")
            .addPathSegment("documents")
        segments.forEach(builder::addPathSegment)
        return builder.build()
    }

    /** A `documents/…` GET: status and body, or null when nothing came back (no token, transport failure). */
    private suspend fun get(segments: List<String>): Pair<Int, String>? {
        val url = url(segments)
        for (attempt in 0..1) {
            val idToken = token(attempt > 0) ?: return null
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $idToken")
                .header("Accept", "application/json")
                .header("Cache-Control", "no-cache")
                .get()
                .build()
            val response = try {
                transport.execute(request)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                return null
            }
            clock.record(response.header("Date"))
            if ((response.statusCode == 401 || response.statusCode == 403) && attempt == 0) continue
            return response.statusCode to response.bodyText()
        }
        return null
    }

    private companion object {
        const val HOST = "firestore.googleapis.com"
    }
}
