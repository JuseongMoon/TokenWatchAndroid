package com.ScienceFiction.TokenWatchAndroid.network.parsing

import com.ScienceFiction.TokenWatchAndroid.domain.ServiceHealth
import com.ScienceFiction.TokenWatchAndroid.domain.StatusPlatform
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi

/** Pure component-list parser for supported public service-status platforms. */
object ServiceStatusParser {
    private val jsonAdapter: JsonAdapter<Any> = Moshi.Builder().build().adapter(Any::class.java)

    internal enum class ComponentStatus { OPERATIONAL, MAINTENANCE, DOWN }

    /** Null means malformed/missing status data or an empty component list. */
    fun parse(platform: StatusPlatform, data: ByteArray): ServiceHealth? {
        val root = parseObject(data) ?: return null
        val statuses = when (platform) {
            StatusPlatform.ATLASSIAN -> atlassianComponents(root)
        } ?: return null
        return classify(statuses)
    }

    internal fun classify(statuses: List<ComponentStatus>): ServiceHealth? {
        if (statuses.isEmpty()) return null
        val maintenance = statuses.count { it == ComponentStatus.MAINTENANCE }
        val down = statuses.count { it != ComponentStatus.OPERATIONAL }
        return when {
            maintenance == statuses.size -> ServiceHealth.MAINTENANCE
            down == 0 -> ServiceHealth.OPERATIONAL
            down == statuses.size -> ServiceHealth.TOTAL_OUTAGE
            down * 2 >= statuses.size -> ServiceHealth.MAJOR
            else -> ServiceHealth.CAUTION
        }
    }

    private fun atlassianComponents(root: Map<*, *>): List<ComponentStatus>? {
        val components = root["components"] as? List<*> ?: return null
        return components.mapNotNull { it as? Map<*, *> }
            .filterNot { it["group"] == true }
            .map { component ->
                when ((component["status"] as? String)?.lowercase()) {
                    "operational" -> ComponentStatus.OPERATIONAL
                    "under_maintenance" -> ComponentStatus.MAINTENANCE
                    else -> ComponentStatus.DOWN
                }
            }
    }

    private fun parseObject(data: ByteArray): Map<*, *>? = runCatching {
        jsonAdapter.fromJson(data.toString(Charsets.UTF_8)) as? Map<*, *>
    }.getOrNull()
}
