package com.ScienceFiction.TokenWatchAndroid.monster

import com.ScienceFiction.TokenWatchAndroid.domain.account.AvatarDecoder
import com.squareup.moshi.Moshi
import kotlin.math.roundToInt

/**
 * Gauge monster and effect sprites. The bundled `assets/monsters-v1.json` ships byte for byte the same
 * as iOS (SHA-256 parity) and the catalog's remote inline sprites (V3) go through the same decoder.
 * They are pixel data, not code. The slime and the blue slime reuse the app's own sprites
 * (`PixelSprite.Slime`/`SlimeSky`).
 *
 * A defeated monster turns its original colors gray (luma Y = 0.299R + 0.587G + 0.114B, × 0.8 — the
 * iOS formula). Right after the defeat a per-monster death animation (`{id}-death`, four frames) plays.
 */

/** A decoded sprite. Colors are 0xRRGGBB, so the domain stays free of Compose types; 0 is transparent. */
data class MonsterSpriteData(
    val cols: Int,
    val rows: Int,
    val frames: List<List<List<Int>>>,
    val palette: Map<Int, Int>,
)

object MonsterSpriteDecoder {
    /** Remote inline sprite limits. */
    const val MAX_SIDE = 24
    const val MAX_FRAMES = 4
    const val MAX_PALETTE_INDEX = 16

    /** The bundled file name. */
    const val BUNDLED_FILE = "monsters-v1.json"
    const val SWORD_ID = "fx-sword"
    const val HALO_ID = "fx-halo"

    private val adapter = Moshi.Builder().build().adapter(Any::class.java)

    /** The bundled file `{schemaVersion, sprites: [{id, palette, frames}]}` → sprites by id. Bad entries are dropped. */
    fun decodeFile(bytes: ByteArray): Map<String, MonsterSpriteData> {
        val root = runCatching { adapter.fromJson(bytes.toString(Charsets.UTF_8)) }.getOrNull() as? Map<*, *>
            ?: return emptyMap()
        val list = root["sprites"] as? List<*> ?: return emptyMap()
        val out = linkedMapOf<String, MonsterSpriteData>()
        for (item in list) {
            val entry = item as? Map<*, *> ?: continue
            val id = (entry["id"] as? String)?.takeIf(String::isNotEmpty) ?: continue
            if (id in out) continue
            out[id] = decode(entry["frames"], entry["palette"]) ?: continue
        }
        return out
    }

    /** A catalog `sprite.inline` — `{cols, rows, frames, palette}`. The declared size must match the grid. */
    fun decodeInline(json: Map<*, *>): MonsterSpriteData? {
        val sprite = decode(json["frames"], json["palette"]) ?: return null
        if (json["cols"].asInt() != sprite.cols || json["rows"].asInt() != sprite.rows) return null
        return sprite
    }

    private fun decode(rawFrames: Any?, rawPalette: Any?): MonsterSpriteData? {
        val frameList = rawFrames as? List<*> ?: return null
        if (frameList.size !in 1..MAX_FRAMES) return null
        val frames = frameList.map { frame ->
            (frame as? List<*>)?.map { row ->
                (row as? List<*>)?.map { it.asInt() ?: return null } ?: return null
            } ?: return null
        }
        val rows = frames.first().size
        val cols = frames.first().firstOrNull()?.size ?: return null
        if (rows !in 1..MAX_SIDE || cols !in 1..MAX_SIDE) return null
        if (!frames.all { frame -> frame.size == rows && frame.all { it.size == cols } }) return null
        val paletteJson = rawPalette as? Map<*, *> ?: return null
        val palette = paletteJson.entries.associate { (key, value) ->
            val index = (key as? String)?.toIntOrNull()?.takeIf { it in 1..MAX_PALETTE_INDEX } ?: return null
            val rgb = AvatarDecoder.parseHex(value as? String) ?: return null
            index to rgb
        }
        for (frame in frames) {
            for (row in frame) {
                for (value in row) {
                    if (value != 0 && value !in palette) return null
                }
            }
        }
        return MonsterSpriteData(cols = cols, rows = rows, frames = frames, palette = palette)
    }

    /** The defeated-state gray (the same formula as iOS). */
    fun grayTone(rgb: Int): Int {
        val r = ((rgb shr 16) and 0xFF).toDouble()
        val g = ((rgb shr 8) and 0xFF).toDouble()
        val b = (rgb and 0xFF).toDouble()
        val y = ((0.299 * r + 0.587 * g + 0.114 * b) * 0.8).roundToInt()
        return (y shl 16) or (y shl 8) or y
    }
}

/**
 * The bundled sprites by id and the lookups the gauge layer makes, mirroring iOS `MonsterSprites`.
 * Pure (colors stay 0xRRGGBB); the UI turns the data into drawable sprites.
 */
class MonsterSpriteSet(val bundled: Map<String, MonsterSpriteData>) {
    /**
     * What draws [monsterId]: the catalog's inline sprite, then the bundled one (the name the catalog
     * points at, or the id). Null means a slime — the app's own slime sprites (see [skySlime]).
     */
    fun body(monsterId: String, entry: MonsterCatalogEntry?): MonsterSpriteData? {
        if (monsterId == SLIME_ID || monsterId == MonsterDraw.SESSION_SLIME_ID) return null
        if (entry == null && bundled[monsterId] == null) return null
        return entry?.inlineSprite ?: bundled[entry?.bundledSprite ?: monsterId] ?: bundled[monsterId]
    }

    /** The per-monster death animation (`{id}-death` — the blue slime's is `slime-sky-death`). Null skips straight to the halo. */
    fun death(monsterId: String): MonsterSpriteData? = bundled["$monsterId-death"]

    val sword: MonsterSpriteData? get() = bundled[MonsterSpriteDecoder.SWORD_ID]
    val halo: MonsterSpriteData? get() = bundled[MonsterSpriteDecoder.HALO_ID]

    companion object {
        const val SLIME_ID = "slime"

        /**
         * Whether a monster drawn as a slime ([body] null) is the sky-blue one: the slime is always green and
         * the blue slime always sky blue; a monster without a drawing is sky blue on a session window only.
         */
        fun skySlime(monsterId: String, sessionWindow: Boolean): Boolean = when (monsterId) {
            SLIME_ID -> false
            MonsterDraw.SESSION_SLIME_ID -> true
            else -> sessionWindow
        }

        fun fromFile(bytes: ByteArray?): MonsterSpriteSet =
            MonsterSpriteSet(bytes?.let(MonsterSpriteDecoder::decodeFile).orEmpty())
    }
}
