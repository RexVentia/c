package com.robot.simulator.testtarget;

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

final class BridgeClient implements ServiceConnection {
    interface Callback { void onChanged(); }

    private final Context context;
    private final Callback callback;
    private volatile IRobotSimulationBridge bridge;
    private volatile BridgeSnapshot snapshot = BridgeSnapshot.empty();

    private final IRobotSimulationListener listener = new IRobotSimulationListener.Stub() {
        @Override public void onSnapshot(BridgeSnapshot value) {
            snapshot = value;
            callback.onChanged();
        }
    };

    BridgeClient(Context context, Callback callback) {
        this.context = context.getApplicationContext();
        this.callback = callback;
    }

    void connect() {
        Intent intent = new Intent().setComponent(new ComponentName(
                BridgeContract.SIMULATOR_PACKAGE,
                BridgeContract.SERVICE_CLASS
        ));
        context.bindService(intent, this, Context.BIND_AUTO_CREATE);
    }

    void disconnect() {
        IRobotSimulationBridge current = bridge;
        if (current != null) {
            try { current.unregisterListener(listener); } catch (RemoteException ignored) {}
        }
        try { context.unbindService(this); } catch (IllegalArgumentException ignored) {}
    }

    BridgeSnapshot snapshot() { return snapshot; }
    boolean connected() { return bridge != null; }

    @Override public void onServiceConnected(ComponentName name, IBinder service) {
        bridge = IRobotSimulationBridge.Stub.asInterface(service);
        try {
            snapshot = bridge.getCurrentSnapshot();
            bridge.registerListener(listener);
        } catch (RemoteException | SecurityException ignored) {
            bridge = null;
        }
        callback.onChanged();
    }

    @Override public void onServiceDisconnected(ComponentName name) {
        bridge = null;
        callback.onChanged();
    }
}
