package com.ScienceFiction.TokenWatchAndroid.domain

/**
 * Announcement feed, served as a single materialized document per platform.
 *
 * Every instant is an epoch-millisecond integer by contract. The server deliberately avoids ISO
 * strings because their fractional seconds break strict parsers, so do not convert these to
 * timestamps on the wire.
 */
data class AnnouncementFeed(
    val schemaVersion: Int,
    val generatedAt: Long? = null,
    val items: List<Announcement> = emptyList(),
) {
    companion object {
        const val SUPPORTED_SCHEMA_VERSION = 1
    }
}

data class Announcement(
    val id: String,
    val kind: Kind = Kind.NOTICE,
    val priority: Int = 0,
    val publishedAt: Long,
    val startAt: Long? = null,
    val endAt: Long? = null,
    /**
     * "android" or "all". Held as a String rather than an enum: the server may add values for other
     * platforms, and an unknown one has to be filtered out instead of failing the whole decode.
     */
    val platform: String = PLATFORM_ALL,
    val minAppVersion: String? = null,
    val maxAppVersion: String? = null,
    val title: LocalizedText = LocalizedText(),
    val body: LocalizedText = LocalizedText(),
) {
    enum class Kind(val wireId: String) {
        NOTICE("notice"),
        PATCH("patch"),
        ;

        /** Chrome label, deliberately untranslated like the rest of the terminal furniture. */
        val chromeLabel: String get() = name

        companion object {
            fun fromWireId(value: String?): Kind = entries.firstOrNull { it.wireId == value } ?: NOTICE
        }
    }

    companion object {
        const val PLATFORM_ANDROID = "android"
        const val PLATFORM_ALL = "all"
    }
}

/** Title/body pair. A missing side falls back to the other language rather than rendering blank. */
data class LocalizedText(val ko: String? = null, val en: String? = null) {
    fun resolved(korean: Boolean): String {
        val preferred = if (korean) ko else en
        val fallback = if (korean) en else ko
        return preferred?.takeIf(String::isNotEmpty)
            ?: fallback?.takeIf(String::isNotEmpty)
            ?: ""
    }
}
