package com.robot.simulator

import android.app.Application
import com.robot.simulator.data.local.RobotSimulatorDatabase

class RobotSimulatorApplication : Application() {
    val database: RobotSimulatorDatabase by lazy {
        RobotSimulatorDatabase.create(this)
    }
}
