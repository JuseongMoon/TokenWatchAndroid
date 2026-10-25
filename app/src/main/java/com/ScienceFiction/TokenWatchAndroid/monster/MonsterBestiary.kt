package com.ScienceFiction.TokenWatchAndroid.monster

/*
 * The species the profile bestiary lines up (pure), mirroring iOS `MonsterBestiary`: the released species
 * this app can draw, plus any species already defeated, in catalog order. A species not yet released (a
 * limited-time event monster, say) stays hidden, not even a silhouette, until it is defeated.
 */

data class BestiaryRow(val entry: MonsterCatalogEntry, val kills: Int) {
    val id: String get() = entry.id

    /** Defeated at least once: its look and name are shown. */
    val discovered: Boolean get() = kills > 0
}

object MonsterBestiary {
    fun rows(config: MonsterConfig, kills: Map<String, Int>, appVersion: String): List<BestiaryRow> =
        config.catalog.mapNotNull { entry ->
            val count = maxOf(0, kills[entry.id] ?: 0)
            val drawable = entry.released &&
                (entry.minAppVersion?.let { AppVersionCompare.isAtLeast(appVersion, it) } ?: true)
            if (drawable || count > 0) BestiaryRow(entry, count) else null
        }
}
