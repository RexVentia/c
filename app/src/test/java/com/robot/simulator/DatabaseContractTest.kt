package com.robot.simulator

import com.robot.simulator.data.local.RouteEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class DatabaseContractTest {
    @Test
    fun routeEntityKeepsMeterPointPayload() {
        val entity = RouteEntity(
            id = "route-1",
            name = "测试路线",
            controlPointsJson = "[[23.0,113.0,0.0],[23.001,113.0,111.2]]",
            lengthM = 111.2,
            createdAtEpochMs = 1L,
            updatedAtEpochMs = 2L,
        )

        assertEquals("route-1", entity.id)
        assertEquals("[[23.0,113.0,0.0],[23.001,113.0,111.2]]", entity.controlPointsJson)
        assertEquals(111.2, entity.lengthM, 0.0)
    }
}
