package com.robot.simulator.bridge;

import com.robot.simulator.bridge.BridgeSnapshot;

oneway interface IRobotSimulationListener {
    void onSnapshot(in BridgeSnapshot snapshot);
}
