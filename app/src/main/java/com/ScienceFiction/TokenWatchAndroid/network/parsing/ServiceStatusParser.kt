package com.ScienceFiction.TokenWatchAndroid.network.parsing

import com.ScienceFiction.TokenWatchAndroid.domain.ServiceHealth
import com.ScienceFiction.TokenWatchAndroid.domain.StatusPlatform
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi

/** Pure JSON-to-domain parser for supported public service-status platforms. */
object ServiceStatusParser {
    private val jsonAdapter: JsonAdapter<Any> = Moshi.Builder().build().adapter(Any::class.java)

    fun parse(platform: StatusPlatform, data: ByteArray): ServiceHealth {
        val root = parseObject(data) ?: return ServiceHealth.UNKNOWN
        return when (platform) {
            StatusPlatform.ATLASSIAN -> parseAtlassian(root)
            StatusPlatform.INSTATUS -> parseInstatus(root)
            StatusPlatform.BETTERSTACK -> parseBetterStack(root)
        }
    }

    private fun parseAtlassian(root: Map<*, *>): ServiceHealth {
        val indicator = root.nestedString("status", "indicator")?.lowercase()
            ?: return ServiceHealth.UNKNOWN
        return when (indicator) {
            "none" -> ServiceHealth.OPERATIONAL
            "minor" -> ServiceHealth.DEGRADED
            "major", "critical" -> ServiceHealth.MAJOR
            "maintenance" -> ServiceHealth.MAINTENANCE
            else -> ServiceHealth.UNKNOWN
        }
    }

    private fun parseInstatus(root: Map<*, *>): ServiceHealth {
        val status = root.nestedString("page", "status")?.uppercase()
            ?: return ServiceHealth.UNKNOWN
        return when (status) {
            "UP" -> ServiceHealth.OPERATIONAL
            "HASISSUES" -> ServiceHealth.DEGRADED
            "DOWN" -> ServiceHealth.MAJOR
            "UNDERMAINTENANCE" -> ServiceHealth.MAINTENANCE
            else -> ServiceHealth.UNKNOWN
        }
    }

    private fun parseBetterStack(root: Map<*, *>): ServiceHealth {
        val state = root.nestedString("data", "attributes", "aggregate_state")?.lowercase()
            ?: return ServiceHealth.UNKNOWN
        return when (state) {
            "operational" -> ServiceHealth.OPERATIONAL
            "degraded" -> ServiceHealth.DEGRADED
            "downtime" -> ServiceHealth.MAJOR
            "maintenance", "under_maintenance" -> ServiceHealth.MAINTENANCE
            else -> ServiceHealth.UNKNOWN
        }
    }

    private fun parseObject(data: ByteArray): Map<*, *>? = runCatching {
        jsonAdapter.fromJson(data.toString(Charsets.UTF_8)) as? Map<*, *>
    }.getOrNull()

    private fun Map<*, *>.nestedString(vararg path: String): String? {
        var value: Any? = this
        for (key in path) {
            value = (value as? Map<*, *>)?.get(key) ?: return null
        }
        return value as? String
    }
}
