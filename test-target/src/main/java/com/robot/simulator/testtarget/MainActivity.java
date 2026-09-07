package com.robot.simulator.testtarget;

import android.app.Activity;
import android.graphics.Typeface;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.robot.simulator.bridge.BridgeSnapshot;
import java.util.Locale;

public final class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final RobotDataSource robotData = new RobotDataSource();
    private BridgeClient bridgeClient;
    private TextView directView;
    private TextView hookView;

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            render();
            handler.postDelayed(this, 250L);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        bridgeClient = new BridgeClient(this, () -> handler.post(this::render));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(48, 36, 48, 36);
        TextView title = new TextView(this);
        title.setText("MuMu 全局桥接测试目标");
        title.setTextSize(26f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        content.addView(title);

        Button reconnect = new Button(this);
        reconnect.setText("重新连接 RobotSimulator");
        reconnect.setOnClickListener(v -> bridgeClient.connect());
        content.addView(reconnect);

        directView = section(content, "AIDL 原始数据");
        hookView = section(content, "目标程序读取结果（由 LSPosed 接管）");

        TextView note = new TextView(this);
        note.setText("提示：0/-1 表示 Hook 尚未接管；两组实时数据一致表示桥接成功。全局位置使用 Android 测试定位接口，会保留“模拟位置”标记，仅用于授权调试。");
        note.setTextSize(16f);
        note.setPadding(0, 36, 0, 0);
        content.addView(note);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        setContentView(scroll);
        bridgeClient.connect();
    }

    private TextView section(LinearLayout content, String label) {
        TextView header = new TextView(this);
        header.setText(label);
        header.setTextSize(20f);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setPadding(0, 32, 0, 8);
        content.addView(header);
        TextView value = new TextView(this);
        value.setTextSize(18f);
        value.setTypeface(Typeface.MONOSPACE);
        content.addView(value);
        return value;
    }

    @Override protected void onStart() {
        super.onStart();
        handler.post(refresh);
    }

    @Override protected void onStop() {
        handler.removeCallbacks(refresh);
        super.onStop();
    }

    @Override protected void onDestroy() {
        bridgeClient.disconnect();
        super.onDestroy();
    }

    private void render() {
        BridgeSnapshot value = bridgeClient.snapshot();
        directView.setText(String.format(Locale.US,
                "连接：%s  协议序号：%d\n状态：%s\n位置：%.6f, %.6f\n步数：%d/%d  步频：%.1f\n速度：%.2f  方向：%.1f\n加速度：%.3f %.3f %.3f\n陀螺仪：%.3f %.3f %.3f",
                bridgeClient.connected() ? "是" : "否", value.sequence, value.status,
                value.latitude, value.longitude, value.currentSteps, value.totalSteps,
                value.cadenceSpm, value.speedMps, value.headingDeg,
                value.accelerometerX, value.accelerometerY, value.accelerometerZ,
                value.gyroscopeX, value.gyroscopeY, value.gyroscopeZ));

        Location location = robotData.getLocation();
        hookView.setText(String.format(Locale.US,
                "位置：%.6f, %.6f  精度：%.1f\n步数：%d  步频：%.1f\n速度：%.2f  方向：%.1f\n加速度：%.3f %.3f %.3f\n陀螺仪：%.3f %.3f %.3f",
                location.getLatitude(), location.getLongitude(), location.getAccuracy(),
                robotData.getStepCount(), robotData.getCadenceSpm(),
                location.getSpeed(), location.getBearing(),
                robotData.getAccelerometerX(), robotData.getAccelerometerY(),
                robotData.getAccelerometerZ(), robotData.getGyroscopeX(),
                robotData.getGyroscopeY(), robotData.getGyroscopeZ()));
    }
}
