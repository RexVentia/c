package com.robot.simulator.ui

import android.app.Application
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.robot.simulator.RobotSimulatorApplication
import com.robot.simulator.bridge.RobotSimulationBridgeService
import com.robot.simulator.bridge.SimulationBridgeStore
import com.robot.simulator.core.DeterministicSimulationEngine
import com.robot.simulator.core.MotionSensorSample
import com.robot.simulator.core.RouteDefinition
import com.robot.simulator.core.RoutePointCodec
import com.robot.simulator.core.SimulationConfig
import com.robot.simulator.core.SimulationSnapshot
import com.robot.simulator.core.SimulationStatus
import com.robot.simulator.data.local.RouteEntity
import com.robot.simulator.data.local.SimulationSessionEntity
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class SimulationViewModel(application: Application) : AndroidViewModel(application) {
    private val sessionDao = (application as RobotSimulatorApplication).database.simulationSessionDao()
    private val engine = DeterministicSimulationEngine()
    private val mutableSnapshot = MutableStateFlow<SimulationSnapshot?>(null)
    private val mutableSensorHistory = MutableStateFlow<List<MotionSensorSample>>(emptyList())
    private val mutableActiveRoute = MutableStateFlow<RouteDefinition?>(null)

    val snapshot: StateFlow<SimulationSnapshot?> = mutableSnapshot.asStateFlow()
    val sensorHistory: StateFlow<List<MotionSensorSample>> = mutableSensorHistory.asStateFlow()
    val activeRoute: StateFlow<RouteDefinition?> = mutableActiveRoute.asStateFlow()
    val sessions = sessionDao.observeAll().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    private var config: SimulationConfig? = null
    private var sessionId: String? = null
    private var startedAtEpochMs = 0L
    private var accumulatedMs = 0L
    private var runningSinceRealtimeMs = 0L
    private var ticker: Job? = null

    fun start(route: RouteEntity, totalSteps: Int, cadenceSpm: Double,
              cadenceVariationSpm: Double = 0.0, cadencePeriodSeconds: Double = 10.0, randomSeed: Long = 1L) {
        val previousConfig = config
        val previousSnapshot = mutableSnapshot.value
        if (previousConfig != null && previousSnapshot?.status in
            listOf(SimulationStatus.Running, SimulationStatus.Paused)
        ) {
            if (previousSnapshot?.status == SimulationStatus.Running) {
                accumulatedMs = currentElapsed(previousConfig)
            }
            ticker?.cancel()
            val replaced = engine.snapshot(previousConfig, accumulatedMs).copy(status = SimulationStatus.Stopped)
            publish(replaced)
            persist(replaced)
        }
        ticker?.cancel()
        val points = RoutePointCodec.decode(route.controlPointsJson)
        val newConfig = SimulationConfig(
            route = RouteDefinition(route.id, route.name, points, route.lengthM),
            totalSteps = totalSteps,
            cadenceSpm = cadenceSpm,
            randomSeed = randomSeed,
            cadenceVariationSpm = cadenceVariationSpm,
            cadencePeriodSeconds = cadencePeriodSeconds,
        )
        config = newConfig
        mutableActiveRoute.value = newConfig.route
        sessionId = UUID.randomUUID().toString()
        SimulationBridgeStore.begin(requireNotNull(sessionId))
        ContextCompat.startForegroundService(
            getApplication(),
            Intent(getApplication(), RobotSimulationBridgeService::class.java)
                .setAction(RobotSimulationBridgeService.ACTION_START),
        )
        startedAtEpochMs = System.currentTimeMillis()
        accumulatedMs = 0L
        runningSinceRealtimeMs = SystemClock.elapsedRealtime()
        mutableSensorHistory.value = emptyList()
        publish(engine.snapshot(newConfig, 0L))
        launchTicker()
    }

    fun pause() {
        val activeConfig = config ?: return
        if (mutableSnapshot.value?.status != SimulationStatus.Running) return
        accumulatedMs = currentElapsed(activeConfig)
        ticker?.cancel()
        publish(engine.snapshot(activeConfig, accumulatedMs).copy(
            status = SimulationStatus.Paused, speedMps = 0.0, cadenceSpm = 0.0))
    }

    fun resume() {
        if (mutableSnapshot.value?.status != SimulationStatus.Paused) return
        runningSinceRealtimeMs = SystemClock.elapsedRealtime()
        launchTicker()
    }

    fun stop() {
        val activeConfig = config ?: return
        val current = mutableSnapshot.value ?: return
        if (current.status !in listOf(SimulationStatus.Running, SimulationStatus.Paused)) return
        if (current.status == SimulationStatus.Running) accumulatedMs = currentElapsed(activeConfig)
        ticker?.cancel()
        val stopped = engine.snapshot(activeConfig, accumulatedMs).copy(
            status = SimulationStatus.Stopped, speedMps = 0.0, cadenceSpm = 0.0)
        publish(stopped)
        persist(stopped)
    }

    private fun launchTicker() {
        val activeConfig = config ?: return
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (isActive) {
                val elapsed = currentElapsed(activeConfig)
                val next = engine.snapshot(activeConfig, elapsed)
                publish(next)
                mutableSensorHistory.value = (mutableSensorHistory.value + next.sensor).takeLast(250)
                if (next.status == SimulationStatus.Completed) {
                    accumulatedMs = activeConfig.durationMs
                    persist(next)
                    return@launch
                }
                delay(20L)
            }
        }
    }

    private fun currentElapsed(activeConfig: SimulationConfig): Long =
        (accumulatedMs + SystemClock.elapsedRealtime() - runningSinceRealtimeMs)
            .coerceAtMost(activeConfig.durationMs)

    private fun publish(snapshot: SimulationSnapshot) {
        mutableSnapshot.value = snapshot
        SimulationBridgeStore.publish(snapshot)
    }

    private fun persist(finalSnapshot: SimulationSnapshot) {
        val activeConfig = config ?: return
        val activeSessionId = sessionId ?: return
        val now = System.currentTimeMillis()
        viewModelScope.launch {
            sessionDao.upsert(
                SimulationSessionEntity(
                    id = activeSessionId,
                    routeId = activeConfig.route.id,
                    routeName = activeConfig.route.name,
                    status = finalSnapshot.status.name,
                    configJson = "{\"totalSteps\":${activeConfig.totalSteps},\"cadenceSpm\":${activeConfig.cadenceSpm},\"cadenceVariationSpm\":${activeConfig.cadenceVariationSpm},\"cadencePeriodSeconds\":${activeConfig.cadencePeriodSeconds},\"randomSeed\":${activeConfig.randomSeed}}",
                    totalSteps = activeConfig.totalSteps,
                    cadenceSpm = activeConfig.cadenceSpm,
                    plannedDurationMs = activeConfig.durationMs,
                    actualDurationMs = finalSnapshot.elapsedMs,
                    finalStepCount = finalSnapshot.stepCount,
                    finalLatitude = finalSnapshot.position.latitude,
                    finalLongitude = finalSnapshot.position.longitude,
                    sensorProfile = "PROCEDURAL_V1_50HZ",
                    startedAtEpochMs = startedAtEpochMs,
                    endedAtEpochMs = now,
                    elapsedBeforePauseMs = finalSnapshot.elapsedMs,
                    createdAtEpochMs = startedAtEpochMs,
                    updatedAtEpochMs = now,
                ),
            )
        }
    }
}
