package com.robot.simulator.bridge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.RemoteCallbackList
import androidx.core.app.NotificationCompat
import com.robot.simulator.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class RobotSimulationBridgeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val listeners = RemoteCallbackList<IRobotSimulationListener>()
    private lateinit var locationPublisher: GlobalLocationPublisher

    private val binder = object : IRobotSimulationBridge.Stub() {
        override fun getProtocolVersion(): Int {
            enforceCaller()
            return BridgeContract.PROTOCOL_VERSION
        }

        override fun getCurrentSnapshot(): BridgeSnapshot {
            enforceCaller()
            return SimulationBridgeStore.snapshot.value
        }

        override fun registerListener(listener: IRobotSimulationListener) {
            enforceCaller()
            listeners.register(listener)
            runCatching { listener.onSnapshot(SimulationBridgeStore.snapshot.value) }
        }

        override fun unregisterListener(listener: IRobotSimulationListener) {
            enforceCaller()
            listeners.unregister(listener)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("机器人仿真桥接运行中")
                .setContentText("正在向 MuMu 调试环境发布位置、步数和运动数据")
                .setOngoing(true)
                .build(),
        )
        locationPublisher = GlobalLocationPublisher(this)
        scope.launch {
            SimulationBridgeStore.snapshot.collectLatest { snapshot ->
                locationPublisher.publish(snapshot)
                val count = listeners.beginBroadcast()
                try {
                    repeat(count) { index ->
                        runCatching { listeners.getBroadcastItem(index).onSnapshot(snapshot) }
                    }
                } finally {
                    listeners.finishBroadcast()
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        listeners.kill()
        scope.cancel()
        locationPublisher.close()
        super.onDestroy()
    }

    private fun enforceCaller() {
        val uid = Binder.getCallingUid()
        if (!BridgeAccessPolicy.isCallerAllowed(this, uid)) {
            throw SecurityException("UID $uid is not allowed to read simulation data")
        }
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "机器人仿真桥接",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    companion object {
        const val ACTION_START = "com.robot.simulator.bridge.START"
        private const val CHANNEL_ID = "robot_simulation_bridge"
        private const val NOTIFICATION_ID = 7101
    }
}
