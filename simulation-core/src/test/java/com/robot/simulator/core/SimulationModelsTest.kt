package com.robot.simulator.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimulationModelsTest {
    @Test
    fun variableCadenceIsRepeatableBoundedAndEndsExactly() {
        val config = SimulationConfig(route, 1000, 140.0, 42L, 8.0, 10.0)
        val engine = DeterministicSimulationEngine()
        var previous = 0
        for (time in 0L until config.durationMs step 100L) {
            val snapshot = engine.snapshot(config, time)
            assertTrue(snapshot.cadenceSpm in 132.0..148.0)
            assertTrue(snapshot.stepCount >= previous)
            assertEquals(snapshot, engine.snapshot(config, time))
            previous = snapshot.stepCount
        }
        val end = engine.snapshot(config, config.durationMs + 1000)
        assertEquals(1000, end.stepCount)
        assertEquals(route.controlPoints.last(), end.position)
        assertEquals(0.0, end.cadenceSpm, 0.0)
        assertTrue(engine.snapshot(config, 5000) != engine.snapshot(config.copy(randomSeed = 600), 5000))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsCadenceVariationOutsideBounds() {
        SimulationConfig(route, 1000, 151.0, cadenceVariationSpm = 1.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidCadencePeriod() {
        SimulationConfig(route, 1000, 140.0, cadencePeriodSeconds = Double.NaN)
    }

    private val route = RouteDefinition(
        id = "route-1",
        name = "测试路线",
        controlPoints = listOf(
            PointM(23.0, 113.0, 0.0),
            PointM(23.01, 113.0, 1_200.0),
        ),
        lengthM = 1_200.0,
    )

    @Test
    fun cadenceBoundariesAreInclusive() {
        assertFalse(SimulationRules.validate(route, 1_000, 119.0).valid)
        assertTrue(SimulationRules.validate(route, 1_000, 120.0).valid)
        assertTrue(SimulationRules.validate(route, 1_100, 151.0).valid)
        assertFalse(SimulationRules.validate(route, 1_000, 152.0).valid)
    }

    @Test
    fun speedBoundariesAndSuggestedStepsAreExact() {
        val range = SimulationRules.suggestedSteps(route.lengthM, 120.0)
        assertEquals(StepRange(480, 1_200), range)
        assertEquals(5.0, SimulationConfig(route, 480, 120.0).speedMps, 0.0001)
        assertEquals(2.0, SimulationConfig(route, 1_200, 120.0).speedMps, 0.0001)
        assertTrue(SimulationRules.validate(route, 480, 120.0).valid)
        assertTrue(SimulationRules.validate(route, 1_200, 120.0).valid)
        assertTrue(SimulationRules.validate(route, 600, 120.0).valid)
        assertFalse(SimulationRules.validate(route, 479, 120.0).valid)
        assertFalse(SimulationRules.validate(route, 1_201, 120.0).valid)
    }

    @Test
    fun engineInterpolatesAndFinishesAtExactEndpointAndStepCount() {
        val config = SimulationConfig(route, 1_000, 120.0, randomSeed = 42L)
        val engine = DeterministicSimulationEngine()
        val middle = engine.snapshot(config, config.durationMs / 2)
        val end = engine.snapshot(config, config.durationMs + 5_000)

        assertTrue(middle.progress in 0.45..0.55)
        assertEquals(500, middle.stepCount)
        assertEquals(route.controlPoints.last().latitude, end.position.latitude, 0.0)
        assertEquals(route.controlPoints.last().longitude, end.position.longitude, 0.0)
        assertEquals(1_000, end.stepCount)
        assertEquals(SimulationStatus.Completed, end.status)
    }

    @Test
    fun instantaneousSpeedVariesButDistanceRemainsMonotonic() {
        val config = SimulationConfig(route, 1_000, 120.0, randomSeed = 42L)
        val engine = DeterministicSimulationEngine()
        val first = engine.snapshot(config, 2_000)
        val second = engine.snapshot(config, 4_000)

        assertTrue(first.speedMps != second.speedMps)
        assertTrue(first.speedMps in config.speedMps * 0.85..config.speedMps * 1.15)
        assertTrue(second.distanceM > first.distanceM)
        assertEquals(0.0, engine.snapshot(config, config.durationMs).speedMps, 0.0)
    }

    @Test
    fun sensorSignalIsDeterministicAndSettlesAtBothEnds() {
        val config = SimulationConfig(route, 1_000, 120.0, randomSeed = 42L)
        val engine = DeterministicSimulationEngine()
        val start = engine.snapshot(config, 0).sensor
        val moving = engine.snapshot(config, 2_125).sensor
        val repeated = engine.snapshot(config, 2_125).sensor
        val end = engine.snapshot(config, config.durationMs).sensor

        assertEquals(9.80665, start.accelerometerZ, 0.00001)
        assertEquals(moving, repeated)
        assertTrue(kotlin.math.abs(moving.accelerometerZ - 9.80665) > 0.1)
        assertEquals(9.80665, end.accelerometerZ, 0.00001)
    }

    @Test
    fun geographicRouteCalculatesAndPersistsCumulativeDistance() {
        val points = GeoMath.withCumulativeDistances(
            listOf(PointM(23.0, 113.0), PointM(23.001, 113.0)),
        )
        assertEquals(111.2, points.last().cumulativeDistanceM, 0.5)
        assertEquals(points, RoutePointCodec.decode(RoutePointCodec.encode(points)))
    }
}
