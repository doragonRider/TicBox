# 通知的声音与振动

本文说明手机桥接到手表的通知如何决定声音和振动：官方手表 SystemUI 的实际行为、本应用在手机端的提醒策略，以及可选的 SystemUI 补丁。通知本身的编码见[伴侣业务消息](companion.md)。

## 1. 官方行为

手机通知经 DataApi 到达手表后，每条通知的根 DataMap 带有提醒请求：`has_sound`、`defaults` 和 `vibrate`（振动波形）。手表的 WearServices 把它们分别转换为 `hasSound` 和 `shouldVibrate`；声音位、振动位或振动波形中任何一项存在，都会把通知标为 interruptive 并带上振动模式。

在一台 TicWatch 上，用四个手机通知频道（无声无振动、仅声音、仅振动、声音加振动）测试，四种组合在手表上**都显示卡片、振动并响铃**。手表 SystemUI（`com.mobvoi.wear.refsysui` 1.5.0 / 150）的提醒链可以解释这一结果：

1. 可听性过滤器（`AudibilityAlertingFilter`）总是放行，不会因为通知本身没有声音或振动而静音。
2. 过滤器放行后就创建提醒对象，即使提取到的振动模式为空也继续。
3. 振动模式为空或等于默认标记时，振动加载器按手表的 `notification_vibration_intensity` 生成一个非空的默认波形，而不是不振动。
4. 提醒方法同时调用振动器和铃声播放器；铃声路径只检查提醒间隔、模式是否自带声音等条件，不读取通知的 `hasSound`，没有自定义铃声时播放系统铃声。

因此，在手表的过滤条件允许、声音和振动设置开启时，无声的通知也会振动并响铃。手机端的 `defaults` / `has_sound` / `vibrate` 在这版固件上不能当作独立生效的开关。这是对这一版 SystemUI 的分析，不代表其他 Wear OS 固件。

## 2. 手机端的提醒策略

本应用在手机上提供全局的声音、振动开关，以及每个应用各自的两个三态设置，用来计算通知的提醒请求。API 在 [NotificationSync.kt](../../android/core/api/src/main/kotlin/dev/ticbox/companion/api/NotificationSync.kt)。

| 状态 | 默认值 | 作用 |
|---|---|---|
| `soundEnabled` | `true` | 允许声音请求；关闭时所有应用都请求静音 |
| `vibrationEnabled` | `true` | 允许振动请求；关闭时所有应用都不请求振动 |
| `appAlerts` | 空 | 包名到 `NotificationAppAlertSettings` 的稀疏覆盖；`alertsFor(packageName)` 未保存时返回两个 `FOLLOW_APP` |

| `NotificationAlertMode` | 声音 | 振动 | 发送行为 |
|---|---|---|---|
| `FOLLOW_APP` | 由 App 决定 | 由 App 决定 | 保留手机通知频道、ranking 和通知原本的请求 |
| `NEVER` | 总是静音 | 禁止振动 | 清除对应的请求 |
| `ALWAYS` | 总是响铃 | 总是振动 | 请求手表的默认提醒，即使原通知没有请求 |

计算顺序：先检查总同步开关和应用名单，再按全局开关、应用的模式和原始请求计算。全局关闭优先于应用的 `ALWAYS`；全局开启只是允许，不强制所有应用提醒。声音和振动分别计算，可以任意组合；两者都关闭也不删除通知卡片。应用的同步选择和提醒模式相互独立。

策略只修改通知根字段 `has_sound`、`defaults` 和 `vibrate`，不改标题、图片、分组、动作或回复。`ALWAYS` 振动保留有效的自定义波形；没有波形、全零或只有等待段时使用默认振动。`FOLLOW_APP` 保留显式的全零波形。`ALWAYS` 声音使用默认声音，不传送手机的音频文件。

设置变化时，源版本未变的已发布卡片会静默重写，沿用原来的 revision，之后的无变化重扫不会再次提醒；与设置变化同时到来的新通知或内容更新按新策略提醒。重连重建总是静默。已经在手表上播放或排队的提醒无法撤回。

设置保存在 `noBackupFilesDir/notification-sync.settings`（schema 2）：包名 1 到 255 个字符，最多 4096 个覆盖，整个文件最大 1 MiB。旧文件缺少提醒字段时，两个全局开关默认开启，每个应用默认“由 App 决定”。

由于第 1 节的官方行为，这些设置只有在手表上应用了第 3 节的补丁后才有物理效果；没有补丁时界面不显示它们，但保留已保存的选择。

## 3. 本应用的 SystemUI 补丁

[进程内模块](monitoring-settings.md#52-进程内模块)包含一个可选的 SystemUI 扩展，按每条手机通知的 `hasSound` 和 `shouldVibrate` 分别限制声音和振动。它复用官方的卡片、过滤和提醒入口，不新增界面，也不替换官方 APK。实现见 [NotificationAlertsModule.java](../../android/watch/healthmodule/src/main/java/dev/ticbox/companion/healthmodule/NotificationAlertsModule.java) 和 [OemAlertAdapter.java](../../android/watch/healthmodule/src/main/java/dev/ticbox/companion/healthmodule/OemAlertAdapter.java)。

### 3.1 准入

| 属性 | 要求 |
|---|---|
| 包名和主进程 | `com.mobvoi.wear.refsysui` |
| 版本 | `1.5.0` / `150`，无 split，完整 APK 的 SHA-256 与代码中的白名单一致 |
| Application | `com.google.android.clockwork.sysui.application.SysUiApplication` |
| 环境 | Android 13/14、主用户、普通应用 UID、ARM32 / ARM64、Magisk 26+ 和 Zygisk |
| 私有目录 | `/data/user/0/`、`/data/data/` 或设备保护存储的 `/data/user_de/0/` |

跳过 DenyList、子进程、隔离 UID、系统 UID、次用户和 system_server。其他包名或版本即使方法名相似也不套用；不放宽 SELinux，不绕过系统签名。

### 3.2 执行方式

扩展在官方主线程取得 SystemUI 依赖注入容器中的振动执行器和铃声执行器，分别包装它们已有的“提醒模式加载”接口，不替换 ART 方法，也不附带 LSPlant 或 LSPosed。两处都仍经过官方的过滤（勿扰、离腕等）、only-alert-once 和节流；包装器不主动发通知、点亮屏幕或调用播放设备。静默通知的卡片仍然显示。

| 声音请求 | 振动请求 | 允许的效果（仍受手表设置限制） |
|---|---|---|
| 否 | 否 | 不响铃、不振动 |
| 是 | 否 | 只走官方的独立铃声路径 |
| 否 | 是 | 只播放纯振动波形 |
| 是 | 是 | 沿用官方的组合模式 |

- 声音只在手表 `AudioManager.RINGER_MODE_NORMAL` 时允许；振动仍受手表的 `vibrate_on` 和 `notification_vibration_intensity` 限制。两者在这版固件中相互独立。
- 官方的模式可能把声音嵌入触感资源，所以“仅振动”不能只关掉铃声播放器：扩展用官方的接口构造纯波形，交给原来的振动器执行。手机的自定义波形必须在 128 项、总长 30 秒以内；没有有效的自定义波形时，使用与官方强度对应的默认波形。有效的全零波形保留。
- “仅声音”时停用触感资源，使用官方的独立铃声，音色可能与组合模式不同。
- 充电参数只表示充电状态，不当作“请求声音”；充电时不强制生成纯振动波形。
- 只作用于来自 Android 手机的通知，不影响本地闹钟、电话、MCU 和 ANCS 的通知。

两个包装器都准备好、字段写入并读回成功后才启用策略；只装上一部分时仍委托官方实现。运行时原型不符或读取异常时停用策略，报告 `notification_alert_contract_unavailable`，之后委托官方。官方自身抛出的异常只传播一次，不重试。

### 3.3 启用与模式

ZIP **默认不含启用标记**，首次安装不会注入 SystemUI；更新时只保留此前满足检查的显式标记。启用方式是在手表上以 root 创建标记，然后重启：

```sh
touch /data/adb/modules/ticwatch_health_settings/notification-alerts.enabled
chmod 600 /data/adb/modules/ticwatch_health_settings/notification-alerts.enabled
```

标记必须是 root 所有的普通文件，最多 16 字节，组和其他用户不可写，不接受符号链接。刚安装的新 ZIP 还在 Magisk 的暂存目录时，标记要写进 `/data/adb/modules_update/ticwatch_health_settings/`，因为开机时 Magisk 会用暂存目录整个替换活动目录。

控制命令可以在 Local Bridge 的 root 命令行运行（root Local Bridge 继承应用的挂载命名空间，客户端从 `/data/system/packages.list` 取得 SystemUI 的 UID）：

```sh
CLASSPATH=/data/adb/modules/ticwatch_health_settings/module.dex app_process / dev.ticbox.companion.healthmodule.NotificationAlertsControl status
CLASSPATH=/data/adb/modules/ticwatch_health_settings/module.dex app_process / dev.ticbox.companion.healthmodule.NotificationAlertsControl PROBE
```

| 模式 / 命令 | 作用 |
|---|---|
| `status` | 返回模式、身份、安装状态、测试应用的加载次数和错误码 |
| `OFF` | 立即委托官方实现，不读取通知模型，不记录日志 |
| `OBSERVE` | 委托官方实现，只为测试应用 `dev.ticbox.alertprobe` 记录提醒相关的布尔值 |
| `PROBE` | 只对 `dev.ticbox.alertprobe` 生效，其他应用保持原样 |
| `ALL` | 对所有手机通知生效 |

首次加载的模式为 `OBSERVE`。模式按 APK 摘要隔离保存，跨 SystemUI 重启保留，只影响之后的加载调用。`installed=true` 只说明接口写入并读回成功，不说明提醒已经经过包装器。失败时 stderr 输出 `notification_alert_control_unavailable stage=<request|uid|connect|identity>`，错误回复带 `error`，退出码为 1。

控制接口是抽象 Unix socket `ticbox-notification-alerts-v1`，只接受内核确认 UID 为 0 的客户端；客户端反查对端为 SystemUI 主用户的 UID，并校验回复中的身份。每个连接一个请求，帧为 4 字节大端长度 + UTF-8 JSON，最大 64 KiB，读取超时 15 秒。`moduleVersionCode=1` 是控制协议的实现号。

```json
{"version":1,"op":"status"}
{"version":1,"op":"mode","mode":"PROBE"}
```

观察日志的标签为 `TicNotifyModule`，只针对测试应用，记录 `role`、`sound`、`vibrate`、`patternPresent`、`plugged`、`patched`；前 32 次都记录，之后每 100 次记录一次。计数是加载器的调用次数，不是通知数或实际提醒次数。不记录标题、正文、通知标识或账号。

手机上的手表模块页通过 [NotificationAlertPatch.kt](../../android/core/api/src/main/kotlin/dev/ticbox/companion/api/NotificationAlertPatch.kt) 执行这些固定命令，只提供 `OFF` 和 `ALL`：开启时创建标记，扩展加载后自动切到 `ALL`（只做一次）；关闭时分别尽力执行 `OFF` 和删除标记。标记只在 SystemUI 进程启动时读取，所以开关时只重启 SystemUI，不重启手表。命令行切到 `OBSERVE` 或 `PROBE` 后页面不会覆盖。

只停用这一补丁：执行 `OFF`，删除标记后重启。完全停用：在 Magisk 中禁用或移除整个模块并重启。删除标记不会清除已保存的模式。

### 3.4 验收方法

手表拔掉 USB 并离开充电座，使用[通知提醒测试器](../../tools/notification-alert-probe/README.md)，保持相同的佩戴、勿扰、铃声和振动设置，依次在 `OBSERVE` 和 `PROBE` 下运行四种频道组合；另外单独验证静音、勿扰、本地闹钟和重连。日志可以在 root 命令行用 `logcat -d -s TicNotifyModule:I '*:S'` 读取。如果观察到的布尔值本身不区分四种组合，先检查手机的编码，不能因为模块已安装就认为问题已解决。

## 4. 待验证

- 补丁在真实手表上的加载、SELinux 下的 socket 权限、反射修改后 ART 的实际行为。
- 四种组合在补丁下的实际声音和振动效果，以及模式跨 SystemUI 重启的保留。
- 选择“总是”时，手表的勿扰、离腕、音量、铃声模式、振动强度、节流和 only-alert-once 仍可能抑制提醒，界面不能说“手表一定会响铃或振动”。
