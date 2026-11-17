package com.robot.simulator.core

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

data class PointM(
    val latitude: Double,
    val longitude: Double,
    val cumulativeDistanceM: Double = 0.0,
)

data class RouteDefinition(
    val id: String,
    val name: String,
    val controlPoints: List<PointM>,
    val lengthM: Double = controlPoints.lastOrNull()?.cumulativeDistanceM ?: 0.0,
)

data class SimulationConfig(
    val route: RouteDefinition,
    val totalSteps: Int,
    val cadenceSpm: Double,
    val randomSeed: Long = 1L,
    val cadenceVariationSpm: Double = 0.0,
    val speedVariationPercent: Double = 0.0,
) {
    init {
        require(cadenceSpm in 120.0..151.0)
        require(cadenceVariationSpm.isFinite() && cadenceVariationSpm >= 0.0 &&
            cadenceVariationSpm <= minOf(cadenceSpm - 120.0, 151.0 - cadenceSpm))
        require(speedVariationPercent.isFinite() && speedVariationPercent in 0.0..20.0)
    }
    val durationMs: Long
        get() = (totalSteps * 60_000.0 / cadenceSpm).toLong().coerceAtLeast(1L)
    val speedMps: Double
        get() = route.lengthM * cadenceSpm / (totalSteps * 60.0)
}

data class StepRange(val minimum: Int, val maximum: Int)

data class SimulationValidation(
    val valid: Boolean,
    val message: String?,
    val speedMps: Double?,
    val suggestedSteps: StepRange?,
)

object SimulationRules {
    const val MIN_CADENCE_SPM = 120.0
    const val MAX_CADENCE_SPM = 151.0
    const val MIN_SPEED_MPS = 2.0
    const val MAX_SPEED_MPS = 5.0
    const val MAX_SPEED_VARIATION_PERCENT = 20.0

    fun suggestedSteps(lengthM: Double, cadenceSpm: Double): StepRange {
        val minimum = ceil(lengthM * cadenceSpm / (60.0 * MAX_SPEED_MPS)).toInt().coerceAtLeast(1)
        val maximum = floor(lengthM * cadenceSpm / (60.0 * MIN_SPEED_MPS)).toInt().coerceAtLeast(minimum)
        return StepRange(minimum, maximum)
    }

    fun validate(route: RouteDefinition?, totalSteps: Int?, cadenceSpm: Double?): SimulationValidation {
        if (route == null) return SimulationValidation(false, "请选择路线", null, null)
        if (totalSteps == null || totalSteps <= 0) {
            return SimulationValidation(false, "总步数必须是正整数", null, null)
        }
        if (cadenceSpm == null || cadenceSpm !in MIN_CADENCE_SPM..MAX_CADENCE_SPM) {
            return SimulationValidation(false, "步频必须在 120–151 步/分钟", null, null)
        }
        val range = suggestedSteps(route.lengthM, cadenceSpm)
        val config = SimulationConfig(route, totalSteps, cadenceSpm)
        if (config.speedMps !in MIN_SPEED_MPS..MAX_SPEED_MPS) {
            return SimulationValidation(
                false,
                "当前平均速度 %.2f m/s，不在 2–5 m/s；建议步数 %d–%d"
                    .format(config.speedMps, range.minimum, range.maximum),
                config.speedMps,
                range,
            )
        }
        return SimulationValidation(true, null, config.speedMps, range)
    }
}

enum class SimulationStatus { Ready, Running, Paused, Stopped, Completed }

data class MotionSensorSample(
    val elapsedMs: Long,
    val accelerometerX: Double,
    val accelerometerY: Double,
    val accelerometerZ: Double,
    val gyroscopeX: Double,
    val gyroscopeY: Double,
    val gyroscopeZ: Double,
    val gravityX: Double,
    val gravityY: Double,
    val gravityZ: Double,
    val rollDeg: Double,
    val pitchDeg: Double,
    val yawDeg: Double,
)

data class SimulationSnapshot(
    val elapsedMs: Long,
    val remainingMs: Long,
    val stepCount: Int,
    val totalSteps: Int,
    val cadenceSpm: Double,
    val position: PointM,
    val headingDeg: Double,
    val distanceM: Double,
    val speedMps: Double,
    val progress: Double,
    val sensor: MotionSensorSample,
    val status: SimulationStatus,
)

/** Per distance-segment summary used by the setup preview (step cadence and pace per segment). */
data class DistanceSegmentStats(
    val index: Int,
    val startM: Double,
    val endM: Double,
    val cadenceSpm: Double,
    val speedMps: Double,
    val paceSecondsPerKm: Double,
)

interface SimulationEngine {
    fun snapshot(config: SimulationConfig, elapsedMs: Long): SimulationSnapshot
}

/**
 * Deterministic, distance-segmented simulation engine.
 *
 * Both cadence and speed vary *per route segment* (roughly one segment per kilometer,
 * at least two segments). Every segment gets a seeded target value around the
 * configured average; adjacent segments are blended with a short ramp, so the first
 * kilometer can run slightly faster and the second slightly slower while:
 *  - average cadence over the whole run stays exactly the configured value,
 *  - the final step count lands exactly on the target,
 *  - cadence never leaves 120..151, and speed keeps small liveliness even at 0%,
 *  - identical parameters + seed reproduce identical curves.
 */
class DeterministicSimulationEngine : SimulationEngine {
    private val cache = object : LinkedHashMap<SimulationConfig, SimulationPlan>(8, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<SimulationConfig, SimulationPlan>,
        ): Boolean = size > 6
    }

    private fun plan(config: SimulationConfig): SimulationPlan =
        cache.getOrPut(config) { SimulationPlan(config) }

    fun cadenceAt(config: SimulationConfig, elapsedMs: Long): Double {
        val active = plan(config)
        val elapsed = elapsedMs.coerceIn(0L, config.durationMs)
        return active.cadenceSpmAtMs(elapsed)
    }

    /** Realized per-segment cadence and pace summaries (for the setup preview). */
    fun segmentStats(config: SimulationConfig): List<DistanceSegmentStats> =
        plan(config).segmentStats()

    override fun snapshot(config: SimulationConfig, elapsedMs: Long): SimulationSnapshot {
        require(config.route.controlPoints.size >= 2) { "Route requires at least two points" }
        val active = plan(config)
        val elapsed = elapsedMs.coerceIn(0L, config.durationMs)
        if (elapsed == config.durationMs) {
            val last = config.route.controlPoints.last()
            return SimulationSnapshot(
                elapsedMs = elapsed,
                remainingMs = 0L,
                stepCount = config.totalSteps,
                totalSteps = config.totalSteps,
                cadenceSpm = 0.0,
                position = last,
                headingDeg = bearingBetween(
                    config.route.controlPoints[config.route.controlPoints.lastIndex - 1],
                    last,
                ),
                distanceM = config.route.lengthM,
                speedMps = 0.0,
                progress = 1.0,
                sensor = sensorSample(config, active, elapsed, 0.0, 0.0),
                status = SimulationStatus.Completed,
            )
        }
        val distance = active.distanceAtMs(elapsed)
        val speed = active.speedAtDistance(distance)
        val (position, heading) = pointAtDistance(config.route.controlPoints, distance)
        val steps = floor(active.stepsAtMs(elapsed) + 1e-9).toInt()
            .coerceIn(0, config.totalSteps)
        return SimulationSnapshot(
            elapsedMs = elapsed,
            remainingMs = config.durationMs - elapsed,
            stepCount = steps,
            totalSteps = config.totalSteps,
            cadenceSpm = active.cadenceSpmAtMs(elapsed),
            position = position.copy(cumulativeDistanceM = distance),
            headingDeg = heading,
            distanceM = distance,
            speedMps = speed,
            progress = (distance / config.route.lengthM).coerceIn(0.0, 1.0),
            sensor = sensorSample(config, active, elapsed, speed, active.speedDerivativeAt(distance)),
            status = SimulationStatus.Running,
        )
    }

    private fun pointAtDistance(points: List<PointM>, targetM: Double): Pair<PointM, Double> {
        if (targetM <= 0.0) return points.first() to bearingBetween(points[0], points[1])
        if (targetM >= points.last().cumulativeDistanceM) {
            return points.last() to bearingBetween(points[points.lastIndex - 1], points.last())
        }
        val endIndex = points.indexOfFirst { it.cumulativeDistanceM >= targetM }.coerceAtLeast(1)
        val start = points[endIndex - 1]
        val end = points[endIndex]
        val segmentLength = end.cumulativeDistanceM - start.cumulativeDistanceM
        val fraction = if (segmentLength <= 0.0) 0.0 else (targetM - start.cumulativeDistanceM) / segmentLength
        return GeoMath.interpolate(start, end, fraction).copy(cumulativeDistanceM = targetM) to
            bearingBetween(start, end)
    }

    private fun bearingBetween(start: PointM, end: PointM): Double =
        GeoMath.bearingDeg(start, end)

    private fun seedPhase(config: SimulationConfig): Double =
        ((config.randomSeed % 997L).toDouble() / 997.0) * 2.0 * PI

    private fun sensorSample(
        config: SimulationConfig,
        plan: SimulationPlan,
        elapsedMs: Long,
        speedMps: Double,
        speedDerivative: Double,
    ): MotionSensorSample {
        val t = elapsedMs / 1_000.0
        val remaining = (config.durationMs - elapsedMs) / 1_000.0
        val envelope = minOf(1.0, t / 0.8, max(0.0, remaining / 0.8))
        val seedPhase = seedPhase(config)
        val phase = 2.0 * PI * plan.stepsAtMs(elapsedMs) + seedPhase
        val drift = sin(2.0 * PI * 0.17 * t + seedPhase) * 0.06
        val sensorNoise = 0.035 * sin(2.0 * PI * 7.3 * t + seedPhase * 2.3) +
            0.018 * sin(2.0 * PI * 11.1 * t + seedPhase * 0.6)
        val speedScale = if (config.speedMps <= 0.0) 1.0 else
            (speedMps / config.speedMps).coerceIn(0.85, 1.15)
        val acceleration = speedDerivative * speedMps
        val vertical = envelope * speedScale *
            (1.55 * sin(phase) + 0.42 * sin(phase * 2.0 + 0.4) + sensorNoise)
        val forward = envelope * (speedScale * 0.62 * cos(phase + 0.2) + drift + acceleration)
        val lateral = envelope * (speedScale * 0.34 * sin(phase * 0.5 + 0.7) + drift * 0.5 + sensorNoise)
        val roll = envelope * 3.2 * sin(phase * 0.5 + 0.7)
        val pitch = envelope * 2.1 * cos(phase + 0.2)
        return MotionSensorSample(
            elapsedMs = elapsedMs,
            accelerometerX = lateral,
            accelerometerY = forward,
            accelerometerZ = 9.80665 + vertical,
            gyroscopeX = envelope * (speedScale * 0.22 * cos(phase * 0.5 + 0.7) + sensorNoise * 0.08),
            gyroscopeY = envelope * (speedScale * -0.31 * sin(phase + 0.2) + sensorNoise * 0.12),
            gyroscopeZ = envelope * (speedScale * 0.08 * sin(phase * 0.5) + drift * 0.1),
            gravityX = 9.80665 * sin(Math.toRadians(roll)),
            gravityY = -9.80665 * sin(Math.toRadians(pitch)),
            gravityZ = 9.80665 * cos(Math.toRadians(roll)) * cos(Math.toRadians(pitch)),
            rollDeg = roll,
            pitchDeg = pitch,
            yawDeg = bearingAt(plan, elapsedMs),
        )
    }

    private fun bearingAt(plan: SimulationPlan, elapsedMs: Long): Double {
        val pts = plan.route.controlPoints
        val distance = plan.distanceAtMs(elapsedMs)
        if (distance >= pts.last().cumulativeDistanceM) {
            return bearingBetween(pts[pts.lastIndex - 1], pts.last())
        }
        return pointAtDistance(pts, distance).second
    }
}

/**
 * Precomputed, distance-grid based plan for one [SimulationConfig].
 * Grid resolution is at most 0.75 m; cumulative time/steps are scaled so the
 * run ends at exactly `durationMs` and `totalSteps`, which keeps the engine pure.
 */
private class SimulationPlan(val config: SimulationConfig) {
    val route: RouteDefinition = config.route
    val lengthM: Double = route.lengthM.coerceAtLeast(route.controlPoints.last().cumulativeDistanceM)
    val durationMs: Long = config.durationMs
    val totalSteps: Int = config.totalSteps

    private val gridCount: Int
    private val stepM: Double
    private val cumulativeTimeMs: DoubleArray
    private val cumulativeSteps: DoubleArray
    private val speedAtDistance: DoubleArray
    private val speedDerivativeAtDistance: DoubleArray
    private val cadenceAtDistance: DoubleArray

    init {
        gridCount = (lengthM / 0.75).toInt().coerceIn(600, 80_000)
        stepM = if (gridCount > 0) lengthM / gridCount else 0.0
        cumulativeTimeMs = DoubleArray(gridCount + 1)
        cumulativeSteps = DoubleArray(gridCount + 1)
        speedAtDistance = DoubleArray(gridCount + 1)
        speedDerivativeAtDistance = DoubleArray(gridCount + 1)
        cadenceAtDistance = DoubleArray(gridCount + 1)

        val averageSpeedMps = config.speedMps
        val cadenceBase = config.cadenceSpm
        val cadenceAmp = config.cadenceVariationSpm
        val speedAmp = config.speedVariationPercent / 100.0

        val segmentCount = ((lengthM / 1_000.0).roundToInt()).coerceIn(2, 12)
        val segmentLength = lengthM / segmentCount
        val rampHalf = minOf(60.0, segmentLength * 0.5)

        // Deterministic seeded shape for segment targets (two spatial harmonics).
        val phase1 = 2.0 * PI * unit01(mix(config.randomSeed, 1))
        val phase2 = 2.0 * PI * unit01(mix(config.randomSeed, 2))
        val weight1 = 0.6 + 0.4 * unit01(mix(config.randomSeed, 3))
        val weight2 = 0.4 + 0.4 * unit01(mix(config.randomSeed, 4))
        val weightSum = weight1 + weight2

        fun segmentShape(index: Int): Double {
            val u = (index + 0.5) / segmentCount
            return (weight1 * cos(2.0 * PI * u + phase1) +
                weight2 * cos(4.0 * PI * u + phase2)) / weightSum
        }

        fun easedValue(distance: Double, targets: DoubleArray): Double {
            if (targets.size == 1) return targets[0]
            val index = (distance / segmentLength).toInt().coerceIn(0, targets.size - 1)
            var value = targets[index]
            if (index > 0) {
                val boundary = index * segmentLength
                val dist = distance - boundary
                if (dist < rampHalf) {
                    val blend = (dist + rampHalf) / (2.0 * rampHalf)
                    value = targets[index - 1] + (targets[index] - targets[index - 1]) * blend
                }
            }
            if (index < targets.size - 1) {
                val boundary = (index + 1) * segmentLength
                val dist = boundary - distance
                if (dist < rampHalf) {
                    val blend = (dist + rampHalf) / (2.0 * rampHalf)
                    value = targets[index] + (targets[index + 1] - targets[index]) * (1.0 - blend)
                }
            }
            return value
        }

        // Raw speed multipliers per segment (+1 offset), then a small distance ripple for liveliness.
        val speedTargets = DoubleArray(segmentCount) { index ->
            1.0 + speedAmp * segmentShape(index + 7)
        }
        val ripplePhaseA = 2.0 * PI * unit01(mix(config.randomSeed, 5))
        val ripplePhaseB = 2.0 * PI * unit01(mix(config.randomSeed, 6))

        // Raw cumulative time on the grid; used to compute segment durations for cadence weighting.
        val rawTimeMs = DoubleArray(gridCount + 1)
        var previousSpeed = 0.0
        for (j in 0..gridCount) {
            val distance = j * stepM
            val multiplier = easedValue(distance, speedTargets) *
                (1.0 + 0.02 * sin(2.0 * PI * distance / 71.3 + ripplePhaseA) +
                    0.008 * sin(2.0 * PI * distance / 23.9 + ripplePhaseB))
            val rawSpeed = (averageSpeedMps * multiplier).coerceAtLeast(0.1)
            speedAtDistance[j] = rawSpeed
            if (j > 0) {
                val average = (previousSpeed + rawSpeed) * 0.5
                rawTimeMs[j] = rawTimeMs[j - 1] + stepM / average * 1000.0
            }
            previousSpeed = rawSpeed
        }
        // Scale speed so total duration is exactly durationMs.
        val timeScale = durationMs / rawTimeMs[gridCount]
        for (j in 0..gridCount) {
            speedAtDistance[j] *= timeScale
            cumulativeTimeMs[j] = rawTimeMs[j] * timeScale
        }
        // Finite-difference dv/ds for the sensor forward-acceleration component.
        for (j in 0..gridCount) {
            val previous = speedAtDistance[(j - 1).coerceAtLeast(0)]
            val next = speedAtDistance[(j + 1).coerceAtMost(gridCount)]
            speedDerivativeAtDistance[j] = if (stepM > 0.0) (next - previous) / (2.0 * stepM) else 0.0
        }

        // Cadence offsets per segment: seeded shape, then de-meaned by *segment duration*
        // weights so the whole-run cadence average is exactly the configured value.
        val segmentTimeMs = DoubleArray(segmentCount)
        for (s in 0 until segmentCount) {
            val a = (s * segmentLength / stepM).roundToInt().coerceIn(0, gridCount)
            val b = ((s + 1) * segmentLength / stepM).roundToInt().coerceIn(0, gridCount)
            segmentTimeMs[s] = rawTimeMs[b] - rawTimeMs[a]
        }
        val cadenceTargets = DoubleArray(segmentCount) { index ->
            cadenceBase + cadenceAmp * segmentShape(index)
        }
        if (cadenceAmp > 0.0) {
            var weightedSum = 0.0
            var timeSum = 0.0
            for (s in 0 until segmentCount) {
                weightedSum += (cadenceTargets[s] - cadenceBase) * segmentTimeMs[s]
                timeSum += segmentTimeMs[s]
            }
            val meanOffset = if (timeSum > 0.0) weightedSum / timeSum else 0.0
            for (s in 0 until segmentCount) cadenceTargets[s] -= meanOffset
            // Scale any residual back within the requested amplitude.
            var maxOffset = 0.0
            for (s in 0 until segmentCount) {
                maxOffset = max(maxOffset, kotlin.math.abs(cadenceTargets[s] - cadenceBase))
            }
            if (maxOffset > cadenceAmp && maxOffset > 0.0) {
                val scale = cadenceAmp / maxOffset
                for (s in 0 until segmentCount) {
                    cadenceTargets[s] = cadenceBase +
                        (cadenceTargets[s] - cadenceBase) * scale
                }
            }
        }

        // Cumulative steps: steps = integral of cadence(spm)/60 over elapsed time.
        var rawSteps = 0.0
        cumulativeSteps[0] = 0.0
        cadenceAtDistance[0] = easedValue(0.0, cadenceTargets).coerceIn(120.0, 151.0)
        for (j in 1..gridCount) {
            val distance = j * stepM
            val cadence = easedValue(distance, cadenceTargets)
                .coerceIn(120.0, 151.0)
            cadenceAtDistance[j] = cadence
            val dtSeconds = (cumulativeTimeMs[j] - cumulativeTimeMs[j - 1]) / 1000.0
            rawSteps += cadence / 60.0 * dtSeconds
            cumulativeSteps[j] = rawSteps
        }
        val stepsScale = if (cumulativeSteps[gridCount] > 0.0) {
            totalSteps / cumulativeSteps[gridCount]
        } else 1.0
        for (j in 0..gridCount) cumulativeSteps[j] *= stepsScale
    }

    fun distanceAtMs(elapsedMs: Long): Double {
        val target = elapsedMs.coerceIn(0L, durationMs).toDouble()
        var low = 0
        var high = gridCount
        while (low < high) {
            val mid = (low + high) ushr 1
            if (cumulativeTimeMs[mid] < target) low = mid + 1 else high = mid
        }
        if (low == 0) return 0.0
        if (low > gridCount) return lengthM
        val base = cumulativeTimeMs[low - 1]
        val span = cumulativeTimeMs[low] - base
        val fraction = if (span > 0.0) (target - base) / span else 0.0
        return ((low - 1) + fraction.coerceIn(0.0, 1.0)) * stepM
    }

    fun stepsAtMs(elapsedMs: Long): Double {
        val target = elapsedMs.coerceIn(0L, durationMs).toDouble()
        val node = timeNode(target)
        if (node == 0) return 0.0
        val base = cumulativeTimeMs[node - 1]
        val span = cumulativeTimeMs[node] - base
        val fraction = if (span > 0.0) (target - base) / span else 0.0
        return cumulativeSteps[node - 1] +
            (cumulativeSteps[node] - cumulativeSteps[node - 1]) * fraction.coerceIn(0.0, 1.0)
    }

    fun stepsAtDistance(distance: Double): Double = interpolateGrid(cumulativeSteps, distance)

    fun cadenceSpmAtMs(elapsedMs: Long): Double =
        interpolateGrid(cadenceAtDistance, distanceAtMs(elapsedMs))

    fun speedAtDistance(distance: Double): Double = interpolateGrid(speedAtDistance, distance)

    fun speedDerivativeAt(distance: Double): Double =
        interpolateGrid(speedDerivativeAtDistance, distance)

    private fun timeNode(targetMs: Double): Int {
        var low = 0
        var high = gridCount
        while (low < high) {
            val mid = (low + high) ushr 1
            if (cumulativeTimeMs[mid] < targetMs) low = mid + 1 else high = mid
        }
        return low
    }

    private fun interpolateGrid(values: DoubleArray, distance: Double): Double {
        if (gridCount == 0) return values[0]
        val clamped = distance.coerceIn(0.0, lengthM)
        val position = (clamped / stepM).coerceIn(0.0, gridCount.toDouble())
        val low = floor(position).toInt().coerceAtMost(gridCount - 1)
        val fraction = position - low
        return values[low] + (values[low + 1] - values[low]) * fraction
    }

    fun segmentStats(): List<DistanceSegmentStats> {
        val segmentCount = ((lengthM / 1_000.0).roundToInt()).coerceIn(2, 12)
        val segmentLength = lengthM / segmentCount
        return List(segmentCount) { index ->
            val startM = index * segmentLength
            val endM = minOf((index + 1) * segmentLength, lengthM)
            val a = (startM / stepM).roundToInt().coerceIn(0, gridCount)
            val b = (endM / stepM).roundToInt().coerceIn(0, gridCount)
            val timeSeconds = (cumulativeTimeMs[b] - cumulativeTimeMs[a]) / 1000.0
            val steps = cumulativeSteps[b] - cumulativeSteps[a]
            val averageCadence = if (timeSeconds > 0.0) steps / timeSeconds * 60.0 else config.cadenceSpm
            val averageSpeed = if (timeSeconds > 0.0) (endM - startM) / timeSeconds else 0.0
            DistanceSegmentStats(
                index = index,
                startM = startM,
                endM = endM,
                cadenceSpm = averageCadence,
                speedMps = averageSpeed,
                paceSecondsPerKm = if (averageSpeed > 0.0) 1_000.0 / averageSpeed else 0.0,
            )
        }
    }

    private fun mix(seed: Long, salt: Int): Long {
        var x = seed + salt * -7046029254386353131L
        x = (x xor (x ushr 30)) * -4658895280553007687L
        x = (x xor (x ushr 27)) * -7723592293110705685L
        return x xor (x ushr 31)
    }

    private fun unit01(value: Long): Double =
        (value ushr 11).toDouble() / (1L shl 53).toDouble()
}
