package com.robot.simulator

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.robot.simulator.ui.RobotSimulatorRoot
import com.robot.simulator.ui.theme.RobotSimulatorTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RobotSimulatorTheme {
                RobotSimulatorRoot()
            }
        }
    }
}
