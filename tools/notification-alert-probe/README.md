# 手表通知声音与振动测试

一个独立的手机测试 APK 加 PowerShell 脚本，用四个 Android 通知频道构造不同的提醒组合。通知仍由 TicBox 采集、编码并通过蓝牙同步，再由手表系统的通知模块处理；TicBox 和手表上的组件都不用更换。

| 通知标题 | case | 声音请求 | 振动请求 |
| --- | --- | --- | --- |
| A - Silent | SILENT | 否 | 否 |
| B - Sound request only | SOUND_ONLY | 是 | 否 |
| C - Vibration request only | VIBRATE_ONLY | 否 | 是 |
| D - Sound and vibration requests | BOTH | 是 | 是 |

所有频道的重要性都是 DEFAULT。不设置自定义振动节奏，避免把“是否振动”和“用哪种节奏”混在一起。开启声音时使用系统默认通知声音 URI，只是让手机端的映射生成声音请求，不传输音频，也不指定手表铃声。

## 执行

把 `Test-NotificationAlerts.ps1`、`ticbox-alert-probe.apk` 和 `probe-manifest.json`（APK 的 SHA-256）放在同一目录。手机已安装 TicBox 并连上手表：

```powershell
.\Test-NotificationAlerts.ps1 -PhoneSerial <手机序列号>
```

脚本安装测试器并授予发送通知的权限。然后在 TicBox 的通知应用列表中勾选 **TicBox Alert Probe**；看不到时，打开列表的“无网络权限应用”筛选并刷新。

**手表断开 USB、离开充电座并佩戴后再测。** 部分手表在接 USB 或充电时不振动也不发声，会干扰对比。脚本默认只连接手机 ADB，通知经已有的蓝牙链路送到手表。

关闭手机勿扰；按要测试的条件设置手表的勿扰、静音和音量，脚本不修改这些设置。手机自己也可能响铃或振动，把手机放远一些，只记录手表的表现。脚本会检查手机勿扰状态并记录铃声模式，但不把手机是否发声当作手表的结果。

每轮按 Enter 发出通知，默认等 8 秒，然后依次填写“手表卡片可见、手表振动、手表响铃”：

```text
y,y,n
```

`y` 是，`n` 否，`u` 不确定；输入 `q` 提前结束。每轮只清除测试器自己的通知和频道，结束后保存 JSON 记录并清理测试通知。

单独重测一项，或等更久：

```powershell
.\Test-NotificationAlerts.ps1 -PhoneSerial <手机序列号> -Cases SOUND_ONLY -WaitSeconds 15
```

`-WatchSerial` 是可选诊断，用手表 ADB 读取 Wear OS 的桥接计数，不参与发送。一般不需要；确实要用时走手表的无线 ADB，手表仍要离开充电座。

## 记录与判断

JSON 记录包含手机频道实际的声音 / 振动状态、手机勿扰和铃声模式、TicBox 的会话和通知计数、可选的手表桥接计数，以及你输入的观察结果。不采集通知正文或设备地址。“通知已发出”不等于“手表已提醒”；没看到测试卡片时，这一轮的声音和振动结果都不能用来判断。

B 只响、C 只振、D 两者都有，才说明当前设置下两者可以独立触发。B 也振动，说明这条链路上的声音请求会带出振动。四项都没有声音时，只能说明当前固件、音量和模式下没有声音，不能推出协议不支持声音。手表的频道设置、脱腕、静音、勿扰和系统通知冷却都会影响结果，建议在相同设置下重复几次。

手机端的 `AndroidNotificationMapper` 根据频道和 ranking 生成 `defaults`、`has_sound`、`vibrate`。这个工具测试的是经过映射的完整链路，不是直接向手表注入 DataMap 的单元测试；系统仍可能通过 ranking 或手表策略抑制提醒。

脚本结束后测试器仍留在手机上。在通知列表取消勾选后卸载：

```powershell
adb.exe -s <手机序列号> uninstall dev.ticbox.alertprobe
```

终端被关掉而没有清理时：

```powershell
.\Test-NotificationAlerts.ps1 -PhoneSerial <手机序列号> -CleanupOnly
```

测试器只有 POST_NOTIFICATIONS 权限，没有网络、蓝牙、通知读取、root 或健康数据权限，也没有可启动的 Activity。广播接收器要求发送方持有 DUMP 权限，供 `adb shell` 显式调用。脚本不会解绑、清日志、重启或修改系统提醒设置。

## 构建

这是独立的 Gradle 项目，不是手机主工程的依赖。复用仓库的 Gradle wrapper 和 Android SDK：

```bash
./android/gradlew -p tools/notification-alert-probe testReleaseUnitTest lintRelease assembleRelease --console=plain
```

产物是 `build/outputs/apk/release/notification-alert-probe-release.apk`，开启 R8 和资源压缩，用本机的 Android 调试证书签名；包名不同，不会替换 TicBox。Robolectric 测试覆盖四种频道组合、旧频道设置不影响实验、无效输入不发通知，以及清理不影响其他通知。

PowerShell 脚本有一个只用假 adb 的回归测试，不连接设备：

```bash
node tools/notification-alert-probe/verify-script.mjs /absolute/path/to/pwsh
```
