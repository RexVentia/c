# 步道乐跑高德定位诊断记录

日期：2026-09-07

测试环境：MuMu 12 专用调试实例（Android 12 / API 31）

目标包名：`com.lptiyu.tanke`

诊断方式：LSPosed 只读记录高德定位配置与回调，不修改回调参数或返回值。

## 关键结果

1. Hook 模块已在目标进程加载并连接 RobotSimulator Bridge。
2. 目标应用主动调用 `AMapLocationClientOption.setMockEnable(false)`。
3. 定位模式为 `Hight_Accuracy`，回调间隔最终设置为 2000 ms。
4. 高德连续返回同一个位置：
   - 纬度：`23.184423160918985`
   - 经度：`113.42466790662859`
   - 坐标系：`GCJ02`
   - Provider：`lbs`
   - LocationType：`9`
   - Mock：`false`
   - TrustedLevel：`1`
   - ErrorCode：`0`
   - ErrorInfo：`success`
5. 上述坐标位于广州，与页面显示位置一致；不是 RobotSimulator 当前发布的新疆模拟位置。

## 代表性日志

```text
RobotHook-AMap: setMockEnable(false)
RobotHook-AMap: option mock=false mode=Hight_Accuracy once=true intervalMs=3000
RobotHook-AMap: option mock=false mode=Hight_Accuracy once=false intervalMs=2000
RobotHook-AMap: raw lat=23.184423160918985 lon=113.42466790662859 type=9 provider=lbs mock=false trusted=1 error=0 info=success coord=GCJ02
```

## 结论

地图不匹配不是路线插值或系统 GPS 发布失败导致的。目标应用明确关闭了高德模拟位置接收，高德定位链随后持续返回广州的类型 9 末次位置缓存；因此只向 Android GPS/NETWORK Provider 发布模拟位置不足以改变该应用内的高德定位结果。

下一步应先在授权沙盒内制作高德定位适配层：仅把 `AMapLocation` 回调中的定位字段映射到 Bridge 快照，同时保留错误码、回调节奏和 GCJ-02 坐标语义，再验证地图显示与系统模拟路线是否一致。
