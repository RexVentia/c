package com.robot.simulator.bridge;

import com.robot.simulator.bridge.BridgeSnapshot;
import com.robot.simulator.bridge.IRobotSimulationListener;

interface IRobotSimulationBridge {
    int getProtocolVersion();
    BridgeSnapshot getCurrentSnapshot();
    void registerListener(IRobotSimulationListener listener);
    void unregisterListener(IRobotSimulationListener listener);
}
