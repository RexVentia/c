package com.robot.simulator.bridge;

public final class BridgeContract {
    public static final int PROTOCOL_VERSION = 1;
    public static final String SIMULATOR_PACKAGE = "com.robot.simulator";
    public static final String SERVICE_CLASS =
            "com.robot.simulator.bridge.RobotSimulationBridgeService";
    public static final String DEFAULT_TEST_TARGET = "com.robot.simulator.testtarget";

    private BridgeContract() {}
}
