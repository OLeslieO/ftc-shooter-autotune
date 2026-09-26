# FTC Shooter AutoTune

机器人本地网页调参：连接机器人 Wi-Fi，配置电机，辨识飞轮模型，验证多个速度，调整 PID，再真实触发 preshooter 做带载优化，导出 Java 常量。

独立库的目标仓库是 **`OLeslieO/ftc-shooter-autotune`**，源代码和 Maven 发布均由该专用仓库维护。机器人代码仓库只是使用方。

安装后的入口是 **`Shooter AutoTune`**。运行时无需电脑端服务器、Node.js、云服务或互联网。网页由 Robot Controller 的 OpMode 在端口 **8081** 提供，所有资源随 APK 安装。首次 Gradle 下载依赖需要互联网。

**发布状态：目前只是本地发布配置，尚未验证远程仓库创建和首次部署。以下远程坐标需完成 [PUBLISHING.md](PUBLISHING.md) 中的首次发布后才能使用。**

## 与当前代码的关系

从 `TeamCode/.../tests/TestShooterPID.java` 复用硬件约定：

| 设备 | 默认名称 | 方向 |
| --- | --- | --- |
| Shooter 1 | `shooterUp` | REVERSE |
| Shooter 2 | `shooterDown` | FORWARD |
| Preshooter | `preShooter` | FORWARD |

默认双射手目标速度是 **1440 encoder ticks/s**，速度直接由 `DcMotorEx.getVelocity()` 读取。可在网页切换单电机、名称和方向。

原来的 `tests/TestShooterPID.java` 继续保留；它使用 REV Hub 内置速度 PIDF。本次新增的是**软件电压前馈 + PID**，两种控制器的系数单位不同。不要把原来的 `P=40, D=17, F=17.5` 填入这里的 `kP/kD/kV`，也不要将新常量传给 `setVelocityPIDFCoefficients()`。

PedroPathing 仅作为架构参考：配置 → 分阶段实验 → 实时状态 → 结果和代码导出。参考仓库为 <https://github.com/Pedro-Pathing/PedroPathing>；实现时检查了本工程中的 `ForesightTuner` 和本机缓存的 `com.pedropathing:tuning:1.0.0` 的 `Procedure`、`TuningSession`、`WebServer`。没有复制路径跟随代码，也不需要运行 Pedro 的调参页面。

射手入口和库不引用 Pedro 类，也不依赖 Pedro Gradle 模块。射手常量放在 `teamcode/shooter/Constants.java`；`teamcode/pedro/Constants.java` 仅用于底盘和定位。

## 一次完整使用流程

### 1. 安装与打开网页

1. Android Studio 同步当前 FTC 工程，编译并安装 **TeamCode / Robot Controller**。
2. Driver Station 选择 **`Shooter AutoTune`**，按 **INIT**。此时启动网页服务，不自动转动电机。
3. 电脑或手机连接机器人的 Wi-Fi。
4. 打开以下地址，使用 `http`：
   - Control Hub：`http://192.168.43.1:8081`
   - 手机 Robot Controller 的 Wi-Fi Direct：通常为 `http://192.168.49.1:8081`
   - 若机器人地址不同，使用实际 RC 地址，端口仍是 `8081`。
5. 建议使用当前版 Chrome 或 Edge，并保持调参标签页在前台。

网页只能在该 OpMode 存活时访问。DS STOP 结束 OpMode 后网页服务器也会关闭。网页 **Stop all motors** 只停止实验，保持网页在线。

### 2. Configure

1. 选择 Single / Dual，并填写 motor names 和 directions。
2. 选择 preshooter 的 Power 或 Velocity 模式，并设置对应数值。
3. 填入目标速度及安全限值，按 **Save configuration**。
4. 按 DS **START**，然后逐个选择电机，按 **Pulse selected motor**。
5. 方向测试只运行 **0.3 秒、最大 0.12 功率**。肉眼确认实际旋转方向，确认编码器在射手正转时读到正速度。必要时修改方向并重新保存。

配置会保存在 RC 的 SharedPreferences，下次打开网页会预填，但**每次 OpMode 都需要 Save configuration 才会应用**。开始应用新硬件配置时，当前会话的调参结果立即失效；若配置硬件失败，进入 FAULT，必须重新成功保存配置后才能运行。

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| Target velocity | 1440 | 编码器 ticks/s，非 RPM |
| Shooter overspeed limit | 2400 | 任一射手超过绝对速度限值即停止 |
| Preshooter overspeed limit | 2500 | 预送弹器编码器速度限值 |
| Maximum shooter power | 0.85 | 输出上限，0–1 |
| Current limit per motor | 8 A | 包括 preshooter；超限持续 150 ms 停止 |
| Minimum battery voltage | 10.5 V | 电压过低或无有效电压读数时停止 |
| Target ramp | 3000 ticks/s² | 软件目标速度斜坡，提供加速度前馈输入 |
| Preshooter demand | 0.6 | Power 模式为 0–1；Velocity 模式为 ticks/s |
| Feed pulse | 0.2 s | 每次真实送弹的持续时间 |
| Observe each shot | 2.5 s | 从送弹开始记录到窗口结束 |
| Shots per candidate | 3 | 每组参数至少 2 发，最多 8 发 |
| Session time limit | 300 s | 无负载、带载和 Test 各自有时间限制 |

最高目标速度必须低于安全速度限值，并有至少约 10% 的维持转速功率余量。默认限值是起点，必须按电机额定速度、电流、齿比和实际机构设置。

RPM 换算：`ticks/s = motorRPM × encoderTicksPerMotorRevolution / 60`。如果使用飞轮 RPM，先按传动比转换成电机轴 RPM。

### 3. PIDFTuner：空载实验

清空待发射物体，保留实际飞轮和传动机构。勾选 **Shooter is unloaded; directions checked**，按 **Start unloaded tuning**。

自动阶段：

1. **IDENTIFY**：六段有限功率阶跃，总计约 9.6 秒，采集电压、速度和加速度。归一化最小二乘拟合 `V = kS + kV·v + kA·a`。拒绝缺乏激励、非物理参数或 R² 小于 0.8 的模型。
2. **FEEDFORWARD**：只使用前馈，在目标速度的 55%、80%、100% 验证。任一电机稳态误差超过 15% 则停止，提示检查模型或硬件。
3. **KP**：由辨识模型的时间常数 `τ = kA/kV` 计算反馈增益尺度，测试四个有界候选，包含纯前馈基线。每组用 65%、100%、80% 目标速度做上升和下降实验。
4. **KD**：仅当最佳响应超调超过 8% 时测试少量 D 候选。
5. **KI**：仅当仍有超过 2% 的稳态误差时测试少量 I 候选。
6. **AWAIT_LOAD**：停止全部电机，等待装载和明确启用带载实验。

候选评分包含按速度归一化的 RMSE、超调、稳态误差和恢复时间。每组实验从停转开始，避免前一组残余转速影响比较。这是基于模型的有限候选优化，不是遍历整个 PIDF 参数空间。

### 4. 带载优化：真实触发 preshooter

在 **AWAIT_LOAD** 装好物体并安排安全接收区域。确认 feeder 的一次脉冲能输送一发。勾选 **Game pieces loaded; firing area clear**，按 **Arm and run loaded tests**。

每组参数先让所有射手电机在目标速度 ±5% 范围稳定至少 0.35 秒，再真实驱动 preshooter。程序比较当前增益及 `kP × 1.2`、`kP × 0.8` 的有限邻域；若空载最佳 `kP=0`，则按模型生成两个非零反馈候选。选择表现最好的参数后，再使用新的一组射击进行 **VERIFY**。

默认需要 **3 组 × 3 发 + 3 发验证 = 12 发**。持续供弹能力不足时，应先调整机构、装载方案或实验配置；不要在飞轮运转时接触机构。

每发、每个射手电机分别记录：

| 指标 | 定义 |
| --- | --- |
| Velocity drop | 送弹前转速减观察窗口内最低转速 |
| Maximum error | 窗口内相对目标速度的最大绝对误差 |
| Recovery time | 自开始送弹到最后一次离开 ±5% 速度带的时间；之后须至少连续 0.3 秒保持在带内 |
| Overshoot | 观察窗口内高于目标速度的最大值 |
| RMSE | 按真实采样间隔加权的速度误差均方根 |

未恢复会明确显示 **Not recovered**，不会被当成正常恢复。优化以每发最差电机的分数为准，再对多发平均。无法恢复、超调超过 15% 或最终误差超过 5% 的候选不能成为有效结果。

如果所有射手电机都没有至少 `max(10 ticks/s, 目标的 1%)` 的转速下降，实验以 **No shot load detected** 结束，阻止空仓调参通过。这是通过速度扰动推断负载，**不是弹丸传感器**；一次脉冲多发、打滑或卡弹仍需要人工检查。

### 5. 导出并粘贴 Constants

仅在带载验证通过后，网页才启用 **Export Java constants**，下载 `ShooterConstants.txt`。**Download metrics JSON** 可保存候选分数、逐发逐电机指标和当前配置。

把六条赋值语句替换到当前工程：

```text
TeamCode/src/main/java/org/firstinspires/ftc/teamcode/shooter/Constants.java
```

导出的字段为：

```java
public static double SHOOTER_KS = ...;
public static double SHOOTER_KV = ...;
public static double SHOOTER_KA = ...;
public static double SHOOTER_KP = ...;
public static double SHOOTER_KI = ...;
public static double SHOOTER_KD = ...;
```

网页不能修改开发电脑上的 Java 源码。需要手动替换字段，重新编译安装。初始字段全为零，表示尚未调参，不会伪造可用常量。

最新成功结果的文本保存在 RC 上，网页的 **Previous session constants** 可再次查看；它只是历史参考，不会在下次会话自动当成已验证结果。改动配置后必须重新验证。

### 6. Test

- 同一会话调参成功后，可直接按 **Start Test**，使用刚刚验证的参数。
- 粘贴常量并重新安装后，重新 INIT → Save configuration → DS START → **Start Test**，使用编译进 `Constants.java` 的六个字段。
- **`gamepad1.right_bumper`** 手动启动 preshooter；首次开始送弹需所有射手在目标速度 ±10% 内。启动后保持送弹直到松开，避免射后掉速造成反复启停。安全限值或 Stop 始终优先停止输出。
- 网页持续显示目标/实际速度、每台电机功率、电流、供电电压、阶段和逐发数据。网页 Stop 或 DS Stop 均停止输出。

## 控制器与单位

```text
commandVolts = kS·sign(targetVelocity)
             + kV·targetVelocity
             + kA·targetAcceleration
             + kP·(targetVelocity − measuredVelocity)
             + kI·integral(error)
             − kD·filteredMeasuredAcceleration

motorPower = clamp(commandVolts / measuredBatteryVoltage, 0, maxPower)
```

| 常量 | 单位 |
| --- | --- |
| kS | V |
| kV、kP | V / (ticks/s) |
| kA、kD | V / (ticks/s²) |
| kI | V / tick |

D 对测量求导，避免目标阶跃引起 derivative kick；恒定目标时等价于误差的导数。I 采用条件积分和贡献限幅，避免饱和累积。目标为零时清空控制器状态。每台射手有独立控制器、积分和反馈，当前版本使用**一套共同的六个常量**，适用于相同电机和相近传动。明显不对称的双飞轮可能无法通过验证。

射手设置为 `RUN_WITHOUT_ENCODER` 并使用 `setPower()`，但仍读取编码器速度。Preshooter 可选 SDK `RUN_USING_ENCODER` + `setVelocity()`；本系统不调 preshooter 的 Hub PIDF。

## 文件与架构

```text
TeamCode/.../shooter/Tuning.java       注册 OpMode，提供当前工程的常量
TeamCode/.../shooter/Constants.java   六个 SHOOTER_K* 字段，与 Pedro 无关

tuner-core/.../autotune/
  AutoTuneManager.java                非阻塞状态机；实验、保护、结果管理
  ShooterConfig.java                  硬件名称、方向、速度和安全配置
  ShooterHardware.java                硬件无关接口
  FeedforwardTuner.java               kS/kV/kA 系统辨识
  PIDTuner.java                       基于模型的增益候选
  VelocityController.java             电压补偿、抗饱和和测量微分
  LoadedShotTest.java                  实际送弹后的逐电机记录
  PerformanceMetrics.java             误差、恢复时间和评分
  Gains.java                          常量和 Java 导出

ftc-integration/.../ftc/
  ShooterAutoTuneOpMode.java           Configure() / PIDFTuner() / Test()，OpMode 与网页命令入口
  FtcShooterHardware.java              DcMotorEx / 电压 / 电流适配
  AutoTuneWebServer.java               NanoHTTPD、命令队列、心跳和导出
  AutoTuneJson.java                    配置和 telemetry JSON

ftc-library/src/main/assets/shooter-autotune/
  index.html / app.js / style.css      无外部依赖网页
```

网页线程只接收命令、读取不可变 JSON 快照，不访问硬件。OpMode 线程执行全部电机读写，约每 20 ms 更新控制器，每 100 ms 发布 telemetry；网页约每 150 ms 拉取一次。DS 的实际调度与硬件读取会影响周期，所以控制器使用测得的时间间隔。

旧版 `ShooterPidfTunerOpMode` 已加 `@Disabled`，避免把内置 Hub PIDF 搜索误认为新系统。旧核心类保留供已有调用方使用。

## 安装到其他 FTC 工程

### 远程 Maven 安装（首次发布后）

专用 Maven 地址为 `https://oleslieo.github.io/ftc-shooter-autotune/maven/`，不使用 `FTC16093-BioBuzz` 的 Maven 地址，也不是 Maven Central 或需要 token 的 GitHub Packages。

1. 在 FTC 工程根 `build.gradle` 的 **`allprojects.repositories`** 中加入：

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

保留工程原有仓库。若工程统一使用 `settings.gradle` 的 `dependencyResolutionManagement.repositories`，将这个 `maven` 块放到那里；不要放到 `pluginManagement` 或 `buildscript.repositories`。

2. 在 `TeamCode/build.gradle` 的 `dependencies` 中加入：

```gradle
implementation 'io.github.oleslieo:shooter-autotune:0.1.0'
```

该 AAR 自动传递依赖 `io.github.oleslieo:shooter-autotune-core:0.1.0`，并携带网页资源。FTC SDK 由目标机器人项目提供；不引入 Pedro。

3. 从本仓库的 `examples/teamcode/shooter/` 复制 **`Tuning.java` 和 `Constants.java`** 到目标工程的：

```text
TeamCode/src/main/java/org/firstinspires/ftc/teamcode/shooter/
```

`Tuning.java` 只注册 Driver Station 入口并将本地六个常量交给库：

```java
@TeleOp(name = "Shooter AutoTune", group = "Tuning")
public final class Tuning extends ShooterAutoTuneOpMode {
    @Override
    protected Gains testGains() {
        return new Gains(Constants.SHOOTER_KS, Constants.SHOOTER_KV, Constants.SHOOTER_KA,
                Constants.SHOOTER_KP, Constants.SHOOTER_KI, Constants.SHOOTER_KD);
    }
}
```

完整 package/import 已包含在示例文件中。如果使用其他包名，两个文件同步修改；如果已有常量类，调整 `testGains()` 的引用即可。无需复制控制循环、FTC 硬件层或网页。

4. Gradle Sync → 编译安装 → DS INIT → 连接机器人 Wi-Fi → 打开网页。按前文依次执行 **Configure → PIDFTuner 空载 → 带载验证 → 导出 Constants → Test**。电机名、方向和速度在 Configure 中按实际机器人修改。

5. 从本地模块迁移时，先确认远程 POM 可下载，再删除以下本地依赖和 `settings.gradle` 的两个 include，防止重复类：

```gradle
implementation project(':external:ftc-shooter-pidf-tuner:ftc-library')
include ':external:ftc-shooter-pidf-tuner:tuner-core'
include ':external:ftc-shooter-pidf-tuner:ftc-library'
```

它们分别位于 `TeamCode/build.gradle` 和根 `settings.gradle`，不是放在同一个块里。发布确认前，当前机器人项目仍使用已接入的本地模块。

### 本地源码安装（开发或尚未发布时）

1. 复制本目录到目标工程 `external/ftc-shooter-pidf-tuner`。
2. 在目标根 `settings.gradle` 加入：

```gradle
include ':external:ftc-shooter-pidf-tuner:tuner-core'
include ':external:ftc-shooter-pidf-tuner:ftc-library'
```

3. 在 `TeamCode/build.gradle` 的现有 `dependencies` 块中加入：

```gradle
implementation project(':external:ftc-shooter-pidf-tuner:ftc-library')
```

4. 复制 `examples/teamcode/shooter/Tuning.java` 和 `Constants.java` 到目标 TeamCode，保持二者在同一个 shooter 包；无需复制任何 Pedro 文件或添加 Pedro 依赖。
5. 同步、编译并安装。无需手动追加 Java sourceSets，网页 assets 随 Android library 合并到 APK。

库按 FTC SDK **12.0.0**、Java 8 字节码、Android minSdk 24 / compileSdk 30 配置。独立构建使用 JDK 17、附带的 Gradle 8.13 wrapper 和 AGP 8.13.2。目标工程使用其他 SDK 时需核对 `ftc-library/build.gradle` 的 `compileOnly` 版本和 API 支持。

## 安全行为与故障排查

- **Browser disconnected**：1.5 秒收不到心跳即停止。不要切换到其他标签页或让手机锁屏；页面隐藏还会主动请求 Stop。
- **Loop missed deadline**：两次控制更新间隔超过 200 ms 时停止。这是软件检测，不是独立硬件 watchdog；DS STOP 和 Hub 自身保护仍需保留。
- **Overspeed/current/battery/stall**：检查安全限值、编码器和机构。射手功率大于 0.2 且速度低于 30 ticks/s 持续 0.8 秒视为堵转或编码器故障。
- **Negative encoder**：检查方向和接线，不能简单取绝对值掩盖问题。
- **Insufficient excitation / poor fit**：检查功率上限、负载摩擦、编码器噪声和电机是否一致。模型验证不通过时不进入带载射击。
- **No power headroom**：降低目标速度，或在硬件允许范围内调整最大功率。
- **No shot load detected**：检查是否空仓，以及一个 feed pulse 是否真的把一发送进飞轮。排障后先 Stop，再重新执行完整流程。
- **网页打不开**：确认 DS 已 INIT 此 OpMode、Wi-Fi 和实际 RC IP 正确，URL 是 `http://...:8081`，没有端口冲突。DS STOP 后页面不可访问是正常行为。
- **网页接受命令但未动作**：看 Command 状态；电机操作需要 DS START、已保存配置及有效网页心跳。
- **结果差异**：本版本辨识空载机构，再通过实际射击优化恢复；它没有弹丸速度传感器，也没有标定投射距离或命中率。

## 验证

硬件无关模拟测试包括：已知模型的参数辨识、单/双飞轮完整状态流程、实际 feeder 脉冲与指标、空仓拒绝、积分抗饱和、电压补偿、停机和安全限值。

在独立库根目录运行：

```bash
./gradlew :tuner-core:check :ftc-library:assembleRelease
./gradlew :tuner-core:publish :ftc-library:publish
python3 scripts/verify-publication.py build/maven 0.1.0
```

`publish` 只写本地 `build/maven`，不会直接上传到服务器。仓库 CI 检查模拟测试、AAR 构建、sources、网页 assets、POM 传递依赖和 Gradle module 坐标。推送版本 tag 后专用仓库的发布 workflow 才部署 GitHub Pages，并保留旧版本。

在集成本地模块的机器人工程根目录运行：

```bash
./gradlew :external:ftc-shooter-pidf-tuner:tuner-core:simulationTest
./gradlew :TeamCode:assembleDebug
```

Windows 使用 `gradlew.bat`。没有可用 Gradle 时，可用 JDK 直接编译 `tuner-core/src/main/java` 和 `tuner-core/src/test/java`，运行 `org.ftc.shooter.tuner.autotune.AutoTuneSimulationTest`。

本次已运行纯 Java 模拟测试，并使用本机 FTC SDK 12 和 Android SDK 对新 FTC 层及 TeamCode 入口做 Java 编译检查。当前执行环境禁止 Gradle 所需的本地套接字，完整 APK 构建未完成；还需要在 Android Studio 中构建并在实际机器人上验证。
