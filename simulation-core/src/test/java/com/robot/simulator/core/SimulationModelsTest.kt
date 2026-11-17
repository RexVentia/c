package com.robot.simulator.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimulationModelsTest {
    // 1.2 km two-point route: keeps average speeds in the realistic 2–5 m/s range.
    private val route = RouteDefinition(
        id = "route-1",
        name = "测试路线",
        controlPoints = listOf(
            PointM(23.0, 113.0, 0.0),
            PointM(23.01, 113.0, 1_200.0),
        ),
        lengthM = 1_200.0,
    )

    // 3 km route with a control point per kilometer: needed for per-km segment checks.
    private val route3k = RouteDefinition(
        id = "route-3k",
        name = "三公里测试路线",
        controlPoints = listOf(
            PointM(23.0, 113.0, 0.0),
            PointM(23.006, 113.0, 1_000.0),
            PointM(23.012, 113.0, 2_000.0),
            PointM(23.018, 113.0, 3_000.0),
        ),
        lengthM = 3_000.0,
    )

    @Test
    fun segmentVariationIsRepeatableBoundedAndEndsExactly() {
        val config = SimulationConfig(
            route = route3k,
            totalSteps = 1_000,
            cadenceSpm = 140.0,
            randomSeed = 42L,
            cadenceVariationSpm = 8.0,
            speedVariationPercent = 10.0,
        )
        val engine = DeterministicSimulationEngine()
        var previous = 0
        for (time in 0L until config.durationMs step 100L) {
            val snapshot = engine.snapshot(config, time)
            assertTrue("cadence ${snapshot.cadenceSpm} out of 132..148", snapshot.cadenceSpm in 132.0..148.0)
            assertTrue(snapshot.stepCount >= previous)
            assertEquals(snapshot, engine.snapshot(config, time))
            previous = snapshot.stepCount
        }
        val end = engine.snapshot(config, config.durationMs + 1000)
        assertEquals(1_000, end.stepCount)
        assertEquals(route3k.controlPoints.last(), end.position)
        assertEquals(0.0, end.cadenceSpm, 0.0)
        assertTrue(engine.snapshot(config, 5_000) != engine.snapshot(config.copy(randomSeed = 600), 5_000))
    }

    @Test
    fun cadenceAndPaceVaryBySegmentAroundAverages() {
        val config = SimulationConfig(
            route = route3k,
            totalSteps = 2_000,
            cadenceSpm = 140.0,
            randomSeed = 42L,
            cadenceVariationSpm = 8.0,
            speedVariationPercent = 12.0,
        )
        val engine = DeterministicSimulationEngine()
        val segments = engine.segmentStats(config)
        assertTrue("expected several segments, got ${segments.size}", segments.size >= 3)
        segments.forEach {
            assertTrue("segment cadence ${it.cadenceSpm}", it.cadenceSpm in 132.0..148.0)
        }
        // Segments must actually differ, both in cadence and pace.
        assertTrue("cadence should vary per segment: ${segments.map { it.cadenceSpm }}",
            segments.map { it.cadenceSpm }.toSet().size > 1)
        assertTrue("pace should vary per segment: ${segments.map { it.paceSecondsPerKm }}",
            segments.map { it.paceSecondsPerKm }.toSet().size > 1)
        // Time-weighted cadence across the whole route stays on the configured average
        // (steps per second must average out to the configured cadence).
        var totalSeconds = 0.0
        var weightedSteps = 0.0
        segments.forEach { segment ->
            val seconds = (segment.endM - segment.startM) / segment.speedMps.coerceAtLeast(0.01)
            totalSeconds += seconds
            weightedSteps += segment.cadenceSpm / 60.0 * seconds
        }
        val timeWeightedCadence = weightedSteps / totalSeconds * 60.0
        assertEquals(140.0, timeWeightedCadence, 0.6)
    }

    @Test
    fun zeroSpeedVariationKeepsPerKmPaceFlatButMicroRippleRemains() {
        val engine = DeterministicSimulationEngine()
        val flatSegments = engine.segmentStats(
            SimulationConfig(route3k, 2_000, 140.0, randomSeed = 7L,
                cadenceVariationSpm = 0.0, speedVariationPercent = 0.0),
        )
        val bumpySegments = engine.segmentStats(
            SimulationConfig(route3k, 2_000, 140.0, randomSeed = 7L,
                cadenceVariationSpm = 0.0, speedVariationPercent = 10.0),
        )
        val flatPaces = flatSegments.map { it.paceSecondsPerKm }
        val bumpyPaces = bumpySegments.map { it.paceSecondsPerKm }
        // With 0% speed variation, per-km pace is essentially constant (micro ripple cancels out
        // over a kilometer). With 10% it differs clearly between segments.
        assertTrue("flat paces nearly equal: $flatPaces", flatPaces.max() - flatPaces.min() < 5.0)
        assertTrue("bumpy paces clearly differ: $bumpyPaces", bumpyPaces.max() - bumpyPaces.min() > 20.0)
        // A different seed yields a different per-segment cadence arrangement.
        val other = engine.segmentStats(
            SimulationConfig(route3k, 2_000, 140.0, randomSeed = 600L,
                cadenceVariationSpm = 8.0, speedVariationPercent = 10.0),
        )
        val otherCadences = other.map { it.cadenceSpm }
        assertTrue("different seed should rearrange cadence", otherCadences.toSet().size > 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsCadenceVariationOutsideBounds() {
        SimulationConfig(route, 1_000, 151.0, cadenceVariationSpm = 1.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidSpeedVariation() {
        SimulationConfig(route, 1_000, 140.0, speedVariationPercent = Double.NaN)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsSpeedVariationAboveLimit() {
        SimulationConfig(route, 1_000, 140.0, speedVariationPercent = 21.0)
    }

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

    @Test
    fun engineInstancesProduceIdenticalResults() {
        val config = SimulationConfig(
            route3k, 2_000, 140.0, randomSeed = 42L, cadenceVariationSpm = 6.0, speedVariationPercent = 9.0,
        )
        val first = DeterministicSimulationEngine().snapshot(config, 123_456)
        val second = DeterministicSimulationEngine().snapshot(config, 123_456)
        assertEquals(first, second)
    }
}
