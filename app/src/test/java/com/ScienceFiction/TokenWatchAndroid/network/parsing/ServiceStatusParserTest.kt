package com.ScienceFiction.TokenWatchAndroid.network.parsing

import com.ScienceFiction.TokenWatchAndroid.domain.ServiceHealth
import com.ScienceFiction.TokenWatchAndroid.domain.StatusPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServiceStatusParserTest {
    @Test
    fun atlassianIndicatorMapping() {
        assertEquals(ServiceHealth.OPERATIONAL, health(StatusPlatform.ATLASSIAN, """{"status":{"indicator":"none","description":"All Systems Operational"}}"""))
        assertEquals(ServiceHealth.DEGRADED, health(StatusPlatform.ATLASSIAN, """{"status":{"indicator":"minor"}}"""))
        assertEquals(ServiceHealth.MAJOR, health(StatusPlatform.ATLASSIAN, """{"status":{"indicator":"major"}}"""))
        assertEquals(ServiceHealth.MAJOR, health(StatusPlatform.ATLASSIAN, """{"status":{"indicator":"critical"}}"""))
        assertEquals(ServiceHealth.MAINTENANCE, health(StatusPlatform.ATLASSIAN, """{"status":{"indicator":"maintenance"}}"""))
        assertEquals(ServiceHealth.UNKNOWN, health(StatusPlatform.ATLASSIAN, """{"status":{"indicator":"weird"}}"""))
        assertNull(health(StatusPlatform.ATLASSIAN, "{}"))
        assertNull(health(StatusPlatform.ATLASSIAN, "not json"))
    }

    @Test
    fun instatusStatusMapping() {
        assertEquals(ServiceHealth.OPERATIONAL, health(StatusPlatform.INSTATUS, """{"page":{"name":"fal","status":"UP"}}"""))
        assertEquals(ServiceHealth.DEGRADED, health(StatusPlatform.INSTATUS, """{"page":{"status":"HASISSUES"}}"""))
        assertEquals(ServiceHealth.MAINTENANCE, health(StatusPlatform.INSTATUS, """{"page":{"status":"UNDERMAINTENANCE"}}"""))
        assertEquals(ServiceHealth.MAJOR, health(StatusPlatform.INSTATUS, """{"page":{"status":"DOWN"}}"""))
        assertNull(health(StatusPlatform.INSTATUS, """{"page":{}}"""))
    }

    @Test
    fun betterStackStateMapping() {
        assertEquals(ServiceHealth.OPERATIONAL, health(StatusPlatform.BETTERSTACK, """{"data":{"attributes":{"aggregate_state":"operational"}}}"""))
        assertEquals(ServiceHealth.DEGRADED, health(StatusPlatform.BETTERSTACK, """{"data":{"attributes":{"aggregate_state":"degraded"}}}"""))
        assertEquals(ServiceHealth.MAJOR, health(StatusPlatform.BETTERSTACK, """{"data":{"attributes":{"aggregate_state":"downtime"}}}"""))
        assertEquals(ServiceHealth.MAINTENANCE, health(StatusPlatform.BETTERSTACK, """{"data":{"attributes":{"aggregate_state":"maintenance"}}}"""))
        assertNull(health(StatusPlatform.BETTERSTACK, """{"data":{"attributes":{}}}"""))
    }

    private fun health(platform: StatusPlatform, json: String): ServiceHealth? =
        ServiceStatusParser.parse(platform, json.toByteArray())
}
