package com.ScienceFiction.TokenWatchAndroid.domain.account

import com.squareup.moshi.Moshi
import kotlin.random.Random

/**
 * Pixel-art profile avatars for the app account. The canonical JSON (`assets/avatars-v1.json`) ships
 * byte for byte — it is never transcribed into code — and is decoded at runtime. iOS bundles the same
 * file with the same hash.
 *
 * Bundled files are read in order and merged. Publishing more avatars later appends a new file and
 * never changes the bytes (hash) of an existing one; on a duplicate id the earlier file wins.
 *
 * The public list (the selection grid) is kept apart from the operator-only list (drawing only — it
 * never appears in the grid). Operator accounts draw with the operator avatar whatever they picked
 * (see `AccountStore.displayAvatarId`).
 */

/** One decoded avatar. Colors are 0xRRGGBB, so the domain stays free of Compose types. */
data class AvatarPreset(
    val id: String,
    val nameKo: String,
    val nameEn: String,
    val bitmap: List<List<Int>>,
    val palette: Map<Int, Int>,
) {
    fun name(korean: Boolean): String = if (korean) nameKo else nameEn
}

/** Avatar JSON decoder. Malformed entries are dropped; unknown fields are ignored so the format can grow. */
object AvatarDecoder {
    const val GRID = 16

    /** Highest palette index (0 is transparent). The number of colors itself has no cap. */
    const val MAX_PALETTE_INDEX = 16

    private val adapter = Moshi.Builder().build().adapter(Any::class.java)

    fun decode(bytes: ByteArray): List<AvatarPreset> {
        val root = runCatching { adapter.fromJson(bytes.toString(Charsets.UTF_8)) }.getOrNull() as? Map<*, *>
            ?: return emptyList()
        if (root["grid"].asInt() != GRID) return emptyList()
        val avatars = root["avatars"] as? List<*> ?: return emptyList()
        return avatars.mapNotNull { validate(it as? Map<*, *>) }
    }

    private fun validate(entry: Map<*, *>?): AvatarPreset? {
        entry ?: return null
        val id = (entry["id"] as? String)?.takeIf(String::isNotEmpty) ?: return null
        val name = entry["name"] as? Map<*, *> ?: return null
        val nameKo = name["ko"] as? String ?: return null
        val nameEn = name["en"] as? String ?: return null
        val frames = entry["frames"] as? List<*> ?: return null
        if (frames.size != 1) return null
        val rows = frames.first() as? List<*> ?: return null
        if (rows.size != GRID) return null
        val bitmap = rows.map { row ->
            val cells = row as? List<*> ?: return null
            if (cells.size != GRID) return null
            cells.map { it.asInt() ?: return null }
        }
        val rawPalette = entry["palette"] as? Map<*, *> ?: return null
        val palette = rawPalette.entries.associate { (key, value) ->
            val index = (key as? String)?.toIntOrNull()?.takeIf { it in 1..MAX_PALETTE_INDEX } ?: return null
            val rgb = parseHex(value as? String) ?: return null
            index to rgb
        }
        for (row in bitmap) {
            for (value in row) {
                if (value == 0) continue
                if (value !in 1..MAX_PALETTE_INDEX || value !in palette) return null
            }
        }
        return AvatarPreset(id = id, nameKo = nameKo, nameEn = nameEn, bitmap = bitmap, palette = palette)
    }

    /** Only `#RRGGBB`. */
    fun parseHex(value: String?): Int? {
        if (value == null || value.length != 7 || value[0] != '#') return null
        val digits = value.substring(1)
        if (!digits.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        return digits.toInt(16)
    }

    /** Moshi reads JSON numbers as Double; only exact integers count. */
    private fun Any?.asInt(): Int? {
        val number = (this as? Number)?.toDouble() ?: return null
        val int = number.toInt()
        return int.takeIf { it.toDouble() == number }
    }
}

/**
 * The avatar lists, each merged from bundled files in order. [selectable] is the public list shown in
 * the selection grid; [all] adds the operator-only avatars, which are only ever drawn.
 */
class AvatarCatalog(
    val selectable: List<AvatarPreset>,
    exclusive: List<AvatarPreset> = emptyList(),
) {
    /** Public plus operator-only, for drawing. An operator-only id that repeats a public one is dropped. */
    val all: List<AvatarPreset> = selectable + exclusive.filter { avatar -> selectable.none { it.id == avatar.id } }

    val ids: List<String> get() = selectable.map { it.id }

    /** The operator avatar, when its bitmap is bundled. */
    val operatorAvatar: AvatarPreset? get() = all.firstOrNull { it.id == OPERATOR_AVATAR_ID }

    /** Looks an id up; unknown ids (for example from a newer app version) draw as the fallback. */
    fun resolve(id: String): AvatarPreset = all.firstOrNull { it.id == id }
        ?: all.firstOrNull { it.id == FALLBACK_ID }
        ?: all.first()

    /** One avatar picked ahead of time on the sign-up screen. Nothing is written until saving. */
    fun random(random: Random = Random.Default): AvatarPreset = selectable.random(random)

    companion object {
        /** Bundled public asset files, in publication order. These fill the selection grid. */
        val BUNDLED_FILES = listOf("avatars-v1.json")

        /** Operator-only asset files (the crowned slime): drawn, never offered in the grid. */
        val EXCLUSIVE_FILES = listOf("avatars-operator.json")

        /** The operator avatar id. */
        const val OPERATOR_AVATAR_ID = "operator"

        /** Drawn for unknown ids; also what non-preset server avatars decode to. */
        const val FALLBACK_ID = "slime"

        /** Merges decoded files in order; on a duplicate id the earlier file wins. */
        fun fromFiles(files: List<ByteArray>, exclusiveFiles: List<ByteArray> = emptyList()): AvatarCatalog {
            // A broken public bundle still draws something (the unit tests catch it before release).
            return AvatarCatalog(
                selectable = merge(files).ifEmpty { listOf(BUILT_IN_FALLBACK) },
                exclusive = merge(exclusiveFiles),
            )
        }

        private fun merge(files: List<ByteArray>): List<AvatarPreset> {
            val seen = mutableSetOf<String>()
            return files.flatMap(AvatarDecoder::decode).filter { seen.add(it.id) }
        }

        private val BUILT_IN_FALLBACK = AvatarPreset(
            id = FALLBACK_ID,
            nameKo = "슬라임",
            nameEn = "Slime",
            bitmap = List(AvatarDecoder.GRID) { row ->
                List(AvatarDecoder.GRID) { column -> if (row in 6..13 && column in 3..12) 1 else 0 }
            },
            palette = mapOf(1 to 0x47D966),
        )
    }
}
