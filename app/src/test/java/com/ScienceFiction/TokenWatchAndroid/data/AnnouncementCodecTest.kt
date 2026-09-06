package com.ScienceFiction.TokenWatchAndroid.data

import com.ScienceFiction.TokenWatchAndroid.domain.Announcement
import com.ScienceFiction.TokenWatchAndroid.domain.AnnouncementFeed
import com.ScienceFiction.TokenWatchAndroid.domain.LocalizedText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnouncementCodecTest {
    @Test
    fun lenientDecodingDropsOnlyTheUnusableEntry() {
        val feed = AnnouncementCodec.decode(
            """
            {"schemaVersion":1,"generatedAt":1788652800000,"items":[
              {"id":"p1","kind":"patch","priority":3,"publishedAt":1788652800000,
               "platform":"android","title":{"ko":"제목","en":"Title"},"futureField":42},
              {"kind":"notice","publishedAt":1788652800000,"title":{"en":"No id"}},
              {"id":"n1","kind":"link","publishedAt":1788652700000,
               "startAt":null,"endAt":null,"body":{"en":"Body"}}
            ]}
            """.trimIndent(),
        )

        val items = feed?.items.orEmpty()
        assertEquals(listOf("p1", "n1"), items.map { it.id })
        assertEquals(Announcement.Kind.PATCH, items[0].kind)
        assertEquals(3, items[0].priority)
        // Unknown kinds degrade rather than failing the entry, and the defaults hold.
        assertEquals(Announcement.Kind.NOTICE, items[1].kind)
        assertEquals(0, items[1].priority)
        assertEquals(Announcement.PLATFORM_ALL, items[1].platform)
        assertNull(items[1].startAt)
    }

    @Test
    fun unsupportedSchemaVersionDiscardsTheFeed() {
        assertNull(AnnouncementCodec.decode("""{"schemaVersion":2,"items":[]}"""))
        assertNull(AnnouncementCodec.decode("""{"items":[]}"""))
        assertNull(AnnouncementCodec.decode("not json"))
        assertNull(AnnouncementCodec.decode(null))
        assertTrue(AnnouncementCodec.decode("""{"schemaVersion":1,"items":[]}""")!!.items.isEmpty())
    }

    @Test
    fun cacheRoundTripPreservesEveryField() {
        val feed = AnnouncementFeed(
            schemaVersion = 1,
            generatedAt = 1788652800000,
            items = listOf(
                Announcement(
                    id = "a", kind = Announcement.Kind.PATCH, priority = 7,
                    publishedAt = 1788652800000, startAt = 1788652000000, endAt = 1788653000000,
                    platform = Announcement.PLATFORM_ALL,
                    minAppVersion = "1.0.0", maxAppVersion = "2.0.0",
                    title = LocalizedText("제목", "Title"), body = LocalizedText("본문", "Body"),
                ),
                Announcement(id = "b", publishedAt = 1788652700000),
            ),
        )
        assertEquals(feed, AnnouncementCodec.decode(AnnouncementCodec.encode(feed)))
    }

    @Test
    fun idListsRoundTripAndSurviveGarbage() {
        assertEquals(listOf("a", "b", "c"), AnnouncementCodec.decodeIds(AnnouncementCodec.encodeIds(listOf("a", "b", "c"))))
        assertEquals(emptyList<String>(), AnnouncementCodec.decodeIds(null))
        assertEquals(emptyList<String>(), AnnouncementCodec.decodeIds("{}"))
        assertEquals(listOf("a"), AnnouncementCodec.decodeIds("""["a",7,null]"""))
    }
}
