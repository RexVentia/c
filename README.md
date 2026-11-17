# RobotSimulator

Android 路线与运动数据模拟工具，包含主程序、Xposed 兼容 Hook 模块和独立桥接测试程序。适用于自有或获授权应用的开发与调试。

**当前版本：0.2.0 预览版。实测环境：MuMu 12 / Android 12 / x86_64。** 项目要求 Android 12 及以上，但更新 Android 版本的完整运行流程尚未验证。

[下载安装包](https://github.com/RexVentia/c/releases/tag/v0.2.0) · [完整使用说明](docs/使用说明.md) · [技术文档](docs/RobotSimulator-技术文档.md) · [兼容性记录](docs/兼容性测试-2026-11-17.md) · [更新记录](CHANGELOG.md)

> 预览版本也可在 [全部 Releases](https://github.com/RexVentia/c/releases) 中找到。公开 APK 不内置开发者的高德地图 Key；首次使用请在“地图设置”中填写自己的 Key。

## 下载哪个文件

| 文件 | 用途 | 是否必装 |
| --- | --- | --- |
| `RobotSimulator-0.2.0-debug.apk` | 画路线、配置参数、运行模拟、查看历史 | 是 |
| `RobotHook-0.2.0-debug.apk` | 通过 LSPosed / Vector 兼容接口向选定测试应用提供数据 | 需要 Hook 时安装 |
| `RobotBridgeTest-0.2.0-debug.apk` | 比较桥接原始数据与目标程序读取值 | 初次配置建议安装 |
| `SHA256SUMS.txt` | 下载文件完整性校验 | 按需 |
| `SIGNING-CERTIFICATES.txt` | APK 证书指纹，申请地图 Key 时使用 | 按需 |

三个 APK 使用相同版本号，均为 Debug 测试构建。主程序单独绘图和模拟不需要 Xposed；Hook 模块需要已正常运行的兼容框架。框架本身不包含在本项目下载包中。

## 快速开始

1. 安装主程序。需要验证桥接时，同时安装 Hook 模块和 Robot Bridge Test。
2. 打开主程序右上角“地图设置”，按页面显示的包名和 SHA1，在高德开放平台创建 **Android 平台 SDK** Key，然后填入保存。
3. 在“路线”页选择“新建路线”，阅读地图提示后决定是否同意启用地图；绘制、命名并保存路线。
4. 进入“参数”，选路线、填总步数和平均步频。可直接点击“填入建议步数”。
5. 点击“开始模拟”；运行页支持暂停、继续、提前停止，结束记录保存在“历史”。
6. 验证 Hook 时，在框架管理器中启用 Robot Hook，作用域先只选择 Robot Bridge Test，然后完全关闭并重新打开测试程序。运行模拟后比较两组数据。

地图 Key 的服务类型、包名、签名证书 SHA1 必须对应当前安装的 APK，详见[高德官方申请说明](https://lbs.amap.com/api/android-location-sdk/guide/create-project/get-key/)。更换已使用的 Key 后，请完全退出主程序再重新打开。

## 功能

- 地图拖动画路线、撤销、重画和本地保存。
- 根据总步数、路线长度和平均步频计算时长与速度，并校验参数。
- 按公里分段的步频、配速起伏；同一随机种子可复现。
- 位置、方向、速度、步数、步频、加速度计、陀螺仪、重力和姿态数据。
- AIDL 快照服务及监听；Xposed 兼容模块；本地桥接测试目标。
- 地图内使用 GCJ-02；Android `Location` 边界转换为 WGS-84。

## 从源码构建

需要 JDK 17、Android SDK 36 和 Build Tools 36.0.0；仓库的 Windows Gradle Wrapper 使用 Gradle 9.4.1。

在仓库根目录创建不提交到 Git 的 `local.properties`：

```properties
sdk.dir=C:/Android/Sdk
```

然后运行：

```powershell
.\gradlew.bat --no-daemon --no-configuration-cache -PpublicBuild=true :app:assembleDebug :hook-bridge:assembleDebug :test-target:assembleDebug :simulation-core:testDebugUnitTest :app:testDebugUnitTest :bridge-contract:testDebugUnitTest
```

首次构建需要联网下载依赖。APK 分别位于 `app`、`hook-bridge`、`test-target` 的 `build/outputs/apk/debug/` 目录。`-PpublicBuild=true` 强制不将本地地图 Key 编入主程序。自己编译后的签名可能不同，请使用安装后“地图设置”显示的 SHA1 配置 Key。

本地开发也可在 `local.properties` 设置 `amap.api.key`；手动填写的 Key 优先于编译时默认值。不要提交 Key、签名私钥或 `local.properties`。

新增地图配置的设备测试使用独立包名，避免改写现有主程序的数据。在连接专用 Android 12 测试设备后运行：

```powershell
.\gradlew.bat --no-daemon --no-configuration-cache -I scripts/Public-QA.init.gradle -PpublicBuild=true :app:connectedDebugAndroidTest
```

此命令安装 `com.robot.simulator.publicqa`，检查空内置 Key、个人 Key 本地保存、隐私同意前不初始化地图及签名 SHA1 格式。该临时包不用于桥接和正式发布；正式构建请去掉 `-I` 参数。

## 项目结构

| 目录 | 内容 |
| --- | --- |
| `app` | Compose 界面、地图、Room 数据库、桥接服务 |
| `simulation-core` | 路线和可复现运动模型、单元测试 |
| `bridge-contract` | AIDL、快照类型、坐标转换 |
| `hook-bridge` | Xposed 兼容模块 |
| `test-target` | Robot Bridge Test |
| `xposed-api-stubs` | 仅编译期使用的最小接口声明 |
| `scripts` | MuMu 部署和阶段验证脚本 |

## 已知限制

- 当前发布为测试预览版，不代表已验证所有 Android 设备或第三方应用。
- Hook 是否兼容目标应用取决于它的数据接口和运行环境；框架加载成功不等于目标应用完整流程通过。
- 0.2.0 之前的同版本号旧 APK 不能仅凭 `0.1.0` 判断新旧；本次使用独立的 0.2.0 版本号及文件校验值。
- 未开启模拟或快照序号为 0 时，测试界面的默认值不代表桥接一定失败。
- Android 14 及以上前台服务适配尚未完成；本版本以已验证的 Android 12 调试环境为准。
- 系统测试定位需要相应模拟位置权限，并保留 Android 模拟位置标记。AIDL 和 Hook 通道可单独验证。
- 应用后台长期运行、进程被系统终止后的恢复行为仍需进一步验证。

地图 SDK 的隐私说明见[高德开放平台](https://lbs.amap.com/pages/privacy/)。路线、历史记录和手动填写的 Key 保存于应用本地；应用不提供项目作者运营的云端同步服务。
