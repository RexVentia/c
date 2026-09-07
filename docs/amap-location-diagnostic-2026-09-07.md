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

## 坐标精度修复与复测

后续确认路线编辑器保存的是高德 GCJ-02 坐标，而 Android `Location` 输入应使用 WGS-84。原实现把 GCJ-02 直接交给 Android 定位层，高德再次转换后产生约 700 米的重复偏移。

修复内容：

- Bridge 和路线数据库继续保存 GCJ-02。
- 进入 Android `Location` 边界前，将 GCJ-02 精确反算为 WGS-84。
- 目标应用读取 `AMapLocation` 时，直接交付同一时刻的 Bridge GCJ-02 坐标、精度、速度和方向。
- 首条高德缓存或离线回调也使用当前 Bridge 快照，避免起步跳点。

2026-09-07 复测证据：

```text
RobotHook-AMap: raw lat=0.0 lon=0.0 type=6 provider=lbs mock=false error=0 coord=GCJ02
RobotHook-AMap: delivered seq=48 lat=45.545441383283645 lon=84.93048824614708 accuracy=3.0 speed=3.004414 bearing=270.00003
RobotHook-AMap: delivered seq=78 lat=45.545441383283645 lon=84.93037115488907 accuracy=3.0 speed=2.6932259 bearing=270.00003
```

复测期间系统 `mock_location_app` 仍为 `null`，MuMu 系统 GPS 仍返回广州坐标；目标应用收到的新疆连续路线来自限定作用域内的 LSPosed Bridge。首条无效缓存没有再作为目标应用实际读取的位置，后续坐标按模拟路线连续变化。
