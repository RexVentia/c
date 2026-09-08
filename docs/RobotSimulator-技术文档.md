# RobotSimulator 技术文档

版本：当前 MuMu 12 Debug 版本  
更新日期：2026-09-07  
包名：`com.robot.simulator`

## 1. 项目定位

RobotSimulator 是一个运行在 MuMu 12 专用调试实例中的机器人运动仿真工具，用于在用户自有或获授权的测试程序中验证路线、步数、步频、位置和运动传感器数据。

本项目分为两部分：

- 本体：路线编辑、参数配置、运动仿真、记录和可视化。
- Bridge/Hook：向测试目标程序提供仿真快照的桥接骨架，具体目标程序接口暂不做定向适配。

## 2. 当前完成范围

### 2.1 路线编辑

- 使用高德 Android 地图 SDK。
- 手指或鼠标连续绘制路线。
- 路线点保存为真实纬度、经度和累计距离。
- 使用地球曲面距离计算路线长度。
- 支持起点、终点、撤销、清空、重画、命名和保存。
- Room 保存路线，退出应用后可恢复。

### 2.2 运动仿真

模拟配置包括：

- 总步数：正整数；
- 平均步频：120–151 步/分钟；
- 平均速度：2–5 m/s；
- 步频扰动幅度；
- 步频扰动周期；
- 可复现随机种子。

基础计算规则：

```text
总时长 = 总步数 × 60 / 平均步频
平均速度 = 路线长度 / 总时长
```

当平均速度不在 2–5 m/s 范围内时，参数页禁止启动，并显示建议步数范围。

### 2.3 步频扰动模型

扰动采用确定性周期模型，而不是不可复现的随机跳变：

```text
cadence(t) = 平均步频 + 扰动幅度 × cos(ωt + seedPhase)
```

周期会对齐为全程整数周期，因此：

- 平均步频保持稳定；
- 步数单调增加；
- 最终步数精确等于目标值；
- 相同参数和随机种子可以复现相同曲线；
- 扰动幅度受到 120–151 步/分钟边界约束。

### 2.4 传感器仿真

当前引擎按程序化运动模型生成：

- 加速度计 X/Y/Z；
- 陀螺仪 X/Y/Z；
- 重力向量 X/Y/Z；
- 横滚、俯仰、航向；
- 速度变化带来的加速度变化；
- 步频相位、轻微漂移和确定性扰动。

运行页提供传感器数值和历史曲线预览。暂停后不继续推进仿真时间，停止后状态固定。

## 3. 软件结构

```text
RobotSimulator
├─ app
│  ├─ Compose 界面
│  ├─ 路线与参数页面
│  ├─ SimulationViewModel
│  ├─ Room 数据库
│  └─ AIDL Bridge Service
├─ simulation-core
│  ├─ SimulationConfig
│  ├─ SimulationRules
│  ├─ DeterministicSimulationEngine
│  ├─ RoutePointCodec
│  └─ GeoMath
├─ bridge-contract
│  └─ Bridge 快照与协议定义
├─ hook-bridge
│  └─ LSPosed/Xposed 加载与 AMap 诊断骨架
├─ test-target
│  └─ Robot Bridge Test 测试目标程序
└─ scripts
   └─ MuMu 部署与阶段验证脚本
```

## 4. 核心数据流

```text
路线编辑
  → RouteEntity
  → SimulationConfig
  → DeterministicSimulationEngine
  → SimulationSnapshot
  → 界面显示 / Room 历史记录 / Bridge Service
```

`SimulationSnapshot` 包含：

- 当前经纬度；
- 累计距离和进度；
- 方向角；
- 当前速度；
- 当前步数和目标步数；
- 当前步频；
- 传感器采样；
- 运行状态。

## 5. Bridge 与 Hook 状态

已实现的骨架能力：

- 前台 Bound Service；
- AIDL 获取协议版本和当前快照；
- 监听注册与注销；
- 会话 ID 和快照序号；
- Robot Bridge Test 测试目标；
- AMap 定位类的诊断和公共位置字段适配入口。

当前阶段不包含：

- 针对某个具体机器人 APK 的内部类定向适配；
- 混淆代码逆向分析；
- 未提供 APK 或源码的目标程序适配。

后续接入新测试程序时，需要先确认包名、进程、定位数据流和步数数据流，再决定使用 Bridge、应用层接口还是目标程序内部数据模型。

## 6. MuMu 12 环境

- 专用实例：`RobotSimulator-Debug`；
- 实例编号：1；
- Android 12 / API 31；
- ABI：x86_64；
- ADB：`127.0.0.1:16416`；
- 主实例：不修改；
- Debug APK：`app/build/outputs/apk/debug/app-debug.apk`。

部署流程：

1. 构建 Debug APK；
2. 启动专用 MuMu 实例；
3. 读取实例实际 ADB 端口；
4. 覆盖安装 APK；
5. 启动 `com.robot.simulator/.MainActivity`；
6. 用 ADB 日志和测试脚本进行验证。

## 7. 测试结果

已通过：

- 步频边界：119、120、151、152；
- 平均速度边界：2 m/s、5 m/s 及越界值；
- 路线中点插值；
- 终点经纬度精确落点；
- 目标步数精确结束；
- 步频扰动可复现、受边界约束、步数不倒退；
- 数据库契约测试；
- Debug APK 构建；
- MuMu 覆盖安装和启动。

核心单元测试当前为 9 项，数据库测试为 1 项，均通过。

## 8. 已知问题

当前 Lint 仍报告 3 个桥接相关问题：

1. `GlobalLocationPublisher` 对系统定位权限的显式检查；
2. 模拟定位权限声明应进一步拆分到 Debug Manifest；
3. 前台服务需要补充 `foregroundServiceType` 声明。

这些问题不影响当前 Debug APK 构建，但在进入长期运行或发布前应修复。

另外，当前工作区还有可重新生成的构建产物、调试临时文件和 MuMu 备份；源代码与 Git 历史不受影响。

## 9. Git 状态与版本管理

- 私有远程仓库：`https://github.com/RexVentia/c.git`；
- 最近一次成功推送：`2e411a4`；
- 当前步频扰动和界面改动已暂存，但尚未完成新的提交和推送；
- 本地高德 Key 保存在 `local.properties`，不应提交到仓库。

## 10. 下一步建议

按优先级建议：

1. 提交并推送当前步频扰动版本；
2. 清理构建产物和调试临时文件；
3. 修复 3 个桥接 Lint 问题；
4. 在 Robot Bridge Test 中完成 AIDL 重连、暂停/继续和进程重启测试；
5. 获得具体机器人测试程序后，再做定向接口适配；
6. 最后再决定是否需要多目标程序配置和更细的传感器模型。

