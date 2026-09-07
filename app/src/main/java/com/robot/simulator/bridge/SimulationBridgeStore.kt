package com.robot.simulator.bridge

import com.robot.simulator.core.SimulationSnapshot
import com.robot.simulator.core.SimulationStatus
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object SimulationBridgeStore {
    private val sequence = AtomicLong(0L)
    private val mutableSnapshot = MutableStateFlow(BridgeSnapshot.empty())
    val snapshot: StateFlow<BridgeSnapshot> = mutableSnapshot.asStateFlow()

    @Volatile private var sessionId: String = ""
    @Volatile private var lastPublishedElapsedMs: Long = Long.MIN_VALUE

    fun begin(newSessionId: String) {
        sessionId = newSessionId
        sequence.set(0L)
        lastPublishedElapsedMs = Long.MIN_VALUE
    }

    fun publish(source: SimulationSnapshot) {
        val terminal = source.status in setOf(SimulationStatus.Completed, SimulationStatus.Stopped)
        if (!terminal && lastPublishedElapsedMs != Long.MIN_VALUE &&
            source.elapsedMs - lastPublishedElapsedMs < 100L
        ) return
        lastPublishedElapsedMs = source.elapsedMs
        mutableSnapshot.value = BridgeSnapshot(
            sessionId,
            sequence.incrementAndGet(),
            source.status.name.uppercase(),
            source.position.latitude,
            source.position.longitude,
            3.0f,
            source.speedMps.toFloat(),
            source.headingDeg.toFloat(),
            source.elapsedMs,
            source.stepCount,
            source.totalSteps,
            source.cadenceSpm.toFloat(),
            source.status == SimulationStatus.Completed,
            source.sensor.accelerometerX.toFloat(),
            source.sensor.accelerometerY.toFloat(),
            source.sensor.accelerometerZ.toFloat(),
            source.sensor.gyroscopeX.toFloat(),
            source.sensor.gyroscopeY.toFloat(),
            source.sensor.gyroscopeZ.toFloat(),
        )
    }
}
