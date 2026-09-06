package com.ScienceFiction.TokenWatchAndroid.network.announcements

import com.ScienceFiction.TokenWatchAndroid.domain.Announcement
import com.ScienceFiction.TokenWatchAndroid.network.providers.apikey.FakeNetworkTransport
import com.ScienceFiction.TokenWatchAndroid.network.providers.apikey.networkResponse
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnouncementFeedClientTest {
    private val payload = """
        {"schemaVersion":1,"generatedAt":1788652800000,"items":[
          {"id":"a1","kind":"notice","priority":1,"publishedAt":1788652800000,
           "platform":"android","title":{"ko":"제목","en":"Title"}}]}
    """.trimIndent()

    /** Firestore returns the feed as an escaped JSON string inside a document envelope. */
    private fun envelope(inner: String = payload): String {
        val escaped = inner.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        return """{"name":"projects/p/databases/(default)/documents/feeds/android",
            "fields":{"schemaVersion":{"integerValue":"1"},"payload":{"stringValue":"$escaped"}}}"""
    }

    private fun client(transport: FakeNetworkTransport) = AnnouncementFeedClient(
        transport = transport,
        projectId = "tokenwatch-app",
        apiKey = "test-key",
        feedDocument = "android",
        appVersion = "1.1.0",
    )

    @Test fun fetchesThePublicFeedWithCleanHeaders() = runBlocking {
        val transport = FakeNetworkTransport(networkResponse(body = envelope()))
        val result = client(transport).fetch()

        val request = transport.requests.single()
        assertEquals(
            "https://firestore.googleapis.com/v1/projects/tokenwatch-app" +
                "/databases/(default)/documents/feeds/android?key=test-key",
            request.url.toString(),
        )
        assertEquals("application/json", request.header("Accept"))
        assertEquals("TokenWatch/1.1.0", request.header("User-Agent"))
        // The feed is public; sending credentials here would be a leak, not an upgrade.
        assertNull(request.header("Authorization"))

        val feed = (result as AnnouncementFeedClient.FetchResult.Loaded).feed
        assertEquals(listOf("a1"), feed.items.map { it.id })
        assertEquals(Announcement.PLATFORM_ANDROID, feed.items.single().platform)
    }

    @Test fun missingDocumentIsEmptyNotFailure() = runBlocking {
        // 404 means "no announcements yet"; treating it as a failure would trap the store in backoff.
        val transport = FakeNetworkTransport(networkResponse(statusCode = 404, body = """{"error":{}}"""))
        assertTrue(client(transport).fetch() is AnnouncementFeedClient.FetchResult.Empty)
    }

    @Test fun seededButEmptyFeedLoadsAsAnEmptyList() = runBlocking {
        val transport = FakeNetworkTransport(
            networkResponse(body = envelope("""{"schemaVersion":1,"generatedAt":1788690800208,"items":[]}""")),
        )
        val result = client(transport).fetch()
        assertTrue((result as AnnouncementFeedClient.FetchResult.Loaded).feed.items.isEmpty())
    }

    @Test fun serverAndParseProblemsFail() = runBlocking {
        assertTrue(
            client(FakeNetworkTransport(networkResponse(statusCode = 500))).fetch()
                is AnnouncementFeedClient.FetchResult.Failed,
        )
        assertTrue(
            client(FakeNetworkTransport(networkResponse(body = """{"fields":{}}"""))).fetch()
                is AnnouncementFeedClient.FetchResult.Failed,
        )
        assertTrue(
            client(FakeNetworkTransport(networkResponse(body = "not json"))).fetch()
                is AnnouncementFeedClient.FetchResult.Failed,
        )
        assertTrue(
            client(FakeNetworkTransport(networkResponse(body = envelope("""{"schemaVersion":2,"items":[]}"""))))
                .fetch() is AnnouncementFeedClient.FetchResult.Failed,
        )
    }

    @Test fun transportErrorsAreSwallowed() = runBlocking {
        val client = AnnouncementFeedClient(
            transport = { throw IOException("offline") },
            projectId = "tokenwatch-app", apiKey = "test-key",
            feedDocument = "android", appVersion = "1.1.0",
        )
        assertTrue(client.fetch() is AnnouncementFeedClient.FetchResult.Failed)
    }

    @Test fun missingCredentialsDisableTheFeatureWithoutARequest() = runBlocking {
        val transport = FakeNetworkTransport(networkResponse(body = envelope()))
        val client = AnnouncementFeedClient(
            transport = transport, projectId = "tokenwatch-app", apiKey = "",
            feedDocument = "android", appVersion = "1.1.0",
        )
        assertTrue(client.fetch() is AnnouncementFeedClient.FetchResult.Failed)
        assertTrue(transport.requests.isEmpty())
    }
}
