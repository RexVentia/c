package com.robot.simulator.hook;

import android.app.Application;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.location.Location;
import android.os.SystemClock;
import com.robot.simulator.bridge.BridgeSnapshot;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class RobotSimulatorHook implements IXposedHookLoadPackage {
    private static final AtomicBoolean AMAP_CORE_HOOKED = new AtomicBoolean(false);
    private static final AtomicLong LAST_AMAP_LOCATION_LOG_MS = new AtomicLong(0L);
    private static final Set<Class<?>> AMAP_LISTENER_CLASSES = Collections.newSetFromMap(
            new ConcurrentHashMap<>()
    );
    private static final Set<String> BLOCKED_PACKAGES = Set.of(
            "android",
            "com.robot.simulator",
            "com.robot.simulator.hook",
            "io.github.huskydg.magisk",
            "org.lsposed.manager",
            "com.android.systemui",
            "com.mumu.launcher"
    );

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) {
        if (param.packageName == null || BLOCKED_PACKAGES.contains(param.packageName)) return;
        hookApplicationAttach(param.classLoader);
        hookStandardLocation();
        hookSensorDispatch(param.classLoader);
        hookAMapDiagnostics(param.classLoader);
        hookTestTarget(param);
        XposedBridge.log("RobotHook: armed in " + param.packageName + "/" + param.processName);
    }

    private void hookApplicationAttach(ClassLoader packageLoader) {
        XposedHelpers.findAndHookMethod(
                Application.class,
                "attach",
                Context.class,
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        Context context = (Context) param.args[0];
                        BridgeState.INSTANCE.connect(context);
                        ClassLoader loader = context.getClassLoader();
                        hookAMapDiagnostics(loader != null ? loader : packageLoader);
                    }
                }
        );
    }

    /** Records the AMap input and configuration without changing either one. */
    private void hookAMapDiagnostics(ClassLoader loader) {
        if (loader == null || AMAP_CORE_HOOKED.get()) return;
        try {
            Class<?> clientClass = XposedHelpers.findClassIfExists(
                    "com.amap.api.location.AMapLocationClient", loader
            );
            Class<?> optionClass = XposedHelpers.findClassIfExists(
                    "com.amap.api.location.AMapLocationClientOption", loader
            );
            if (clientClass == null || optionClass == null) return;
            if (!AMAP_CORE_HOOKED.compareAndSet(false, true)) return;

            XposedBridge.hookAllMethods(optionClass, "setMockEnable", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length > 0) {
                        XposedBridge.log("RobotHook-AMap: setMockEnable(" + param.args[0] + ")");
                    }
                }
            });

            XposedBridge.hookAllMethods(clientClass, "setLocationOption", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length == 0 || param.args[0] == null) return;
                    Object option = param.args[0];
                    XposedBridge.log(
                            "RobotHook-AMap: option mock=" + readValue(option, "isMockEnable")
                                    + " mode=" + readValue(option, "getLocationMode")
                                    + " once=" + readValue(option, "isOnceLocation")
                                    + " intervalMs=" + readValue(option, "getInterval")
                    );
                }
            });

            XposedBridge.hookAllMethods(clientClass, "setLocationListener", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length > 0 && param.args[0] != null) {
                        hookAMapListenerClass(param.args[0].getClass());
                    }
                }
            });
            XposedBridge.log("RobotHook-AMap: diagnostics ready");
        } catch (Throwable throwable) {
            AMAP_CORE_HOOKED.set(false);
            XposedBridge.log("RobotHook-AMap: diagnostics unavailable: " + throwable);
        }
    }

    private void hookAMapListenerClass(Class<?> listenerClass) {
        for (Class<?> type = listenerClass; type != null && type != Object.class; type = type.getSuperclass()) {
            if (!AMAP_LISTENER_CLASSES.add(type)) continue;
            try {
                XposedBridge.hookAllMethods(type, "onLocationChanged", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args.length == 0 || param.args[0] == null) return;
                        Object location = param.args[0];
                        if (!location.getClass().getName().startsWith("com.amap.api.location.AMapLocation")) {
                            return;
                        }
                        logAMapLocation(location);
                    }
                });
            } catch (Throwable throwable) {
                XposedBridge.log("RobotHook-AMap: listener hook failed: " + throwable);
            }
        }
    }

    private void logAMapLocation(Object location) {
        long now = SystemClock.elapsedRealtime();
        long previous = LAST_AMAP_LOCATION_LOG_MS.get();
        if (now - previous < 1000L || !LAST_AMAP_LOCATION_LOG_MS.compareAndSet(previous, now)) return;
        XposedBridge.log(
                "RobotHook-AMap: raw lat=" + readBaseLocationOriginal(location, "getLatitude")
                        + " lon=" + readBaseLocationOriginal(location, "getLongitude")
                        + " type=" + readValue(location, "getLocationType")
                        + " provider=" + readValue(location, "getProvider")
                        + " mock=" + readValue(location, "isFromMockProvider")
                        + " trusted=" + readValue(location, "getTrustedLevel")
                        + " error=" + readValue(location, "getErrorCode")
                        + " info=" + readValue(location, "getErrorInfo")
                        + " coord=" + readValue(location, "getCoordType")
        );
    }

    private Object readBaseLocationOriginal(Object target, String methodName) {
        try {
            Method method = target.getClass().getMethod(methodName);
            return XposedBridge.invokeOriginalMethod(method, target, new Object[0]);
        } catch (Throwable throwable) {
            return "unavailable";
        }
    }

    private Object readValue(Object target, String methodName) {
        try {
            return XposedHelpers.callMethod(target, methodName);
        } catch (Throwable throwable) {
            return "unavailable";
        }
    }

    private void hookStandardLocation() {
        hookLocationGetter("getLatitude", s -> s.latitude);
        hookLocationGetter("getLongitude", s -> s.longitude);
        hookLocationGetter("getAccuracy", s -> s.accuracyM);
        hookLocationGetter("getSpeed", s -> s.speedMps);
        hookLocationGetter("getBearing", s -> s.headingDeg);
    }

    private void hookLocationGetter(String methodName, SnapshotValue value) {
        XposedHelpers.findAndHookMethod(
                Location.class,
                methodName,
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        BridgeSnapshot snapshot = BridgeState.INSTANCE.current();
                        if (snapshot.sequence > 0L) param.setResult(value.read(snapshot));
                    }
                }
        );
    }

    private void hookSensorDispatch(ClassLoader loader) {
        try {
            XposedHelpers.findAndHookMethod(
                    "android.hardware.SystemSensorManager$SensorEventQueue",
                    loader,
                    "dispatchSensorEvent",
                    int.class,
                    float[].class,
                    int.class,
                    long.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            BridgeSnapshot snapshot = BridgeState.INSTANCE.current();
                            if (snapshot.sequence == 0L) return;
                            Object events = XposedHelpers.getObjectField(param.thisObject, "mSensorsEvents");
                            SensorEvent event = (SensorEvent) XposedHelpers.callMethod(
                                    events, "get", (Integer) param.args[0]
                            );
                            if (event == null || event.sensor == null) return;
                            float[] values = (float[]) param.args[1];
                            replaceSensorValues(event.sensor.getType(), values, snapshot);
                        }
                    }
            );
        } catch (Throwable throwable) {
            XposedBridge.log("RobotHook: sensor dispatch hook unavailable: " + throwable);
        }
    }

    private void replaceSensorValues(int type, float[] values, BridgeSnapshot snapshot) {
        if (values == null || values.length == 0) return;
        if (type == Sensor.TYPE_STEP_COUNTER) {
            values[0] = snapshot.currentSteps;
        } else if (type == Sensor.TYPE_STEP_DETECTOR) {
            values[0] = 1f;
        } else if (type == Sensor.TYPE_ACCELEROMETER && values.length >= 3) {
            values[0] = snapshot.accelerometerX;
            values[1] = snapshot.accelerometerY;
            values[2] = snapshot.accelerometerZ;
        } else if (type == Sensor.TYPE_GYROSCOPE && values.length >= 3) {
            values[0] = snapshot.gyroscopeX;
            values[1] = snapshot.gyroscopeY;
            values[2] = snapshot.gyroscopeZ;
        }
    }

    private void hookTestTarget(XC_LoadPackage.LoadPackageParam param) {
        if (!"com.robot.simulator.testtarget".equals(param.packageName)) return;
        String className = "com.robot.simulator.testtarget.RobotDataSource";
        hookTargetGetter(className, param.classLoader, "getStepCount", s -> s.currentSteps);
        hookTargetGetter(className, param.classLoader, "getCadenceSpm", s -> s.cadenceSpm);
        hookTargetGetter(className, param.classLoader, "getAccelerometerX", s -> s.accelerometerX);
        hookTargetGetter(className, param.classLoader, "getAccelerometerY", s -> s.accelerometerY);
        hookTargetGetter(className, param.classLoader, "getAccelerometerZ", s -> s.accelerometerZ);
        hookTargetGetter(className, param.classLoader, "getGyroscopeX", s -> s.gyroscopeX);
        hookTargetGetter(className, param.classLoader, "getGyroscopeY", s -> s.gyroscopeY);
        hookTargetGetter(className, param.classLoader, "getGyroscopeZ", s -> s.gyroscopeZ);
    }

    private void hookTargetGetter(
            String className,
            ClassLoader loader,
            String methodName,
            SnapshotValue value
    ) {
        XposedHelpers.findAndHookMethod(
                className,
                loader,
                methodName,
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        BridgeSnapshot snapshot = BridgeState.INSTANCE.current();
                        if (snapshot.sequence > 0L) param.setResult(value.read(snapshot));
                    }
                }
        );
    }

    private interface SnapshotValue {
        Object read(BridgeSnapshot snapshot);
    }
}
