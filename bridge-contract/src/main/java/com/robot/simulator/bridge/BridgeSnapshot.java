package com.robot.simulator.bridge;

import android.os.Parcel;
import android.os.Parcelable;

public final class BridgeSnapshot implements Parcelable {
    public final String sessionId;
    public final long sequence;
    public final String status;
    public final double latitude;
    public final double longitude;
    public final float accuracyM;
    public final float speedMps;
    public final float headingDeg;
    public final long elapsedMs;
    public final int currentSteps;
    public final int totalSteps;
    public final float cadenceSpm;
    public final boolean endpoint;
    public final float accelerometerX;
    public final float accelerometerY;
    public final float accelerometerZ;
    public final float gyroscopeX;
    public final float gyroscopeY;
    public final float gyroscopeZ;

    public BridgeSnapshot(
            String sessionId,
            long sequence,
            String status,
            double latitude,
            double longitude,
            float accuracyM,
            float speedMps,
            float headingDeg,
            long elapsedMs,
            int currentSteps,
            int totalSteps,
            float cadenceSpm,
            boolean endpoint,
            float accelerometerX,
            float accelerometerY,
            float accelerometerZ,
            float gyroscopeX,
            float gyroscopeY,
            float gyroscopeZ
    ) {
        this.sessionId = sessionId;
        this.sequence = sequence;
        this.status = status;
        this.latitude = latitude;
        this.longitude = longitude;
        this.accuracyM = accuracyM;
        this.speedMps = speedMps;
        this.headingDeg = headingDeg;
        this.elapsedMs = elapsedMs;
        this.currentSteps = currentSteps;
        this.totalSteps = totalSteps;
        this.cadenceSpm = cadenceSpm;
        this.endpoint = endpoint;
        this.accelerometerX = accelerometerX;
        this.accelerometerY = accelerometerY;
        this.accelerometerZ = accelerometerZ;
        this.gyroscopeX = gyroscopeX;
        this.gyroscopeY = gyroscopeY;
        this.gyroscopeZ = gyroscopeZ;
    }

    private BridgeSnapshot(Parcel in) {
        sessionId = in.readString();
        sequence = in.readLong();
        status = in.readString();
        latitude = in.readDouble();
        longitude = in.readDouble();
        accuracyM = in.readFloat();
        speedMps = in.readFloat();
        headingDeg = in.readFloat();
        elapsedMs = in.readLong();
        currentSteps = in.readInt();
        totalSteps = in.readInt();
        cadenceSpm = in.readFloat();
        endpoint = in.readInt() != 0;
        accelerometerX = in.readFloat();
        accelerometerY = in.readFloat();
        accelerometerZ = in.readFloat();
        gyroscopeX = in.readFloat();
        gyroscopeY = in.readFloat();
        gyroscopeZ = in.readFloat();
    }

    public static BridgeSnapshot empty() {
        return new BridgeSnapshot(
                "", 0L, "IDLE", 0.0, 0.0, 0f, 0f, 0f, 0L,
                0, 0, 0f, false, 0f, 0f, 9.80665f, 0f, 0f, 0f
        );
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(sessionId);
        dest.writeLong(sequence);
        dest.writeString(status);
        dest.writeDouble(latitude);
        dest.writeDouble(longitude);
        dest.writeFloat(accuracyM);
        dest.writeFloat(speedMps);
        dest.writeFloat(headingDeg);
        dest.writeLong(elapsedMs);
        dest.writeInt(currentSteps);
        dest.writeInt(totalSteps);
        dest.writeFloat(cadenceSpm);
        dest.writeInt(endpoint ? 1 : 0);
        dest.writeFloat(accelerometerX);
        dest.writeFloat(accelerometerY);
        dest.writeFloat(accelerometerZ);
        dest.writeFloat(gyroscopeX);
        dest.writeFloat(gyroscopeY);
        dest.writeFloat(gyroscopeZ);
    }

    @Override public int describeContents() { return 0; }

    public static final Creator<BridgeSnapshot> CREATOR = new Creator<>() {
        @Override public BridgeSnapshot createFromParcel(Parcel in) { return new BridgeSnapshot(in); }
        @Override public BridgeSnapshot[] newArray(int size) { return new BridgeSnapshot[size]; }
    };
}
