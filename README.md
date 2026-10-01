# FTC Shooter AutoTune

机器人本地网页调参库：连接机器人 Wi-Fi，在浏览器里配置飞轮电机，自动辨识飞轮模型，验证多个速度，调整 PID，再真实触发 preshooter 做带载优化，最后导出六个 Java 常量。

- 源码仓库：<https://github.com/OLeslieO/ftc-shooter-autotune>
- Maven 仓库：`https://oleslieo.github.io/ftc-shooter-autotune/maven/`
- 当前版本：**0.1.2**（`io.github.oleslieo:shooter-autotune:0.1.2`）

运行时无需电脑端服务器、Node.js、云服务或互联网。网页由 Robot Controller 上的 OpMode 在端口 **8082** 提供（被占用时自动改用 8083、8084，DS telemetry 显示实际地址），网页资源随 APK 一起安装。只有首次 Gradle 下载依赖时需要互联网。

## 目录

1. [安装](#安装)
2. [使用方法](#使用方法)
3. [控制器与单位](#控制器与单位)
4. [安全行为与故障排查](#安全行为与故障排查)
5. [库结构](#库结构)
6. [开发、测试与发布](#开发测试与发布)

## 安装

### 环境要求

| 项目 | 要求 |
| --- | --- |
| FTC SDK | 12.0.0（库以 `compileOnly` 方式引用，由你的机器人工程提供） |
| Android | minSdk 24，compileSdk 30 |
| Java | Java 8 字节码，兼容 FTC 工程默认设置 |
| 硬件 | 1 或 2 台带编码器的射手电机（`DcMotorEx`），1 台 preshooter / feeder 电机 |

不依赖 PedroPathing、FTC Dashboard 或其他第三方库。

### 方式一：远程 Maven 安装（推荐）

**第 1 步：添加 Maven 仓库。** 打开 FTC 工程根目录的 `build.gradle`，在 `allprojects.repositories` 中加入下面的 `maven` 块，保留原有仓库：

```gradle
allprojects {
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri('https://oleslieo.github.io/ftc-shooter-autotune/maven/')
            content { includeGroup 'io.github.oleslieo' }
        }
    }
}
```

如果你的工程把仓库集中写在 `build.dependencies.gradle` 的 `repositories` 块，或 `settings.gradle` 的 `dependencyResolutionManagement.repositories` 里，把同一个 `maven` 块放到那里即可。不要放进 `pluginManagement` 或 `buildscript.repositories`。

**第 2 步：添加依赖。** 在 `TeamCode/build.gradle` 的 `dependencies` 块中加入：

```gradle
implementation 'io.github.oleslieo:shooter-autotune:0.1.2'
```

这个 AAR 会自动带上核心库 `io.github.oleslieo:shooter-autotune-core:0.1.2` 和网页资源，不需要再单独声明。

**第 3 步：复制入口文件。** 把本仓库 `examples/teamcode/shooter/` 下的 **`Tuning.java`** 和 **`Constants.java`** 复制到目标工程：

```text
TeamCode/src/main/java/org/firstinspires/ftc/teamcode/shooter/
```

`Tuning.java` 只负责注册 Driver Station 入口，并把本地六个常量交给库：

```java
package org.firstinspires.ftc.teamcode.shooter;

import com.qualcomm.robotcore.eventloop.opmode.TeleOp;

import org.ftc.shooter.tuner.autotune.Gains;
import org.ftc.shooter.tuner.ftc.ShooterAutoTuneOpMode;

@TeleOp(name = "Shooter AutoTune", group = "Tuning")
public final class Tuning extends ShooterAutoTuneOpMode {
    @Override
    protected Gains testGains() {
        return new Gains(Constants.SHOOTER_KS, Constants.SHOOTER_KV, Constants.SHOOTER_KA,
                Constants.SHOOTER_KP, Constants.SHOOTER_KI, Constants.SHOOTER_KD);
    }
}
```

`Constants.java` 初始六个字段全为零，表示尚未调参。如果使用其他包名，两个文件一起改；如果已经有自己的常量类，只需改 `testGains()` 的引用。不需要复制控制循环、FTC 硬件层或网页文件。

**第 4 步：同步并安装。** Android Studio 中 Gradle Sync，然后编译并安装到 Robot Controller。Driver Station 的 TeleOp 列表里会出现 **`Shooter AutoTune`**。

如果 Sync 报 `Could not find io.github.oleslieo:shooter-autotune`，先在浏览器确认下面的地址能打开，再运行 `./gradlew --refresh-dependencies :TeamCode:assembleDebug` 清掉 Gradle 缓存的 404：

```text
https://oleslieo.github.io/ftc-shooter-autotune/maven/io/github/oleslieo/shooter-autotune/0.1.2/shooter-autotune-0.1.2.pom
```

### 方式二：本地源码安装（改库源码时使用）

1. 把本仓库克隆或复制到目标工程的 `external/ftc-shooter-autotune`。
2. 在目标工程根 `settings.gradle` 加入：

```gradle
include ':external:ftc-shooter-autotune:tuner-core'
include ':external:ftc-shooter-autotune:ftc-library'
```

3. 在 `TeamCode/build.gradle` 的 `dependencies` 块中加入：

```gradle
implementation project(':external:ftc-shooter-autotune:ftc-library')
```

4. 同样复制 `examples/teamcode/shooter/Tuning.java` 和 `Constants.java` 到 TeamCode。
5. Gradle Sync、编译并安装。网页 assets 随 Android library 合并到 APK，不需要手动追加 sourceSets。

不要同时使用远程坐标和本地 `project(...)` 依赖，会出现重复类。从本地模块切换到远程坐标时，删掉上面的 `implementation project(...)` 和两个 `include`。

### 升级版本

把 `implementation 'io.github.oleslieo:shooter-autotune:0.1.2'` 的版本号改为新版本后重新 Sync。可用版本列表见 [maven-metadata.xml](https://oleslieo.github.io/ftc-shooter-autotune/maven/io/github/oleslieo/shooter-autotune/maven-metadata.xml)。已发布版本不会被覆盖。

## 使用方法

完整流程：**打开网页 → Configure → 空载调参 → 带载验证 → 导出 Constants → Test**。

### 1. 打开网页

1. Driver Station 选择 **`Shooter AutoTune`**，按 **INIT**。此时启动网页服务，不会转动电机。
2. 电脑或手机连接机器人的 Wi-Fi。
3. 用 `http`打开：
   - Control Hub：`http://192.168.43.1:8082`
   - 手机 Robot Controller 的 Wi-Fi Direct：通常为 `http://192.168.49.1:8082`
   - 若机器人地址不同，用实际 RC 地址。DS 的 telemetry 会显示实际地址和端口。不要用 8080 或 8081，那是 RC 自己的网页控制台和 WebSocket 端口，打开会看到 RC 页面或 `websocket upgrade failure`。
4. 建议使用当前版 Chrome 或 Edge，并保持调参标签页在前台。

网页只在该 OpMode 存活时可访问。DS STOP 后网页服务器关闭。网页上的 **Stop all motors** 只停止实验，网页保持在线。

### 2. Configure：配置硬件

1. 选择 Single / Dual 布局，填写电机名称和方向。
2. 选择 preshooter 的 Power 或 Velocity 模式，并设置对应数值。
3. 填入目标速度，按 **Save configuration**。
4. 按 DS **START**，逐个选择电机，按 **Pulse selected motor**。
5. 方向测试只运行 **0.3 秒、最大 0.12 功率**。肉眼确认旋转方向，并确认射手正转时编码器读到正速度。有问题就改方向重新保存。

配置保存在 RC 的 SharedPreferences，下次打开网页会预填，但**每次运行 OpMode 都要按一次 Save configuration 才会应用**。保存新硬件配置后，本次会话之前的调参结果立即失效；若配置硬件失败会进入 FAULT，必须重新成功保存后才能运行。

默认值：

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| Shooter 1 / 2 名称与方向 | `shooterUp` REVERSE / `shooterDown` FORWARD | 与 RC 硬件配置中的名称一致 |
| Preshooter 名称与方向 | `preShooter` FORWARD | |
| Target velocity | 1440 ticks/s |  |
| Minimum battery voltage | 10.5 V | 电压过低或无有效读数时停止 |
| Target ramp | 3000 ticks/s² | 软件目标速度斜坡，提供加速度前馈输入 |
| Preshooter demand | 0.6 | Power 模式为 0–1；Velocity 模式为 ticks/s |
| Observe each shot | 2.5 s | 从送弹开始记录到窗口结束 |
| Shots per candidate | 3 | 每组参数至少 2 发，最多 8 发 |

### 3. PIDFTuner：空载调参

清空待发射物体，保留实际飞轮和传动机构。勾选 **Shooter is unloaded; directions checked**，按 **Start unloaded tuning**。

自动阶段：

1. **IDENTIFY**：六段有限功率阶跃，总计约 9.6 秒，采集电压与速度时间序列，用输出误差法拟合 `V = kS + kV·v + kA·a`：对电压做时间常数 `τ = kA/kV` 的一阶滤波后回归速度，并一维搜索 `τ`，不对带噪声的编码器速度做微分。拒绝缺乏激励、非物理参数或速度域 R² 小于 0.8 的模型；Trials 区域会显示 R²、τ 和三个前馈参数。
2. **FEEDFORWARD**：只用前馈，在目标速度的 55%、80%、100% 验证。任一电机稳态误差超过 15% 则停止。
3. **KP**：由模型时间常数 `τ = kA/kV` 计算增益尺度，测试四个有界候选（含纯前馈基线）。每组用 65%、100%、80% 目标速度做上升和下降实验。
4. **KD**：仅当最佳响应超调超过 8% 时测试少量 D 候选。
5. **KI**：仅当仍有超过 2% 的稳态误差时测试少量 I 候选。
6. **AWAIT_LOAD**：停止全部电机，等待装载并明确启用带载实验。

候选评分包含按速度归一化的 RMSE、超调、稳态误差和恢复时间。每组实验从停转开始，避免残余转速影响比较。这是基于模型的有限候选优化，不是遍历整个 PIDF 参数空间。

### 4. 带载优化：真实触发 preshooter

在 **AWAIT_LOAD** 装好物体，安排安全的接收区域，确认一次手动送弹只送一发。勾选 **Game pieces loaded; firing area clear**，按 **Arm and run loaded tests**。

每组参数先让所有射手在目标速度 ±5% 内稳定至少 0.35 秒。稳定后网页 phase/message 会显示 "ready; hold gamepad1.right_bumper to feed"，此时**按住 `gamepad1.right_bumper` 手动送弹，松开停止**；送弹时长完全由你按住的时间决定，库不会自动掐断，也不会自动开始。程序比较当前增益及 `kP × 1.2`、`kP × 0.8`；若空载最佳 `kP = 0`，则按模型生成两个非零反馈候选。选出最好的参数后，再用新的一组射击做 **VERIFY**。

默认需要 **3 组 × 3 发 + 3 发验证 = 12 发**，每一发都需要你在"ready"提示后手动按一次右肩键，不是全自动连发。供弹能力不足时先调整机构或实验配置；飞轮运转时不要接触机构。

每发、每个射手电机分别记录：

| 指标 | 定义 |
| --- | --- |
| Velocity drop | 送弹前转速减观察窗口内最低转速 |
| Maximum error | 窗口内相对目标速度的最大绝对误差 |
| Recovery time | 从开始送弹到最后一次离开 ±5% 速度带的时间；之后须连续 0.3 秒保持在带内 |
| Overshoot | 观察窗口内高于目标速度的最大值 |
| RMSE | 按真实采样间隔加权的速度误差均方根 |

未恢复会显示 **Not recovered**，不会被当成正常恢复。优化以每发最差电机的分数为准，再对多发平均。无法恢复、超调超过 15% 或最终误差超过 5% 的候选不能成为有效结果。

如果所有射手都没有至少 `max(10 ticks/s, 目标的 1%)` 的转速下降，实验以 **No shot load detected** 结束，防止空仓调参通过。这是通过速度扰动推断负载，**不是弹丸传感器**；一次脉冲多发、打滑或卡弹仍需人工检查。

### 5. 导出并粘贴 Constants

带载验证通过后，网页才启用 **Export Java constants**，下载 `ShooterConstants.txt`。**Download metrics JSON** 可保存候选分数、逐发逐电机指标和当前配置。

把导出的六条赋值语句替换到目标工程的 `Constants.java`：

```java
public static double SHOOTER_KS = ...;
public static double SHOOTER_KV = ...;
public static double SHOOTER_KA = ...;
public static double SHOOTER_KP = ...;
public static double SHOOTER_KI = ...;
public static double SHOOTER_KD = ...;
```

网页不能改电脑上的 Java 源码，需要手动替换后重新编译安装。

最近一次成功结果的文本保存在 RC 上，网页的 **Previous session constants** 可再次查看；它只是历史参考，不会在下次会话自动当作已验证结果。改动配置后必须重新验证。

### 6. Test：验证常量

- 同一会话调参成功后，可直接按 **Start Test**，使用刚验证的参数。
- 粘贴常量并重新安装后，重新 INIT → Save configuration → DS START → **Start Test**，此时使用编译进 `Constants.java` 的六个字段。
- **`gamepad1.right_bumper`** 手动启动 preshooter；首次送弹需要所有射手在目标速度 ±10% 内。按住期间持续送弹，松开停止。库没有自动的超速或过流保护，Driver Station Stop 和网页 **Stop all motors** 是唯一的强制停止手段。
- 网页持续显示目标/实际速度、每台电机功率、电流、供电电压、阶段和逐发数据。网页 Stop 或 DS Stop 均停止输出。

### 7. 在比赛代码中使用常量

导出的常量属于**软件电压前馈 + PID** 控制器，单位见下一节。它们不能填进 `DcMotorEx.setVelocityPIDFCoefficients()`，REV Hub 内置速度 PIDF 的系数单位完全不同。比赛代码中可直接复用 `tuner-core` 里的 `VelocityController`，或按下面的公式自行实现。

## 控制器与单位

```text
commandVolts = kS·sign(targetVelocity)
             + kV·targetVelocity
             + kA·targetAcceleration
             + kP·(targetVelocity − measuredVelocity)
             + kI·integral(error)
             − kD·filteredMeasuredAcceleration

motorPower = clamp(commandVolts / measuredBatteryVoltage, 0, 1)
```

| 常量 | 单位 |
| --- | --- |
| kS | V |
| kV、kP | V / (ticks/s) |
| kA、kD | V / (ticks/s²) |
| kI | V / tick |

D 对测量求导，避免目标阶跃引起 derivative kick；恒定目标时等价于误差的导数。I 采用条件积分和贡献限幅，避免饱和累积。目标为零时清空控制器状态。每台射手有独立控制器、积分和反馈，当前版本使用**一套共同的六个常量**，适用于相同电机和相近传动。明显不对称的双飞轮可能无法通过验证。

射手设置为 `RUN_WITHOUT_ENCODER` 并使用 `setPower()`，但仍读取编码器速度。Preshooter 可选 SDK `RUN_USING_ENCODER` + `setVelocity()`；本系统不调 preshooter 的 Hub PIDF。

## 安全行为与故障排查

- **Browser disconnected**：检查 Robot Controller Wi-Fi、网页地址和浏览器是否仍能访问 Robot Controller。
- **Loop missed deadline**：两次控制更新间隔超过 200 ms 时停止。这是软件检测，不是独立硬件 watchdog；DS STOP 和 Hub 自身保护仍然是最后防线。
- **Battery / stall**：电压持续 0.5 秒低于 Minimum battery voltage（默认 10.5 V）或无有效读数时停止，瞬时压降不触发；故障信息会给出实测电压。射手功率大于 0.2 且速度低于 30 ticks/s 持续 0.8 秒视为堵转或编码器故障并停止。
- **Negative encoder**：检查方向和接线，不要用取绝对值掩盖问题。
- **Insufficient excitation / poor fit**：检查负载摩擦、编码器噪声和两台电机是否一致。模型验证不通过时不会进入带载射击。
- **No power headroom**：降低目标速度；控制器功率上限固定为 1.0（满功率），无法再调整。
- **No shot load detected**：检查是否空仓，以及手动送弹时是否真的把一发送进飞轮。排障后先 Stop，再重新执行完整流程。
- **网页打不开**：确认 DS 已 INIT 此 OpMode、Wi-Fi 和 RC IP 正确、URL 使用 DS telemetry 显示的端口（默认 8082）。DS STOP 后页面不可访问是正常现象。
- **网页接受命令但没动作**：看 Command 状态；电机操作需要 DS START、已保存配置和有效网页心跳。
- **Gradle 找不到依赖**：见[安装](#方式一远程-maven-安装推荐)末尾的检查方法。
- **结果差异**：本库辨识空载机构，再通过真实射击优化恢复；它没有弹丸速度传感器，也不标定投射距离或命中率。

## 库结构

```text
examples/teamcode/shooter/
  Tuning.java                          复制到 TeamCode：注册 OpMode，提供本地常量
  Constants.java                       复制到 TeamCode：六个 SHOOTER_K* 字段

tuner-core/  (io.github.oleslieo:shooter-autotune-core, 纯 Java)
  autotune/AutoTuneManager.java        非阻塞状态机；实验、保护、结果管理
  autotune/ShooterConfig.java          硬件名称、方向、速度和安全配置
  autotune/ShooterHardware.java        硬件无关接口
  autotune/FeedforwardTuner.java       kS/kV/kA 系统辨识
  autotune/PIDTuner.java               基于模型的增益候选
  autotune/VelocityController.java     电压补偿、抗饱和和测量微分
  autotune/LoadedShotTest.java         真实送弹后的逐电机记录
  autotune/PerformanceMetrics.java     误差、恢复时间和评分
  autotune/Gains.java                  常量和 Java 导出

ftc-integration/  (源码目录，由 ftc-library 编译)
  ftc/ShooterAutoTuneOpMode.java       Configure() / PIDFTuner() / Test()，OpMode 与网页命令入口
  ftc/FtcShooterHardware.java          DcMotorEx / 电压 / 电流适配
  ftc/AutoTuneWebServer.java           NanoHTTPD、命令队列、心跳和导出
  ftc/AutoTuneJson.java                配置和 telemetry JSON

ftc-library/  (io.github.oleslieo:shooter-autotune, Android AAR)
  src/main/assets/shooter-autotune/    index.html / app.js / style.css，无外部依赖网页
```

网页线程只接收命令、读取不可变 JSON 快照，不访问硬件。OpMode 线程执行全部电机读写，约每 20 ms 更新控制器，每 100 ms 发布 telemetry；网页约每 150 ms 拉取一次。控制器使用测得的时间间隔，DS 调度抖动不会改变积分和微分尺度。

旧版 `ShooterPidfTunerOpMode`（搜索 Hub 内置 PIDF）已加 `@Disabled`，不会出现在 DS 列表中，仅为兼容保留。

## 开发、测试与发布

本地构建需要 JDK 17、Android SDK platform 30、build-tools 35.0.0。设置 `ANDROID_HOME`，或在仓库根目录创建 `local.properties` 写入 `sdk.dir=...`（已在 `.gitignore` 中，不要提交）。Gradle 8.13 wrapper 和 AGP 8.13.2 随仓库提供。

```bash
./gradlew :tuner-core:check :ftc-library:assembleRelease
./gradlew :tuner-core:publish :ftc-library:publish
python3 scripts/verify-publication.py build/maven 0.1.2
```

Windows 使用 `gradlew.bat` 和 `py -3`。`:tuner-core:check` 运行硬件无关的模拟测试，覆盖：已知模型的参数辨识、单/双飞轮完整状态流程、真实 feeder 脉冲与指标、空仓拒绝、积分抗饱和、电压补偿、停机和安全限值。`publish` 只写本地 `build/maven`，不会上传。

GitHub Actions：

| Workflow | 触发 | 作用 |
| --- | --- | --- |
| `Check library` | 推送 `main`、Pull Request | 模拟测试、AAR 构建、本地 Maven 布局校验 |
| `Publish Maven` | 推送 `v*` tag，或手动指定已有 tag | 构建、校验并部署到 GitHub Pages，制品历史保存在 `maven` 分支 |

发布新版本：修改 `gradle.properties` 的 `version`，提交推送，再推送同名 `v*` tag。详细步骤、Pages 设置和故障排查见 [PUBLISHING.md](PUBLISHING.md)。
