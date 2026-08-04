package com.ScienceFiction.TokenWatchAndroid.network.parsing

import com.ScienceFiction.TokenWatchAndroid.domain.ServiceHealth
import com.ScienceFiction.TokenWatchAndroid.domain.StatusPlatform
import com.ScienceFiction.TokenWatchAndroid.network.parsing.ServiceStatusParser.ComponentStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServiceStatusParserTest {
    @Test
    fun classifyThresholds() {
        assertEquals(ServiceHealth.OPERATIONAL, classify(ComponentStatus.OPERATIONAL, ComponentStatus.OPERATIONAL))
        assertEquals(ServiceHealth.CAUTION, classify(ComponentStatus.DOWN, ComponentStatus.OPERATIONAL, ComponentStatus.OPERATIONAL))
        assertEquals(ServiceHealth.MAJOR, classify(ComponentStatus.DOWN, ComponentStatus.DOWN, ComponentStatus.OPERATIONAL))
        assertEquals(ServiceHealth.MAJOR, classify(ComponentStatus.DOWN, ComponentStatus.DOWN, ComponentStatus.OPERATIONAL, ComponentStatus.OPERATIONAL))
        assertEquals(ServiceHealth.TOTAL_OUTAGE, classify(ComponentStatus.DOWN, ComponentStatus.DOWN))
        assertEquals(ServiceHealth.MAINTENANCE, classify(ComponentStatus.MAINTENANCE, ComponentStatus.MAINTENANCE))
        assertEquals(ServiceHealth.MAJOR, classify(ComponentStatus.MAINTENANCE, ComponentStatus.MAINTENANCE, ComponentStatus.OPERATIONAL))
        assertEquals(ServiceHealth.CAUTION, classify(ComponentStatus.MAINTENANCE, ComponentStatus.OPERATIONAL, ComponentStatus.OPERATIONAL))
        assertNull(ServiceStatusParser.classify(emptyList()))
    }

    @Test
    fun smallComponentCountsUseTheSameThresholds() {
        assertEquals(ServiceHealth.OPERATIONAL, classify(ComponentStatus.OPERATIONAL))
        assertEquals(ServiceHealth.TOTAL_OUTAGE, classify(ComponentStatus.DOWN))
        assertEquals(ServiceHealth.MAINTENANCE, classify(ComponentStatus.MAINTENANCE))
        assertEquals(ServiceHealth.MAJOR, classify(ComponentStatus.DOWN, ComponentStatus.OPERATIONAL))
    }

    @Test
    fun parsesAtlassianLeafComponents() {
        assertEquals(
            ServiceHealth.CAUTION,
            health(StatusPlatform.ATLASSIAN, """{"components":[{"status":"operational","group":false},{"status":"operational","group":false},{"status":"partial_outage","group":false}]}"""),
        )
        assertEquals(
            ServiceHealth.OPERATIONAL,
            health(StatusPlatform.ATLASSIAN, """{"components":[{"status":"major_outage","group":true},{"status":"operational","group":false}]}"""),
        )
        assertEquals(ServiceHealth.MAINTENANCE, health(StatusPlatform.ATLASSIAN, """{"components":[{"status":"under_maintenance"}]}"""))
        assertNull(health(StatusPlatform.ATLASSIAN, "{}"))
        assertNull(health(StatusPlatform.ATLASSIAN, "not json"))
    }

    private fun classify(vararg statuses: ComponentStatus): ServiceHealth? =
        ServiceStatusParser.classify(statuses.toList())

    private fun health(platform: StatusPlatform, json: String): ServiceHealth? =
        ServiceStatusParser.parse(platform, json.toByteArray())
}
