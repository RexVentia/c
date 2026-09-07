package com.robot.simulator.hook;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;
import com.robot.simulator.bridge.BridgeContract;
import com.robot.simulator.bridge.BridgeSnapshot;
import com.robot.simulator.bridge.IRobotSimulationBridge;
import com.robot.simulator.bridge.IRobotSimulationListener;
import de.robv.android.xposed.XposedBridge;

final class BridgeState implements ServiceConnection, IBinder.DeathRecipient {
    static final BridgeState INSTANCE = new BridgeState();

    private volatile BridgeSnapshot snapshot = BridgeSnapshot.empty();
    private volatile IRobotSimulationBridge bridge;
    private volatile boolean binding;
    private volatile Context lastContext;

    private final IRobotSimulationListener listener = new IRobotSimulationListener.Stub() {
        @Override public void onSnapshot(BridgeSnapshot value) {
            if (value != null && value.sequence >= snapshot.sequence) snapshot = value;
        }
    };

    private BridgeState() {}

    BridgeSnapshot current() { return snapshot; }

    synchronized void connect(Context source) {
        if (bridge != null || binding) return;
        if (source == null) return;
        Context context = source.getApplicationContext();
        if (context == null) context = source;
        lastContext = context;
        Intent intent = new Intent().setComponent(new ComponentName(
                BridgeContract.SIMULATOR_PACKAGE,
                BridgeContract.SERVICE_CLASS
        ));
        binding = true;
        try {
            if (!context.bindService(intent, this, Context.BIND_AUTO_CREATE)) binding = false;
        } catch (Throwable throwable) {
            binding = false;
            XposedBridge.log("RobotHook: bridge bind failed: " + throwable);
        }
    }

    @Override public void onServiceConnected(ComponentName name, IBinder service) {
        binding = false;
        bridge = IRobotSimulationBridge.Stub.asInterface(service);
        try {
            service.linkToDeath(this, 0);
            if (bridge.getProtocolVersion() != BridgeContract.PROTOCOL_VERSION) {
                XposedBridge.log("RobotHook: protocol mismatch");
                bridge = null;
                return;
            }
            snapshot = bridge.getCurrentSnapshot();
            bridge.registerListener(listener);
            XposedBridge.log("RobotHook: bridge connected");
        } catch (RemoteException | SecurityException exception) {
            bridge = null;
            XposedBridge.log("RobotHook: bridge handshake failed: " + exception);
        }
    }

    @Override public void onServiceDisconnected(ComponentName name) {
        bridge = null;
        binding = false;
        connect(lastContext);
    }

    @Override public void binderDied() {
        bridge = null;
        binding = false;
        connect(lastContext);
    }
}
