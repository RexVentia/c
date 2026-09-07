package com.robot.simulator.bridge;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class CoordinateTransformTest {
    @Test
    public void gcjRoundTripStaysWithinOneCentimeter() {
        double gcjLatitude = 45.546476;
        double gcjLongitude = 84.927066;

        double[] wgs84 = CoordinateTransform.gcj02ToWgs84(gcjLatitude, gcjLongitude);
        double[] roundTrip = CoordinateTransform.wgs84ToGcj02(wgs84[0], wgs84[1]);

        assertEquals(gcjLatitude, roundTrip[0], 1e-7);
        assertEquals(gcjLongitude, roundTrip[1], 1e-7);
    }

    @Test
    public void coordinatesOutsideChinaRemainUnchanged() {
        double[] result = CoordinateTransform.gcj02ToWgs84(51.5074, -0.1278);

        assertEquals(51.5074, result[0], 0.0);
        assertEquals(-0.1278, result[1], 0.0);
    }
}
