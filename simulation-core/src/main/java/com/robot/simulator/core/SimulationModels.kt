package com.robot.simulator.core

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
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
    val cadencePeriodSeconds: Double = 10.0,
) {
    init {
        require(cadenceSpm in 120.0..151.0)
        require(cadenceVariationSpm.isFinite() && cadenceVariationSpm >= 0.0 &&
            cadenceVariationSpm <= minOf(cadenceSpm - 120.0, 151.0 - cadenceSpm))
        require(cadencePeriodSeconds.isFinite() && cadencePeriodSeconds in 2.0..120.0)
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

interface SimulationEngine {
    fun snapshot(config: SimulationConfig, elapsedMs: Long): SimulationSnapshot
}

class DeterministicSimulationEngine : SimulationEngine {
    // Whole cycles preserve the requested average and exact final step total.
    private fun cadenceOmega(config: SimulationConfig): Double {
        val duration = config.durationMs / 1000.0
        val cycles = kotlin.math.round(duration / config.cadencePeriodSeconds).coerceAtLeast(1.0)
        return 2.0 * PI * cycles / duration
    }

    fun cadenceAt(config: SimulationConfig, elapsedMs: Long): Double =
        config.cadenceSpm + config.cadenceVariationSpm *
            cos(cadenceOmega(config) * elapsedMs.coerceIn(0, config.durationMs) / 1000.0 + seedPhase(config))

    private fun continuousSteps(config: SimulationConfig, elapsedMs: Long): Double {
        val omega = cadenceOmega(config)
        val phase = seedPhase(config)
        return (config.totalSteps * (elapsedMs.toDouble() / config.durationMs) +
            config.cadenceVariationSpm / (60.0 * omega) *
            (sin(omega * elapsedMs / 1000.0 + phase) - sin(phase)))
            .coerceIn(0.0, config.totalSteps.toDouble())
    }

    override fun snapshot(config: SimulationConfig, elapsedMs: Long): SimulationSnapshot {
        require(config.route.controlPoints.size >= 2) { "Route requires at least two points" }
        val elapsed = elapsedMs.coerceIn(0L, config.durationMs)
        val elapsedSeconds = elapsed / 1_000.0
        val durationSeconds = config.durationMs / 1_000.0
        val totalProfileArea = speedProfileArea(config, durationSeconds)
        val progress = if (elapsed == config.durationMs) 1.0 else
            (speedProfileArea(config, elapsedSeconds) / totalProfileArea).coerceIn(0.0, 1.0)
        val distance = config.route.lengthM * progress
        val instantaneousSpeed = if (elapsed == config.durationMs) 0.0 else
            config.route.lengthM * speedProfile(config, elapsedSeconds) / totalProfileArea
        val (position, heading) = pointAtDistance(config.route.controlPoints, distance)
        val steps = if (elapsed == config.durationMs) config.totalSteps else
            floor(continuousSteps(config, elapsed)).toInt().coerceAtMost(config.totalSteps)
        return SimulationSnapshot(
            elapsedMs = elapsed,
            remainingMs = config.durationMs - elapsed,
            stepCount = steps,
            totalSteps = config.totalSteps,
            cadenceSpm = if (elapsed == config.durationMs) 0.0 else cadenceAt(config, elapsed),
            position = position.copy(cumulativeDistanceM = distance),
            headingDeg = heading,
            distanceM = distance,
            speedMps = instantaneousSpeed,
            progress = progress,
            sensor = sensorSample(config, elapsed, heading, instantaneousSpeed, totalProfileArea),
            status = if (elapsed == config.durationMs) SimulationStatus.Completed else SimulationStatus.Running,
        )
    }

    private fun pointAtDistance(points: List<PointM>, targetM: Double): Pair<PointM, Double> {
        if (targetM <= 0.0) return points.first() to GeoMath.bearingDeg(points[0], points[1])
        if (targetM >= points.last().cumulativeDistanceM) {
            return points.last() to GeoMath.bearingDeg(points[points.lastIndex - 1], points.last())
        }
        val endIndex = points.indexOfFirst { it.cumulativeDistanceM >= targetM }.coerceAtLeast(1)
        val start = points[endIndex - 1]
        val end = points[endIndex]
        val segmentLength = end.cumulativeDistanceM - start.cumulativeDistanceM
        val fraction = if (segmentLength <= 0.0) 0.0 else (targetM - start.cumulativeDistanceM) / segmentLength
        return GeoMath.interpolate(start, end, fraction).copy(cumulativeDistanceM = targetM) to
            GeoMath.bearingDeg(start, end)
    }

    private fun speedProfile(config: SimulationConfig, timeSeconds: Double): Double {
        val phase = seedPhase(config)
        return 1.0 +
            0.06 * sin(2.0 * PI * timeSeconds / 11.0 + phase) +
            0.025 * sin(2.0 * PI * timeSeconds / 3.7 + phase * 1.7)
    }

    private fun speedProfileArea(config: SimulationConfig, timeSeconds: Double): Double {
        val phase = seedPhase(config)
        val firstOmega = 2.0 * PI / 11.0
        val secondOmega = 2.0 * PI / 3.7
        return timeSeconds +
            0.06 * (cos(phase) - cos(firstOmega * timeSeconds + phase)) / firstOmega +
            0.025 * (cos(phase * 1.7) - cos(secondOmega * timeSeconds + phase * 1.7)) / secondOmega
    }

    private fun speedProfileDerivative(config: SimulationConfig, timeSeconds: Double): Double {
        val phase = seedPhase(config)
        return 0.06 * (2.0 * PI / 11.0) * cos(2.0 * PI * timeSeconds / 11.0 + phase) +
            0.025 * (2.0 * PI / 3.7) * cos(2.0 * PI * timeSeconds / 3.7 + phase * 1.7)
    }

    private fun seedPhase(config: SimulationConfig): Double =
        ((config.randomSeed % 997L).toDouble() / 997.0) * 2.0 * PI

    private fun sensorSample(
        config: SimulationConfig,
        elapsedMs: Long,
        headingDeg: Double,
        instantaneousSpeedMps: Double,
        totalProfileArea: Double,
    ): MotionSensorSample {
        val t = elapsedMs / 1_000.0
        val remaining = (config.durationMs - elapsedMs) / 1_000.0
        val envelope = minOf(1.0, t / 0.8, max(0.0, remaining / 0.8))
        val seedPhase = seedPhase(config)
        val phase = 2.0 * PI * continuousSteps(config, elapsedMs) + seedPhase
        val drift = sin(2.0 * PI * 0.17 * t + seedPhase) * 0.06
        val sensorNoise = 0.035 * sin(2.0 * PI * 7.3 * t + seedPhase * 2.3) +
            0.018 * sin(2.0 * PI * 11.1 * t + seedPhase * 0.6)
        val speedScale = if (config.speedMps <= 0.0) 1.0 else
            (instantaneousSpeedMps / config.speedMps).coerceIn(0.85, 1.15)
        val speedAcceleration = config.route.lengthM / totalProfileArea * speedProfileDerivative(config, t)
        val vertical = envelope * speedScale *
            (1.55 * sin(phase) + 0.42 * sin(phase * 2.0 + 0.4) + sensorNoise)
        val forward = envelope * (speedScale * 0.62 * cos(phase + 0.2) + drift + speedAcceleration)
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
            yawDeg = headingDeg,
        )
    }
}
