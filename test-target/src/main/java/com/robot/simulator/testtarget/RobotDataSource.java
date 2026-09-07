package com.robot.simulator.testtarget;

import android.location.Location;

public final class RobotDataSource {
    public Location getLocation() {
        Location location = new Location("robot-test");
        location.setLatitude(0.0);
        location.setLongitude(0.0);
        location.setAccuracy(999f);
        return location;
    }

    public int getStepCount() { return -1; }
    public float getCadenceSpm() { return -1f; }
    public float getAccelerometerX() { return 0f; }
    public float getAccelerometerY() { return 0f; }
    public float getAccelerometerZ() { return 0f; }
    public float getGyroscopeX() { return 0f; }
    public float getGyroscopeY() { return 0f; }
    public float getGyroscopeZ() { return 0f; }
}
