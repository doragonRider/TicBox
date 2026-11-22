# 监测与提醒设置

本文规定手表官方健康应用 TicHealth 的健康监测和提醒设置：存放位置、键名、类型、取值、默认值和修改后的副作用，以及本应用读写这些设置的方式。机器可读的完整目录见 [monitoring-settings-catalog.json](monitoring-settings-catalog.json)。

适用版本：TicHealth 5.5.0-3292.2931（versionCode 50503292）和 5.6.0-3234.2686（versionCode 50603234）。其他版本只读。

设置分三类存放：

| 存放位置 | 内容 | 外部访问方式 |
|---|---|---|
| `HealthSettingsProvider` 的 `app_ctrl` 表 | 监测开关、采样模式、睡眠血氧间隔、温度单位、计步 | `ContentResolver.call`，需要厂商权限 |
| 健康数据库的 POINT | 运动心率上限（类型 16），期望睡眠和起床时间（33、34） | `ContentResolver.insert`，需要厂商权限 |
| 进程内的 SharedPreferences `healthy`、`sleep_settings` | 久坐和目标提醒、健康提醒时段、睡眠提醒和闹钟 | 没有跨进程入口，只能在 TicHealth 进程内调用 |

## 1. HealthSettingsProvider

两个版本都导出 `com.mobvoi.ticwear.health.provider.HealthSettingsProvider`，authority 为 `com.mobvoi.health.provider.settings`，受 `com.mobvoi.ticwear.permission.HEALTH`（signatureOrSystem）保护。普通签名的应用无法读写；root 调用也可能被某些固件拒绝，此时必须明确报告权限被拒绝。

调用方式：`ContentResolver.call(content://com.mobvoi.health.provider.settings/<table>/<name>, method, null, extras)`。

| 项目 | 约定 |
|---|---|
| method | `get`、`put`、`remove` |
| extras | `table:String`、`name:String`；put 另加 `value:String`；table 为空时使用 `default` |
| get 的返回 | `value:String?`；没有默认值参数，默认值由官方的业务代码决定 |
| put 的返回 | `value:String?` 为**写入前的旧值**，`status:Boolean` 为写入结果 |
| remove 的返回 | 同样返回旧值和 status；本应用不使用 |
| 存储 | `getSharedPreferences("settings_" + table)`，值都是字符串：布尔为 `"true"` / `"false"`，数值为十进制 |

Provider 自身不调用 `notifyChange`。官方客户端在 `status=true` 且旧值不同时，对精确的 URI 调用 `notifyChange(uri, null)`。调用方应在写入后重新 get 确认新值，再发出通知；不能把 put 返回的旧值当成新值。`table=healthy` 会创建 `settings_healthy`，与私有的 `healthy` 偏好文件无关。

### 1.1 app_ctrl 中的设置

官方界面的偏好键名常常不同于 Provider 的键名，不能直接用界面的键名调用 Provider。

| 设置 | 键名 | 类型与取值 | 默认值与说明 |
|---|---|---|---|
| 后台心率 | `background_hrm` | boolean | 取决于机型；不能当作 false |
| 后台心率模式 | `background_hrm_mode` | int，0 普通、1 实时 | 0；MCU 的采样周期分别为 600 秒和 1 秒，不能设置任意周期 |
| 自动血氧 | `auto_detect_blood_oxygen` | boolean | 取决于机型；白天的采样周期固定 3600 秒 |
| 压力 | `auto_detect_pressure` | boolean | false；开启时 MCU 周期 1800 秒 |
| 身心状态 | `auto_detect_body_mind_state` | boolean | false；完整的采集调度未确认 |
| 心脏健康 | `auto_detect_heart_health` | boolean | false；实际的监测开关 |
| 自动睡眠 | `auto_detect_sleep` | boolean | true；另受 `flags_feature/auto_motion` 控制 |
| 自动运动识别 | `auto_detect_fitness` | boolean | 取决于机型；同样受 `flags_feature/auto_motion` 控制 |
| 睡眠呼吸率 | `detect_respiration_rate` | boolean | true；开启时周期 1200 秒 |
| 睡眠温度 | `detect_temperature` | boolean | true；开启时周期 600 秒 |
| 静息心率告警 | `rest_hrm_warning` | boolean | false |
| 静息心率下限 | `rest_hrm_warning_lower` | int，40..60，步长 5 | 5.5 默认 60；**5.6 已移除这两个阈值的界面入口**，但 MCU 仍读取 |
| 静息心率上限 | `rest_hrm_warning_upper` | int，100..150，步长 10 | 5.5 默认 100；同上 |
| 运动心率告警 | `fitness_hrm_warning` | boolean | false；上限的数值不在这里，见第 3 节 |
| 睡眠血氧间隔 | `sleep_detect_blood_oxygen_frequency` | long，**3600 / 1800 / 60 秒** | 3600；60 秒对应界面上的“实时” |
| 睡眠温度单位 | `detect_sleep_temperature_unit` | 字符串，`"0"` 摄氏、`"1"` 华氏 | 5.6 默认 `"0"`；5.5 跟随伴侣 App 的温度设置 |
| 计步 | `step_pedometer` | boolean | true；修改方式见第 1.2 节 |

`auto_motion` 是派生值，不应由界面直接写入：5.6 计算 `auto_detect_fitness || auto_detect_sleep || rest_hrm_warning`（前两项还受 `flags_feature/auto_motion` 控制），与旧值不同时写入。5.5 的界面在开启静息告警时，还会强制设置 `background_hrm=true` 和 `background_hrm_mode=1`。逐键写入可能只有一部分成功，不能伪装成原子提交，也不能粗暴回滚其他进程刚改过的设置。

### 1.2 计步开关

计步应通过 StepProvider 修改，而不是直接 put：对 `content://com.mobvoi.ticwear.steps` 调用 `ContentResolver.update(uri, ContentValues("enable" → Boolean), null, null)`。官方会在进程内更新 `app_ctrl/step_pedometer`，清理 `healthy` 偏好中的 `last_step`、`last_step_time`、`last_motion_type`、`last_motion_time`、`last_freq_distance` 等缓存（不删除健康历史），再以空的 ContentValues 触发通知。该方法**总是返回 0**，不能据此判断失败，必须回读开关确认。

### 1.3 设置与采集的关系

手表的 MCU 服务（`com.mobvoi.wear.mcuservice.aw`）为后台心率及其模式、自动血氧、睡眠血氧间隔、自动运动、计步、心脏健康、压力、呼吸率、温度和静息告警的三个键注册 ContentObserver，变化后设置 MCU 的采样参数。因此修改后必须对精确 URI 发通知。官方界面另外发送的 `heart_rate_background_tracking_changed` 是 LocalBroadcastManager 的进程内广播，不能用跨进程的普通广播冒充。

后台心率只在数据授权 `data_hrm` 允许且开关为 true 时才设置 1 秒或 600 秒，否则为 0；血氧同样需要 `data_blood_oxygen` 授权。另有 data_pressure、data_respiration_rate、data_sleep 等数据授权。修改监测设置不应改动这些授权或 Android 的 BODY_SENSORS、ACTIVITY_RECOGNITION 权限。**设置已保存不代表传感器已经开始采样。**

### 1.4 心脏健康消息

伴侣路由上的 `/health/cmd_change_heart_health_switch` 修改的是心脏健康入口是否显示（`feature_heart_health_should_show`）：值为 `"1"` 时显示入口，但不自动开启监测；其他值隐藏入口，并关闭 `auto_detect_heart_health`。要开启监测，应写入 `auto_detect_heart_health`。

## 2. 私有偏好

以下设置保存在 TicHealth 进程内的 SharedPreferences 中。官方没有可用的跨进程修改入口：从外部改写 XML 文件不会刷新进程内的缓存；强制停止健康应用会中断监测和运动，也不可取。本应用通过[进程内模块](#5-本应用的实现)调用官方的 getter 和 setter。

### 2.1 healthy

| 设置 | 键名 | 类型与默认值 | 说明 |
|---|---|---|---|
| 久坐提醒 | `alert_sit_long` | boolean，true | 开启时启动 TimerTaskService |
| 步数达到 80% 的提醒 | `alert_step_enable_when_80` | boolean，true | 在 `floor(目标 × 0.8) <= 步数 < 目标` 且当天尚未提醒时触发 |
| 每日目标达成提醒 | `alert_health_time_done` | boolean，true | 控制步数、运动和活动三类目标的通知；部分机型的界面隐藏这一项 |
| 健康提醒时段开始 | `health_not_disturb_start_new` | `HH:mm`，`22:00` | 健康提醒的免打扰时段，可以跨午夜；与系统勿扰无关 |
| 健康提醒时段结束 | `health_not_disturb_end_new` | `HH:mm`，`08:00` | 同上 |

久坐提醒没有可配置的间隔：检查安排在每小时的第 48 分钟（允许 4 分钟的延迟），并且需要计步已开启、`data_step` 授权、不在健康提醒时段、没有充电或离腕、不在活动中、当日活动目标未完成、当天未禁用提醒；然后检查两个半小时桶的步数合计是否不足 100，再根据最近 10 分钟的活动选择“还差 X 步”或“起身活动”的提示。这些是固件固定的规则。

### 2.2 sleep_settings

| 键名 | 类型、默认值和取值 | 修改后的行为 |
|---|---|---|
| `sleep_notification` | boolean，false | 重设入睡提醒的精确闹钟 |
| `sleep_complete_notification` | boolean，false | 睡眠结束后检查；与闹钟无关 |
| `sleep_alarm` | boolean，false | 取消并重建起床闹钟，向 MCU 发送开关和时间，再重设入睡提醒 |
| `smart_awake` | 字符串，`"0"`；`"0" / "15" / "30" / "45" / "60"` 分钟 | 智能唤醒窗口，在窗口内的浅睡或 REM 时唤醒；修改后重设闹钟 |
| `vibration_mode` | 字符串，`"never" / "once" / "always"`；5.6 默认 `"once"`，5.5 默认 `"always"` | 起床闹钟的振动方式 |
| `sound_switch` | boolean，true | 起床闹钟的提示音 |
| `sleep_time` / `getup_time` | `HH:mm`，`23:00` / `08:00` | 两者不能相同；启用排除时段时不能与之冲突；同时写入 POINT 33、34，更新 `week_of_year`、`sleep_target`，并重建闹钟 |
| `exclusion_time_open_status` | boolean，false | 开启前确认排除时段不与睡眠时段重叠；开启后在该时段内忽略睡眠的开始 |
| `exclusion_start_time` / `exclusion_end_time` | `HH:mm`，`10:00` / `17:00` | 同时更新派生的 `exclusion_time_target`，可以跨午夜 |

`sleep_target`、`exclusion_time_target`（分钟数）和 `week_of_year` 是派生的缓存，不是用户设置。官方睡眠页面上的 `sleep_respiration_rate`、`sleep_temperature`、`sleep_temperature_unit` 只是界面的偏好键，实际控制写入第 1.1 节的 `app_ctrl`。

## 3. 数据库中的设置

### 3.1 运动心率上限

运动心率上限保存为健康数据库中的 POINT 类型 16（HeartRateWarningFitnessUpperLimit），每次修改新增一条 `time_from = time_to = 当前毫秒` 的记录，`values` 为数值的浮点文本。界面范围为 100..220 bpm；默认值为 `220 − 年龄`（由资料中的生日计算，无效时按 25 岁），不是固定的 195。

外部写入：`ContentResolver.insert(content://com.mobvoi.health.db.provider/data_point, values)`，同样受 HEALTH 权限保护。ContentValues 为 `device_id:String`、`wwid:String`、`type:Int=16`、`time_to:Long`、`time_from:Long`、`synced:Int=0`、`deleted:Int=0`、`values:String`，不传 `_id`。必须使用单条 insert：它会在写库后发出 `notifyChange(data_point, null)` 并执行按类型的后续处理；bulkInsert 和 update 没有相同的后续处理。

身份必须取自手表当前的官方环境：设备 ID 来自 `com.mobvoi.wear.provider.info` 的 `wear/wear_device_id`（`illegal_device_id` 为无效值）；账号 wwid 优先取存在时的 `com.mobvoi.wear.provider.accountInfo` 的 `account/wwid`，否则取 `com.mobvoi.wear.provider.info` 的 `phone/wwid`。空字符串是合法的“无账号”；读取失败与空字符串必须区分。不能从最近的健康记录猜测账号或设备。回读时按当前账号（非空时）和 `type=16` 选择最新的 `time_to`；官方的读取方不一定按 device_id 过滤。

TicFitness 在**准备下一次运动时**读取 `fitness_hrm_warning` 和最新的类型 16 并缓存。正在进行的运动不会立即采用新的上限，也不应为此中断运动。

### 3.2 期望睡眠和起床时间

POINT 33、34 的值是本地时间距零点的毫秒数（HH:mm 换算为分钟 × 60000）。官方会在进程内把最新的两条记录转成 `sleep_time / getup_time` 偏好，但只在 `week_of_year` 为负或不等于当前周时执行，**同一周内直接退出**，也不会重建闹钟。因此只插入 33、34 记录无法即时修改睡眠时间，必须通过进程内的 setter。

## 4. 不应作为用户设置的键

`permission_health_sensor` 和 `permission_health_recognition` 是官方记录的授权状态；`is_fall_asleep` 是运行状态；`HeartHealthNotificationTime` 是通知时间；`heart_rate_app_foreground` 是前台状态；`mcu_log_sync_enable` 是日志控制。`auto_blood_oxygen_reminder` 出现在手机端的常量中，但没有找到手表上的读取方；`fitness_hpm_enable` 在 MCU 的观察列表中，但用户语义未确认。这些键不能仅因为存在就加入可写的设置。

噪声监测由独立的应用提供，它的配置不在本文范围内，本应用不提供噪声的设置。

## 5. 本应用的实现

### 5.1 API

手机通过 `CompanionServices.monitoring` 读写，类型定义在 [MonitoringSettings.kt](../../android/core/api/src/main/kotlin/dev/ticbox/companion/api/MonitoringSettings.kt)，页面为 [HealthMonitoringScreen.kt](../../android/ui/src/main/kotlin/dev/ticbox/companion/ui/device/HealthMonitoringScreen.kt)。

```kotlin
val monitoring = services.monitoring
monitoring.refresh()
monitoring.set(MonitoringSettingId.PRESSURE, MonitoringValue.Toggle(true))
monitoring.set(MonitoringSettingId.SLEEP_BLOOD_OXYGEN_INTERVAL, MonitoringValue.Number(1800))
monitoring.set(MonitoringSettingId.EXERCISE_HEART_RATE_UPPER, MonitoringValue.Number(195))
monitoring.set(MonitoringSettingId.SLEEP_EXPECTED_BEDTIME, MonitoringValue.Text("23:30"))
```

共 34 项。前 18 项对应第 1 节的 Provider 设置和第 3.1 节的运动心率上限；其中 `SLEEP` 和 `AUTO_EXERCISE` 写入时按官方规则重新计算 `auto_motion`；`REST_HEART_RATE_ALERT` 开启时先启用后台心率并设为实时模式。后 16 项是第 2 节的私有偏好，描述符 `requiresInProcessModule=true`，必须安装并实际加载[进程内模块](#52-进程内模块)：

| 接口 ID | 对应 |
|---|---|
| `SEDENTARY_REMINDER`、`STEP_GOAL_APPROACH_REMINDER`、`DAILY_GOALS_REMINDER` | `alert_sit_long`、`alert_step_enable_when_80`、`alert_health_time_done` |
| `HEALTH_QUIET_START`、`HEALTH_QUIET_END` | 健康提醒时段 |
| `SLEEP_BEDTIME_REMINDER`、`SLEEP_COMPLETE_REMINDER`、`SLEEP_WAKE_ALARM` | `sleep_notification`、`sleep_complete_notification`、`sleep_alarm` |
| `SLEEP_SMART_AWAKE_MINUTES` | `smart_awake` |
| `SLEEP_ALARM_VIBRATION`、`SLEEP_ALARM_SOUND` | `vibration_mode`、`sound_switch` |
| `SLEEP_EXPECTED_BEDTIME`、`SLEEP_EXPECTED_WAKE_TIME` | `sleep_time`、`getup_time`（以及 POINT 33、34） |
| `SLEEP_EXCLUSION_ENABLED`、`SLEEP_EXCLUSION_START`、`SLEEP_EXCLUSION_END` | 排除时段 |

| 结果 | 含义 |
|---|---|
| `READ` | 读取完成；需逐项检查 `available`，不可用的项不能当作默认值 |
| `UNCHANGED` | 当前值已等于目标值；仍可能修复必要的通知或闹钟刷新，不重放上一次的命令 |
| `STORED_AND_NOTIFIED` | 已保存、回读一致并完成了必要的刷新；不代表传感器已开始采样 |
| `STORED_UNVERIFIED` | 已尝试写入，可能部分成功或已生效但验证失败；不自动重试或回滚 |
| `INVALID_ARGUMENT` | ID、类型或取值不合法，未开始写入 |
| `UNAVAILABLE` | 通道、root、Local Bridge 版本、Provider 或官方身份不可用 |
| `INTERRUPTED` / `FAILED` | 连接被替换或执行失败；需要重新读取 |

写入只对上述两个 TicHealth 版本开放，未知版本保持只读。Provider 的 `storedValue=null` 表示没有保存过的值，不能显示为 false；默认值和 `firmwareFeatureEnabled` 单独报告（后台心率、血氧和自动运动的默认值取决于手表的 `Build.MODEL` 和系统 feature，5.5 的 Atlas 与 5.6 不同）。功能开关、数据授权、Android 权限、硬件和官方运行状态仍可能阻止采集。

每次连接替换后 `state.current=false`，旧值不冒充新手表的值；同一连接中的操作串行执行，不自动重放写入。读取通过 Local Bridge 的 root 通道进行，与工具箱共用设备身份确认，不会重新连接而使其他工具的租约失效。

部分固件即使调用方是 root，也会拒绝受厂商权限保护的 Provider。读取遇到明确的权限拒绝、且尚未尝试写入时，改用进程内模块的 Provider 代理（见下文）；写入失败不会换一条路径重试。模块版本过旧时返回 `monitoring_module_update_required`。

### 5.2 进程内模块

`:watch:healthmodule` 构建一个可选的 Magisk / Zygisk 模块（ID `ticwatch_health_settings`，为兼容已安装的模块保留旧名称），要求 Magisk 26+、已启用 Zygisk、Android 11（API 30）以上、ARM32 或 ARM64。它只加载到 `com.mobvoi.wear.health.at` 的主进程，跳过子进程、隔离进程、系统服务和 DenyList 中的进程；不修改官方 APK、签名校验、系统属性或 SELinux 策略。安装、构建和停用见[手表组件](../dev/watch.md)。

模块在真实的 Application 初始化完成后，核对包名、versionCode、versionName 和完整 APK 的 SHA-256；只有与 [HealthProfile.java](../../android/watch/healthmodule/src/main/java/dev/ticbox/companion/healthmodule/HealthProfile.java) 中的白名单完全一致时才创建接口。官方应用更新后不沿用旧的映射。

调用链：手机 → Local Bridge 的 root 通道 → root agent → 抽象 Unix socket `ticbox-health-v1` → 官方主线程 Handler → 官方的 setter 和闹钟工具。

- 服务端用内核的 `peerCredentials.uid == 0` 检查客户端；客户端反过来检查服务端的 UID 等于 TicHealth 的 UID，并核对回复中的包名、版本和摘要。
- 帧为 4 字节大端长度 + UTF-8 JSON，最大 64 KiB，每个连接处理一个请求。RPC 协议 `version=1`，实现的 `moduleVersionCode=1`。
- `op="settings"`：带固定的设置 `id` 和规范化的字符串 `value` 时写入，不带时读取。`op="status"`：只返回构建 ID 和官方应用身份，不读取设置。
- `op="provider"`（`providerVersion=1`）：在模块的工作线程中，以真实 Application 的 ContentResolver 执行固定的操作：app_ctrl 白名单的 get、put 和通知，`auto_motion` 只读，StepProvider 布尔更新和通知，类型 16 的读写。每个 authority 的所属包都单独核对；不修改 UID，不授予签名权限。
- 不接受类名、方法名、文件路径、URI、表名、SQL 或代码；值域在模块内再次校验。

```json
{"version":1,"op":"settings","id":"SLEEP_EXPECTED_BEDTIME","value":"23:30"}
```

私有偏好在官方进程中读写，闹钟按官方的入口取消和重建。修改睡眠时间时同时保留 POINT 33、34、周数和目标时长，排除时段按官方规则校验。官方写库是异步的，模块在同一个工作队列中加一道栅栏并回读，再回到主线程回复，不阻塞官方的主线程。

主线程上的操作串行执行。超时时尚未开始的任务被取消；已开始的任务继续完成官方的副作用，并返回 `STORED_UNVERIFIED`，不假装回滚，也不自动重放。显式写入相同的值时，也会检查并修复必要的闹钟或数据库后续工作。

Logcat 标签 `TicHealthModule` 和原生引导层的 `TicBoxHealthModule` 只记录启动阶段（例如被准入策略拒绝时为 `target_policy_rejected`），不记录健康数据、账号、通知或聊天内容。同一个模块还包含可选的[通知提示补丁](notification-alerts.md#3-本应用的-systemui-补丁)，两者分别启用。

## 6. 待验证

- 进程内模块在真实手表上的加载、SELinux 下的 socket 通信、16 项的读回和跨进程可见性。
- 官方闹钟和 MCU 对修改的实际采纳，以及提醒的实际触发。
- 部分固件在 root 下的 Provider 权限行为。
