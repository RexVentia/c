package com.robot.simulator.bridge;

/** Coordinate conversion at the Android Location boundary. */
public final class CoordinateTransform {
    private static final double SEMI_MAJOR_AXIS = 6378245.0;
    private static final double ECCENTRICITY_SQUARED = 0.00669342162296594323;

    private CoordinateTransform() {}

    /** Converts an AMap/GCJ-02 point to the WGS-84 point expected by Android Location. */
    public static double[] gcj02ToWgs84(double latitude, double longitude) {
        if (outsideChina(latitude, longitude)) return new double[] {latitude, longitude};

        double wgsLatitude = latitude;
        double wgsLongitude = longitude;
        for (int attempt = 0; attempt < 10; attempt++) {
            double[] converted = wgs84ToGcj02(wgsLatitude, wgsLongitude);
            double latitudeError = converted[0] - latitude;
            double longitudeError = converted[1] - longitude;
            wgsLatitude -= latitudeError;
            wgsLongitude -= longitudeError;
            if (Math.max(Math.abs(latitudeError), Math.abs(longitudeError)) < 1e-9) break;
        }
        return new double[] {wgsLatitude, wgsLongitude};
    }

    public static double[] wgs84ToGcj02(double latitude, double longitude) {
        if (outsideChina(latitude, longitude)) return new double[] {latitude, longitude};

        double latitudeDelta = transformLatitude(longitude - 105.0, latitude - 35.0);
        double longitudeDelta = transformLongitude(longitude - 105.0, latitude - 35.0);
        double latitudeRadians = Math.toRadians(latitude);
        double sinLatitude = Math.sin(latitudeRadians);
        double magic = 1.0 - ECCENTRICITY_SQUARED * sinLatitude * sinLatitude;
        double sqrtMagic = Math.sqrt(magic);
        latitudeDelta = latitudeDelta * 180.0 /
                ((SEMI_MAJOR_AXIS * (1.0 - ECCENTRICITY_SQUARED)) /
                        (magic * sqrtMagic) * Math.PI);
        longitudeDelta = longitudeDelta * 180.0 /
                (SEMI_MAJOR_AXIS / sqrtMagic * Math.cos(latitudeRadians) * Math.PI);
        return new double[] {latitude + latitudeDelta, longitude + longitudeDelta};
    }

    private static boolean outsideChina(double latitude, double longitude) {
        return longitude < 72.004 || longitude > 137.8347 ||
                latitude < 0.8293 || latitude > 55.8271;
    }

    private static double transformLatitude(double x, double y) {
        double value = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y +
                0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x));
        value += (20.0 * Math.sin(6.0 * x * Math.PI) +
                20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        value += (20.0 * Math.sin(y * Math.PI) +
                40.0 * Math.sin(y / 3.0 * Math.PI)) * 2.0 / 3.0;
        value += (160.0 * Math.sin(y / 12.0 * Math.PI) +
                320.0 * Math.sin(y * Math.PI / 30.0)) * 2.0 / 3.0;
        return value;
    }

    private static double transformLongitude(double x, double y) {
        double value = 300.0 + x + 2.0 * y + 0.1 * x * x +
                0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x));
        value += (20.0 * Math.sin(6.0 * x * Math.PI) +
                20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        value += (20.0 * Math.sin(x * Math.PI) +
                40.0 * Math.sin(x / 3.0 * Math.PI)) * 2.0 / 3.0;
        value += (150.0 * Math.sin(x / 12.0 * Math.PI) +
                300.0 * Math.sin(x / 30.0 * Math.PI)) * 2.0 / 3.0;
        return value;
    }
}
