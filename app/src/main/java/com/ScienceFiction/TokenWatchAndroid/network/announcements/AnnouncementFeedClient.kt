package com.ScienceFiction.TokenWatchAndroid.network.announcements

import com.ScienceFiction.TokenWatchAndroid.BuildConfig
import com.ScienceFiction.TokenWatchAndroid.data.AnnouncementCodec
import com.ScienceFiction.TokenWatchAndroid.domain.AnnouncementFeed
import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkTransport
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CancellationException
import okhttp3.Request

/**
 * Reads the announcement feed — a single document — over the Firestore REST API.
 *
 * No Firebase SDK: announcements need neither realtime updates nor an offline cache, so this stays
 * a plain HTTPS+JSON call like [com.ScienceFiction.TokenWatchAndroid.network.status.ServiceStatusClient].
 * Firestore's own encoding (`fields.payload.stringValue`) is confined to this file, so swapping the
 * transport for a CDN or a bespoke API only touches [feedUrl] and [decode].
 *
 * The request is unauthenticated: the security rules allow `get` on `feeds/{id}` and nothing else,
 * and no private data is ever carried in the payload.
 */
class AnnouncementFeedClient(
    private val transport: NetworkTransport,
    private val projectId: String = BuildConfig.ANNOUNCEMENT_PROJECT_ID,
    private val apiKey: String = BuildConfig.ANNOUNCEMENT_API_KEY,
    private val feedDocument: String = BuildConfig.ANNOUNCEMENT_FEED_DOC,
    private val appVersion: String = BuildConfig.VERSION_NAME,
) {
    sealed interface FetchResult {
        data class Loaded(val feed: AnnouncementFeed) : FetchResult

        /** 404 — the feed document does not exist yet. Normal ("no announcements"), not a failure. */
        data object Empty : FetchResult

        /** Transport, status or parse failure. The caller keeps its cache and retries later. */
        data object Failed : FetchResult
    }

    suspend fun fetch(): FetchResult {
        val url = feedUrl() ?: return FetchResult.Failed
        return try {
            val response = transport.execute(
                Request.Builder()
                    .url(url)
                    .get()
                    .header("Accept", "application/json")
                    .header("User-Agent", "TokenWatch/$appVersion")
                    .build(),
            )
            when {
                response.statusCode == 404 -> FetchResult.Empty
                response.statusCode !in 200..299 -> FetchResult.Failed
                else -> decode(response.bodyText())?.let(FetchResult::Loaded) ?: FetchResult.Failed
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            FetchResult.Failed
        }
    }

    /** Unwraps the Firestore document envelope and decodes the payload it carries. */
    internal fun decode(body: String): AnnouncementFeed? {
        val root = runCatching { envelopeAdapter.fromJson(body) }.getOrNull() as? Map<*, *> ?: return null
        val fields = root["fields"] as? Map<*, *> ?: return null
        val payload = (fields["payload"] as? Map<*, *>)?.get("stringValue") as? String ?: return null
        return AnnouncementCodec.decode(payload)
    }

    /** Null disables the feature quietly, mirroring how a missing plist switches it off on iOS. */
    internal fun feedUrl(): String? {
        if (projectId.isEmpty() || apiKey.isEmpty() || feedDocument.isEmpty()) return null
        return "https://firestore.googleapis.com/v1/projects/$projectId" +
            "/databases/(default)/documents/feeds/$feedDocument?key=$apiKey"
    }

    private companion object {
        val envelopeAdapter = Moshi.Builder().build().adapter(Any::class.java)
    }
}
