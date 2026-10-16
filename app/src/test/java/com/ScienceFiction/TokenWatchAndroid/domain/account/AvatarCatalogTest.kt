package com.ScienceFiction.TokenWatchAndroid.domain.account

import java.io.File
import java.security.MessageDigest
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AvatarCatalogTest {
    /** Unit tests run from the module directory; AssetManager is not available on the JVM. */
    private val bundled = File("src/main/assets/avatars-v1.json").readBytes()

    private val expectedIds = listOf(
        "warrior", "mage", "archer", "rogue", "cleric", "knight", "bard", "hacker",
        "slime", "slime-sky", "goblin", "skeleton", "robot", "cat", "dragon", "mimic",
    )

    @Test fun bundledFileIsByteIdenticalToIos() {
        // The same constant is asserted by the iOS tests. Changing the file changes both.
        val digest = MessageDigest.getInstance("SHA-256").digest(bundled).joinToString("") { "%02x".format(it) }
        assertEquals("a71c42a62000e3177983f101f1e757cb44d039ec3cae7a940d3d1396a9deffe1", digest)
        assertEquals(listOf("avatars-v1.json"), AvatarCatalog.BUNDLED_FILES)
    }

    @Test fun bundledFileDecodesAllSixteenInOrder() {
        val catalog = AvatarCatalog.fromFiles(listOf(bundled))
        assertEquals(expectedIds, catalog.ids)
        catalog.all.forEach { avatar ->
            assertEquals(16, avatar.bitmap.size)
            assertTrue(avatar.bitmap.all { it.size == 16 })
            assertTrue(avatar.nameKo.isNotBlank() && avatar.nameEn.isNotBlank())
            // Ids are lowercase words joined by hyphens.
            assertTrue(avatar.id.matches(Regex("^[a-z]+(-[a-z]+)*$")))
        }
    }

    @Test fun unknownIdsFallBackToSlime() {
        val catalog = AvatarCatalog.fromFiles(listOf(bundled))
        assertEquals("slime", catalog.resolve("photo").id)
        assertEquals("slime", catalog.resolve("").id)
        assertEquals("dragon", catalog.resolve("dragon").id)
    }

    @Test fun randomPicksFromThePublishedList() {
        val catalog = AvatarCatalog.fromFiles(listOf(bundled))
        val random = Random(7)
        repeat(50) { assertTrue(catalog.random(random).id in expectedIds) }
    }

    @Test fun brokenBundleStillDrawsTheFallback() {
        val catalog = AvatarCatalog.fromFiles(listOf("not json".toByteArray()))
        assertEquals(listOf("slime"), catalog.ids)
        assertEquals("slime", catalog.resolve("warrior").id)
    }

    @Test fun registryMergesFilesInOrderAndKeepsTheFirstDuplicate() {
        val first = file(entry("a", nameEn = "first"), entry("b"))
        val second = file(entry("b", nameEn = "second"), entry("c"))
        val catalog = AvatarCatalog.fromFiles(listOf(first, second))
        assertEquals(listOf("a", "b", "c"), catalog.ids)
        assertEquals("B", catalog.resolve("b").nameEn)
    }

    @Test fun decoderAcceptsAnyColorCountAndSparsePaletteKeys() {
        val sixteen = (1..16).associate { it.toString() to "#0000%02X".format(it) }
        val wide = entry("wide", palette = sixteen, fill = 16)
        val sparse = entry("sparse", palette = mapOf("1" to "#111111", "6" to "#666666"), fill = 6)
        assertEquals(listOf("wide", "sparse"), AvatarDecoder.decode(file(wide, sparse)).map { it.id })
    }

    @Test fun decoderDropsMalformedEntries() {
        val bad = listOf(
            entry("wide17", columns = 17),
            entry("twoFrames", frames = 2),
            entry("key0", palette = mapOf("0" to "#000000", "1" to "#111111")),
            entry("key17", palette = mapOf("1" to "#111111", "17" to "#171717")),
            entry("value17", palette = mapOf("1" to "#111111"), fill = 17),
            entry("missingColor", palette = mapOf("1" to "#111111"), fill = 2),
            entry("shortHex", palette = mapOf("1" to "#123")),
        )
        assertEquals(listOf("ok"), AvatarDecoder.decode(file(*(bad + entry("ok")).toTypedArray())).map { it.id })
    }

    @Test fun decoderRejectsAnotherGridAndIgnoresUnknownFields() {
        val body = """{"schemaVersion":2,"grid":24,"avatars":[${entry("a")}]}"""
        assertTrue(AvatarDecoder.decode(body.toByteArray()).isEmpty())
        val extra = """{"schemaVersion":9,"grid":16,"future":true,"avatars":[${entry("a", extra = ",\"anim\":{}")}]}"""
        assertEquals(listOf("a"), AvatarDecoder.decode(extra.toByteArray()).map { it.id })
    }

    @Test fun hexParsingAcceptsOnlySixDigits() {
        assertEquals(0x12ABEF, AvatarDecoder.parseHex("#12abef"))
        assertEquals(null, AvatarDecoder.parseHex("12ABEF"))
        assertEquals(null, AvatarDecoder.parseHex("#12ABEG"))
        assertEquals(null, AvatarDecoder.parseHex("#FFF"))
    }

    private fun file(vararg entries: String): ByteArray =
        """{"schemaVersion":1,"grid":16,"avatars":[${entries.joinToString(",")}]}""".toByteArray()

    private fun entry(
        id: String,
        nameEn: String = id.uppercase(),
        palette: Map<String, String> = mapOf("1" to "#47D966"),
        fill: Int = 1,
        columns: Int = 16,
        frames: Int = 1,
        extra: String = "",
    ): String {
        val row = (0 until columns).joinToString(",", "[", "]") { if (it == 3) fill.toString() else "0" }
        val frame = (0 until 16).joinToString(",", "[", "]") { row }
        val paletteJson = palette.entries.joinToString(",", "{", "}") { (k, v) -> "\"$k\":\"$v\"" }
        val framesJson = (0 until frames).joinToString(",", "[", "]") { frame }
        return """{"id":"$id","name":{"ko":"$id","en":"$nameEn"},"palette":$paletteJson,"frames":$framesJson$extra}"""
    }
}
