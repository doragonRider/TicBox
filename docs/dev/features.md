# 功能实现

本文说明各项伴侣功能在手机端的实现：Android 接口、权限、持久化和完成语义。报文格式见[伴侣业务消息](../spec/companion.md)、[工具箱协议](../spec/toolbox.md)、[远程屏幕](../spec/remote-screen.md)和[账号与云](../spec/cloud.md)。

所有功能通过同一个 `CompanionFeatureSession` 使用主连接：它提供 sessionId、DataScope、伴侣路由，以及绑定物理会话的发送、请求和 Channel。功能不创建自己的蓝牙连接；断线后旧会话的命令和回调不能作用于新会话。

## 1. 通知

`services.notifications`（[NotificationSync.kt](../../android/core/api/src/main/kotlin/dev/ticbox/companion/api/NotificationSync.kt)）。

| 接口 | 作用 |
|---|---|
| `setEnabled` | 总开关，关闭时撤销本机拥有的全部镜像 |
| `setPackageEnabled` / `setPackageFilter(mode, packages)` | 按名单模式保存应用选择；ALLOWLIST 和 BLOCKLIST 两份名单独立保存 |
| `setSoundEnabled` / `setVibrationEnabled` | 全局提醒开关，不影响通知卡片 |
| `setPackageSoundMode` / `setPackageVibrationMode` / `setPackageAlertSettings` | 每个应用 `FOLLOW_APP` / `NEVER` / `ALWAYS` |
| `resync()` | 重新读取手机的活动通知 |

- **默认值**：总开关开、BLOCKLIST（空名单），但系统的通知访问权限默认未授予。读取设置失败时回到“关闭 + 空 ALLOWLIST”并报告 STORAGE，不会因读取失败放行所有应用。提醒策略见[通知的声音与振动](../spec/notification-alerts.md)。
- **授权**：`NotificationAccess.settingsIntent(context)` 打开系统的通知访问设置，返回后 `NotificationAccess.requestRebind(context)` 再 `resync()`。它与 `POST_NOTIFICATIONS` 无关。状态 `ACCESS_REQUIRED`、`LISTENER_DISCONNECTED`、`DISCONNECTED`、`RECONCILING`、`ACTIVE`、`FAILED` 都不证明手表已显示。
- **采集**：[WatchNotificationListener](../../android/core/platform/src/main/kotlin/dev/ticbox/companion/platform/notification/WatchNotificationListener.kt) 只是系统入口，和会话服务通过进程内的弱引用相接。发布、删除和 ranking 回调合并 100 ms 后，由 `AndroidNotificationSource` 读取完整的活动通知；监听断开或读取异常不会被当作空快照去批量删除镜像。
- **对账**：[NotificationSyncEngine](../../android/core/engine/src/main/kotlin/dev/ticbox/companion/engine/notification/NotificationSyncEngine.kt) 把持久化的存活条目与当前快照比较，内容和图片摘要不变时不重写，消失或被屏蔽的发送墓碑。新会话重建时发送静默版本（去掉 defaults / vibrate，`has_sound=false`，`only_alert_once`），内容指纹独立于静默编码，避免之后的无关回调让整批旧通知再次提醒。
- **持久化**：内容在 DataItem / Asset 缓存中；设置在 `noBackupFilesDir/notification-sync.settings`；全局通知事件计数在 `notification-revision.counter`（重建时沿用原 revision，新内容才递增）。替换或撤回成功后才把旧图片交给回收，SQLite 在同一事务中重新检查所有引用和所有者。
- **不转发**：`FLAG_LOCAL_ONLY` 通知、本应用自己的前台通知、用户屏蔽的应用。系统隐去的敏感内容不能恢复。
- **字段**：`AndroidNotificationMapper` 提取标题、正文、长文本、摘要、分类、分组、进度、计时器、频道的声音和振动，以及 MessagingStyle 的发送者、消息和图片；Spanned 文本用 HTML 表示。图标、大图和头像为有界 WebP Asset，附件只读 `content` / `android.resource` URI。
- **动作**：`PendingIntent` 映射为不透明的随机 ID，不序列化、不按数字 index 猜测。`/bridger/rpc` 在独立的串行观察器中执行，文字回复经 `RemoteInput.addResultsToIntent`。`/bridger/result` 的 success 只表示 `PendingIntent.send` 被系统接受。重复的 invocation 在有限的结果缓存内复用已执行结果。
- **手表上滑走**：远端墓碑经 DataSync 交给通知引擎，核对来源和本机拥有的 URI 后撤销手机通知。

界面的应用选择器提供图标、搜索、全选 / 全不选、模式、排序、建议选择和剪贴板导入导出；批量替换先确认数量，未安装的包名保留可见。

## 2. 电话

`services.calling`（[Calling.kt](../../android/core/api/src/main/kotlin/dev/ticbox/companion/api/Calling.kt)）。使用非 UI 的 `InCallService`，不替换用户的默认拨号器。

```xml
<uses-permission android:name="android.permission.MANAGE_ONGOING_CALLS" />
<uses-permission android:name="android.permission.READ_PHONE_STATE" />
<uses-permission android:name="android.permission.CALL_PHONE" />
<service android:name=".calling.CompanionInCallService" android:exported="true"
    android:permission="android.permission.BIND_INCALL_SERVICE">
    <intent-filter><action android:name="android.telecom.InCallService" /></intent-filter>
    <meta-data android:name="android.telecom.IN_CALL_SERVICE_UI" android:value="false" />
    <meta-data android:name="android.telecom.IN_CALL_SERVICE_RINGING" android:value="false" />
    <meta-data android:name="android.telecom.INCLUDE_EXTERNAL_CALLS" android:value="true" />
    <meta-data android:name="android.telecom.INCLUDE_SELF_MANAGED_CALLS" android:value="true" />
</service>
```

- 管理能力以 `TelecomManager.hasManageOngoingCallsPermission()` 为准，有 WATCH 关联不等于已授权（[InCallService](https://developer.android.com/reference/android/telecom/InCallService)）。`CallingAccess` 分别报告关联、profile、管理能力、默认拨号器和运行时权限；`prepareAuthorization` 给出权限请求、设置页 Intent 或 `PairWatch`。
- `setEnabled` 是手机本地的 `call_sync` 开关，保存在 `noBackupFilesDir/companion/calling-settings-v1.bin`（默认开）。读取完成前不 attach，避免已关闭的功能在重建时先发一轮状态。
- `DISPATCHED` 只表示公开 API 调用已提交，不是“已接通”“短信已送达”或“音频已切换”。音频请求等待 `OutcomeReceiver`，10 秒超时。
- 拨号用 `TelecomManager.placeCall`，由系统执行运营商、SIM 和紧急号码规则。拒接短信由 `Call.reject(true, text)` 交给系统，不使用 SmsManager。DTMF 每次一个字符，播放 300 ms。
- **音频**：通话音频依赖 HFP / LE Audio，不在本协议中。优先使用 `onAvailableCallEndpointsChanged` 和 `requestCallEndpointChange`；无法唯一匹配手表时，用公开的 `requestBluetoothAudio(exactDevice)` 按 MAC 选择。没有 HFP 时返回 `watch_hfp_audio_unavailable`。`silenceRinger()` 需要默认拨号器或 `MODIFY_PHONE_STATE`，被拒绝时报告 UNAVAILABLE，不改手机音量或勿扰来代替。
- 系统标记为被勿扰屏蔽（`Call.EXTRA_IS_SUPPRESSED_BY_DO_NOT_DISTURB`）的来电不镜像。
- 失去整个 Telecom 绑定时向手表发送 RESET，不伪装成挂断。日志不含号码、姓名、拒接文本或 Call ID。
- 启用通话控制时，在 GMS 命名空间发布动态能力 `full_companion_call_control_capability`，关闭或失去权限时撤回。

## 3. 媒体

`services.media`（[MediaControls.kt](../../android/core/api/src/main/kotlin/dev/ticbox/companion/api/MediaControls.kt)）复用通知访问权限，不下载媒体 URI，也不主动开始播放。

- UI 只用 `state`、`setEnabled`、`setPreferredPackage`、`setAutoLaunch`（默认关）和 `resync`；设置在 `noBackupFilesDir/media-controls.settings`。
- 选择会话：优先正在播放的，其次优先级最高的暂停会话；指定了应用时先在该应用内选择。单个会话解析失败时保留上次的镜像，不当作“没有媒体”。
- 状态和封面按内容摘要比较，重复扫描不重传封面。首次和重连发布强制关闭自动拉起，下一次内容变化才采用用户设置。`elapsed_time` 递增，避免手表丢弃旧状态。
- 授权撤销、关闭或权威的空快照撤回 DataItem；物理断线也持久化墓碑，供以后重放。
- 观察到手表的 `media_notification_message_watch` 能力后才启用 v2，传统 DataItem 仍是基础路径。

## 4. 查找

- **手表找手机**：设置默认开，保存在 `find-devices.settings`；关闭会停止响铃并撤回 `mdm/ringable` 能力。`AndroidPhoneRinger` 先进入带停止按钮的 `mediaPlayback` 前台服务，再异步准备系统提示音并请求振动（准备不阻塞 5 秒的回执窗口）。8 秒准备超时、播放错误、失去音频焦点、用户停止、5 分钟总时限、断开和关闭都会清理。临时提高闹钟音量，结束时仅在用户没有另改音量时恢复；不修改勿扰或静音。UI 分别显示 `active`、`preparingAudio`、`audioPlaybackStarted`、`vibrationRequested` 和 `failure`。
- **手机找手表**：`services.finding.ringWatch()` 见[手机找手表](../spec/watch-finding.md)，需要手表安装同版本的 Local Bridge。

## 5. 设备设置与锁屏

`services.deviceSettings`（[DeviceSettings.kt](../../android/core/api/src/main/kotlin/dev/ticbox/companion/api/DeviceSettings.kt)）：

- `refresh()` 请求设备信息和电量，等待同一会话中可解析的响应，超时不冒充成功。`wearVersionNumber` 是 Android SDK_INT，不是固件版本。获取设备信息不使用 `/companion/send_companion_info`（它会触发账号上传）。
- `setLanguageTags` 和 `set24HourFormat` 写共享设置，null 表示跟随手机；与首次设置共用写入器，已有相同值不重复写。`PERSISTED_AND_SUBMITTED` 只表示进入了持久的 DataApi 队列。
- 偏好文件统一用 `AtomicPreferencesFile` 在 IO 线程上串行原子读写；`preferencesReady` / `settingsReady` 之前，编辑和命令会等待恢复，避免默认值覆盖已保存的设置。
- `openSettings` 和 `requestUninstall` 经 Remote Intent 打开手表的设置页或系统卸载确认；只有结果中的 `successful=true` 才报告 `REMOTE_UI_OPENED`。卸载以应用目录中移除为准。
- 应用目录来自 `/installed_apps/data/...` DataItem，图标从同一 AppKey 的 Asset 缓存读取，缺失时请求。
- 锁屏：读取支持的类型，`configureLock` 发起配置并注册三个反向回调；请求不包含密码，打开配置界面不等于配置完成。
- 心脏健康入口的显示（`setHeartHealthFeatureVisible`）返回 `SUBMITTED_UNVERIFIED`；实际监测开关在 `services.monitoring`，见[监测与提醒设置](../spec/monitoring-settings.md)。

## 6. 表盘

`services.watchFaces`（[WatchFaceManagement.kt](../../android/core/api/src/main/kotlin/dev/ticbox/companion/api/WatchFaceManagement.kt)）：

- `state` 包含 installed、favorites、favoriteOrder、current、photo、editing 和 lastOperation。`refresh()` 请求 DataSync 重新同步并读本地缓存；`inventoryComplete` 始终为 false，因为手表在预览加载失败时会跳过该条。
- 收藏的增删、排序和切换使用 request_id 等待 `/favorites/rpc/favorites_operation_response`。移除收藏不等于卸载；卸载打开手表的确认界面，以目录更新为准。
- `AndroidWatchFaceFiles` 只接受调用方已有读取权限的 content / file / android.resource URI，拒绝网络 URL；`stageArchive(uri)` 返回可重复打开的私有副本，用完删除。
- `transferMwfArchive(archive, exactPath, explicitRoute)`：先检查 ZIP（SHA-256、大小、CRC，有界读取，不解压，拒绝绝对路径、盘符和 `..`），再以 32 KiB 块写入 Channel，重新校验字节数和摘要。结果为 `TRANSFERRED_UNVERIFIED`，不显示“已安装”。
- 本地预算：消息和编辑帧 1 MiB，目录 512 条，待确认操作 16 个、30 秒，ZIP 64 MiB、读取总量 128 MiB、512 个条目。

## 7. 紧急求助

`services.sos`（[Sos.kt](../../android/core/api/src/main/kotlin/dev/ticbox/companion/api/Sos.kt)）：

```kotlin
sos.refresh()
sos.setEmergencyNumber(number)
sos.setContacts(listOf(SosEmergencyContact(name, number)))
sos.setButtonTrigger(enabled)
sos.setFallDetection(SosFallDetectionMode.EXERCISE_ONLY)
sos.setShareLocation(enabled)
sos.phoneAssistance.configure(SosPhoneAssistanceConfig(enabled = true))
```

- **设置确认**：写入后立即只读查询 `/watch_settings`，读回值匹配才算 `CONFIRMED`。`sync_ok` 不完成任何命令，因为手表可能在写入失败后仍回 `sync_ok`。跌倒检测额外返回 `watch_preference_confirmed_sensor_execution_unverified`。
- **没有请求 ID 的协议**：写入、读回和刷新串行执行。已发送的请求超时、取消或 IO 异常后，本会话暂停后续写入（`previous_write_outcome_unknown_reconnect_required`），新的物理连接重新建立相关性。同一会话中 `u_time` 更旧的快照不覆盖已观察的设置。
- **缓存**：每块手表的设置用 `AtomicFileSosSettingsStore` 存在 `noBackupFilesDir`，文件名为 peer 的摘要；新手表不继承联系人。`settingsCurrent=false` 表示缓存。
- **手机短信援助**（默认关，与手表的按键和跌倒开关分开）：收件人和位置分享必须来自本次物理会话的读回；缓存未刷新时，在 60 秒事件时效内最多等待 15 秒。不能用于短信的联系人被跳过（`sos_contacts_partially_skipped`），全部不可用才拒绝。每次事件和发送前检查 `SEND_SMS`、可用的 SIM 和配置；多 SIM 可指定 `subscriptionId`，SIM 变化时停止，不换卡。
  - 不分享位置：首轮有提交成功即结束；全部失败时每 4 小时再试一轮，从不立即重发不确定的提交。
  - 分享位置：首条不等待定位，第一个新定位立即补发；之后每 15 分钟检查，移动 100 米以上发送位置，否则每 4 小时发送求助更新；24 小时后发送结束消息。定位不可用时不伪造坐标。
  - 已接受的事件由独立的前台服务持有，蓝牙掉线或服务解绑不停止它。私有事件日志在提交前预留尝试，进程恢复后不重播不确定的首条短信。`smsPartsSubmitted` 只是 API 提交的分段数。
- 界面显示实际的确认层次（“设置已读回”“短信已提交”），不显示“保证获救”之类的结论。号码和联系人姓名不写日志或 SavedState。

## 8. 勿扰同步

`services.dnd.setMode(OFF | FOLLOW_PHONE)`。手机用已授权的 `NotificationListenerService.currentInterruptionFilter` 和变化回调，启动和重连时发送最新状态；监听器不可用时为 unknown（`notification_listener_unavailable`），不当作“关闭勿扰”。手表的 Local Bridge 建立本应用自己的 PRIORITY `AutomaticZenRule`，只开关这条规则，不影响用户手动的勿扰或其他规则。

手表没有通知策略权限时，经 Magisk 授权执行 `cmd notification allow_dnd <包名>`。偏好、版本号和可能已激活规则的手表列表保存在 `no_backup/companion/dnd-v1.json`；发送前先持久化归属，切换手表后仍能在原表重连时释放规则。回执必须匹配 requestId、版本、peer 和物理会话。线格式见[工具箱协议 §7](../spec/toolbox.md#7-勿扰同步)。

## 9. 工具箱

`services.toolbox` 提供 `adb` 和 `localBridge` 两种后端，使用同一个 Agent。协议、操作和错误码见[工具箱协议](../spec/toolbox.md)。

```kotlin
val tools = services.toolbox
tools.adb.pair(AdbEndpoint(host, pairingPort), sixDigitCode)
tools.adb.connect(AdbEndpoint(host, connectionPort)) // 两个端口可能不同
tools.localBridge.connect()                          // 需要伴侣主连接就绪
```

| 项目 | 无线 ADB | root Local Bridge |
|---|---|---|
| 连接 | Kadb 直连 adbd：六位码配对、TLS、NSD 发现 | 主连接唤醒 + 专用 RFCOMM |
| UID | 通常 2000（以握手为准） | 必须为 0 |
| 前提 | 目标开启无线调试；Android 17 手机需要本地网络权限 | 两端同签名、已有伴侣连接和蓝牙绑定、手表授予 Magisk root |
| Agent 来源 | 手机内嵌的手表 APK，部署到 `/data/local/tmp` | 已安装的 Local Bridge APK |
| 身份 | ADB 私钥保存在手机 no_backup | 手机私有的 256 位安装密钥 |

### 9.1 设备身份

READY 返回硬件序列属性的 SHA-256 摘要（原始序列号不发送不记录）。`state.target` 包含摘要、后端种类和进程内唯一的连接代次；`backend.bind()` 取得操作上下文，跨文件选择器、确认弹窗和多步骤复制都持有它，每次新通道重新验证。物理身份变化时阻止工具操作，需要 `confirmDevice(target)`，确认新设备不会重新授权旧列表或旧的文件选择。相同设备重连刷新列表，不把新端口当作新设备。应用管理器按 READY 的 `protectedPackages` 禁用对通道宿主的强停、清数据、禁用和卸载。

演示手表的高级页面复用正式服务的无线 ADB，可以操作真实的 Android 设备；不接入 root Local Bridge。进出演示不关闭共享的 ADB。

### 9.2 无线 ADB 的发现与重连

- **配对状态**：发现结束后，用本应用的 ADB 身份对连接端点做一次 CNXN / TLS 认证检查（不执行 shell、不部署 Agent），最多并发 4 个、单个 3 秒、整批 5 秒。`pairingStatus`：认证通过为 `PAIRED`，对端明确拒绝客户端证书为 `REQUIRED`，其他为 `UNKNOWN`；不按 IP 缓存。连接时收到证书拒绝返回 `adb_pairing_required`。
- **进入无线调试页**（未连接）时同时：`reconnectLast()` 对上次保存的地址做认证检查（约 3 秒，不改 `state`），通过才 `connect()`；照常搜索，按 mDNS 服务名（`adb-<序列号>-…`，随连接保存在私有存储）标出上次的设备（手动输入的按 IP），认证为 `PAIRED` 时自动连接它的新端口。两者失败都不提示。
- **主动断开**：`disconnectByChoice()` 之后不再自动重连，重启应用也保持，直到下次手动连接。连接中断等其他断开不算。

### 9.3 操作通道的恢复

文件、应用、剪贴板和投屏共用一个 Kadb 客户端，每次操作开一条 shell-v2 流和一个 Agent。

- OPEN 已完成、结果尚未交付时取消，只关闭这一条流；OPEN 仍阻塞时，只能重置这一次捕获的客户端连接。
- 只有业务 REQUEST **尚未发送**、OPEN 或 READY 遇到 IOException、且 Kadb 确认底层连接已失效时，才重新打开 Agent 一次；新 READY 仍核对版本、身份、代次和权限。证书拒绝、设备变化、远端错误和调用者取消不恢复。REQUEST 发出后的任何失败都不重发，包括只读操作。
- 诊断（`TicBoxProtocol` 的 `toolbox_adb`、`toolbox` 组件）只记录通道编号、存活状态、异常类型、固定的失败类别、协议错误码，以及白名单内的 TLS 原因（`tlsReasons`、BoringSSL 错误码、`tlsOperation`），不记录异常原文、命令参数、剪贴板或文件名。

### 9.4 Kadb 的 TLS

[本地 Kadb 模块](../../android/thirdparty/kadb/README.md)基于 Kadb 2.1.5，把连接阶段手动驱动的 `SSLEngine` 换成 Android 的 `SSLSocket`：STLS 确认后在原 TCP socket 上升级；关闭单条 shell 流不关闭共用的 TLS；取消时先关闭原始 socket 以解除阻塞的握手或读写。ADB 帧、流复用、队列、私钥存储和 SPAKE2 配对保留，TLS 限定 1.3，不增加 Conscrypt 原生库。诊断字段为 `tlsImplementation=platform_ssl_socket`。

背景：曾观察到复用连接的 OPEN 遇到 `SSLProtocolException`，目标端 adbd 记录 `SSL_read / DECRYPTION_FAILED_OR_BAD_RECORD_MAC`，与网络、代理和应用版本无关；主机上的压力测试没有复现。换用 `SSLSocket` 是待实机对照的修正，尚未证明原实现就是唯一原因。主机回归覆盖同一 TLS 连接上顺序 200 条、4 路并发共 400 条流、350001 字节双向传输、TLS 1.3 KeyUpdate、错误公钥和 TLS 1.2 拒绝、握手超时和取消。已知该模块的并行流测试偶尔因 Connection reset 失败（与改动无关）。

### 9.5 root 连接

伴侣主连接、健康同步和 root 工具是不同的通道：主界面显示已连接，root 工具还需要独立的 RFCOMM 认证、Agent READY 和 `probe` 回复。手表的 `ToolboxWakeService` 每次读取后立即写入并 flush（子进程的 stdin 有缓冲，否则小的 `probe` 会卡住）。手机 `connect()` 的 90 秒期限覆盖整个过程，超时进入 `FAILED / timeout`；调用者取消回到 DISCONNECTED。

### 9.6 内嵌的手表 APK 与更新

手机 APK 的 `assets/watch/local-bridge.apk` 是同一次构建、同签名、同版本、同变体的手表 APK，其中包含 `ToolboxAgentMain` 和 `assets/toolbox/scrcpy-server-v4.1.jar`。ADB 模式直接把它作为 `app_process` 的 classpath，并从中流式读取 scrcpy 服务端（部署时校验摘要）。生成资产的任务在 `:app`，避免 `:core:platform` 反向依赖手表 APK。

- 安装：交给已连接的 `toolbox.adb.install(...)`；或在已安装的 root Bridge 上 `toolbox.localBridge.updateHost(...)`。无线调试已连接时优先走 ADB（不会随 Bridge 重启而断开）。
- root 更新：`updateHost` 另开一条通道，只检查 READY 的 UID 为 0 且身份一致，然后只发 `install`。下限 `FIRST_UPDATABLE_VERSION = 3`，更旧的返回 `bridge_update_needs_adb`，比手机新的返回 `phone_app_outdated`。提交时系统会强停 Bridge，通道随之断开：全部发出后断开为 `install_result_unknown`，没发完为 `bridge_update_interrupted`。页面重新连接并列出应用，版本等于手机版本且 APK 路径改变，才显示已更新。
- 安装被提前拒绝时，Agent 写出 RESULT / ERROR 后继续丢弃声明范围内的在途 DATA / END（最多 5 秒），避免连接重置掩盖拒绝原因；手机在上传写失败后最多再等 2 秒接收结果。

### 9.7 列表缓存

列应用或列目录需要一两秒，`ToolboxMemory` 把结果保存在进程内存中（不写磁盘、不进日志），按 `ToolboxTarget` 绑定：

| 变化 | 处理 |
|---|---|
| 手动刷新 | 应用管理器的标题栏按钮、文件管理器下拉；失败时保留页面上的列表 |
| 文件操作（无论成败） | 忘记涉及的路径、列出它的父目录及其下所有目录，再读当前目录 |
| 进入已不存在的目录 | `not_found` 显示问题页，并忘记该目录和它的父目录 |
| 应用操作 | 除成功启动外都重读列表 |
| 终端和安装 | 清空全部列表 |

### 9.8 应用包文件

`readBundle(file)` 只读不传，返回选好的 `ToolboxBundle`（普通 APK 返回 null，走 `install`）；`installBundle` 先装 APK，成功后再复制数据。代码在 `core/engine/.../toolbox/`。

| 决定 | 理由 |
|---|---|
| 按内容识别，不看扩展名和 MIME | 各来源的类型不一致；`.apk+` 是含 `apk+.json` 的 ZIP |
| 读 ZIP 中央目录，不用 `ZipInputStream` | 常见的 XAPK 是 STORED 加数据描述符，`ZipInputStream` 不支持；文件可达数 GB，需要 ZIP64 |
| 读内层 APK 的清单，不看文件名 | 分包名可能是 `main.apk`、`obb.apk`、`AssetPack1.apk` |
| `.apks` 按 `toc.pb` 选 | 同一文件包含其他 Android 版本的构建和按需模块；只保留 install-time 模块和资产包 |
| 分包按目标设备选 | 读取 ABI、SDK、密度和语言：ABI 取设备列表中最靠前的，密度取不低于设备的最小档，语言取设备语言加 `en`；功能模块、资产包和纹理分包全部保留 |
| 数据只写 `Android/obb/<包名>/`、`Android/data/<包名>/` | 路径来自不可信的文件，而 root 可以写任何位置；越界、`..` 或其他包的路径拒绝整个文件（`package_bad_path`） |
| 复制前用 `df` 检查空间 | 确认不够才拒绝（`package_no_space`），查不出不拦 |
| 临时文件写完后改名并核对大小 | 应用不会读到半个文件；APK 已装而数据失败时返回 `dataFailure`，保留文件以便重试 |

已知限制：不解密旧的加密 `.apkm`（`package_encrypted`）；不提供手动选择分包；不删除旧版本的 OBB；单个 APK 写入 5 分钟、整次 30 分钟的超时不变，蓝牙等慢速连接装不完 GB 级的单个 APK（OBB 走 `push`，没有这个限制）。

### 9.9 第三方组件

| 组件 | 许可 | 用途 |
|---|---|---|
| [Kadb 2.1.5](https://github.com/flyfishxu/Kadb) | Apache-2.0 | 无线 ADB（本地修改见其 README） |
| [SPAKE2-Java 1.1.1](https://github.com/Flyfish233/spake2-java) | GPL-3.0 | ADB 六位码配对 |
| [scrcpy 4.1](https://github.com/Genymobile/scrcpy/tree/v4.1) | Apache-2.0 | 投屏服务端，基线提交 `2926c06c5dc3064ae6d8db706f1a98a37cfcf3f0` 加私有扩展；`tools/build-scrcpy-server.mjs --check` 离线重建并核对 |

本项目的原创代码和文档使用 GPL-3.0-only，第三方组件保留各自的许可和版权声明。手机 APK 包含依赖的许可和声明；发行时应提供与 APK 对应的完整源码和构建信息。

## 10. 云、账号与 Google 服务

默认数据流是手表 Provider → 专用蓝牙 → 手机 → 本地数据库，不需要任何账号。`services.privacy`（`CloudSettingsClient`）保存用户在界面上确认的开关，Runtime 据此启停服务；设置在 `noBackupFilesDir/cloud-privacy.settings`，读取失败时全部关闭，不进入备份。

| 开关 | 含义 | 默认 |
|---|---|---|
| `MOBVOI_CLOUD` | Mobvoi 账号和健康云，允许云同步和向手表发送账号资料 | 关 |
| `GOOGLE_ACCOUNT_TRANSFER` | 把 Google 账号添加到手表 | 关 |
| `GOOGLE_CLOUD_SYNC` | 经 Google 云连接手机和手表 | 关 |
| `GOOGLE_LOCATION_SERVICES` | 首次设置时允许 Google 网络定位 | 关 |
| `VENDOR_DIAGNOSTICS` | 官方诊断上报设置 | 关 |
| `WATCH_INTERNET_PROXY` | 手表经手机上网（Flow15），可能连接 Mobvoi、Google 等服务 | 关 |

- 开关只表示允许使用，不表示已登录或远端已开通；各项独立，不互相隐式开启。代理是任意 TCP/UDP 转发，关闭时整个 Flow15 监听都不启动，界面要说明开启后官方手表应用可能自行联网。
- `ConfiguredCloudHttpTransport`：未开启时不创建真实的 HTTP transport；关闭时释放服务和同步任务，保留本地数据。`ConfiguredGoogleServices` 和 `ConfiguredSetupClient` 同理，后者默认跳过 Google 账号，不接受已关闭功能的 opt-in。
- `refreshHealth()` 和 `setStepGoal()` 在手表上都会触发强制云同步：前者要求 `MOBVOI_CLOUD`，否则抛 `CloudFeatureDisabledException`；后者不受开关限制，但界面默认只把目标存在手机上，用户选择同时修改手表时先提醒并确认。
- 电量、步数、本地健康同步和查询不受云开关影响；手表 Local Bridge 没有 INTERNET 权限，健康数据不经过 GMS Data Layer。
- 手机上的开关只控制本应用启动的服务，不能阻止官方手表应用自行使用 Wi-Fi。
- Mobvoi 会话和资料用 AndroidKeyStore AES-GCM 加密保存，密码不持久化；健康缓存按 region / wwid / accountId 隔离。日志不含凭据或正文。

**Google 服务**（`services.google`）：账号复制、`applyConsents`、`setCloudSyncSetting` 分别暴露。PendingIntent 保存在 Runtime 的 `AndroidGoogleActions`，API 只暴露 `GoogleUserAction` token；Activity 用 `takeLaunch / releaseLaunch / activityResult / launchFailed` 处理，Activity 的返回只表示界面已关闭，成功以 GMS 的转移回调为准。Google 的私有服务对调用方的安装签名有名单检查，本应用不在默认名单中，所以账号复制和云同步通常会被拒绝；实现仍发起真实调用，如实报告 SecurityException、状态码或超时。见[账号与云 §6](../spec/cloud.md)。

## 11. DataApi

`services.data`（[DataSync.kt](../../android/core/api/src/main/kotlin/dev/ticbox/companion/api/DataSync.kt)）是后端扩展接口，普通页面不直接构造 URI 或 protobuf。

| API | 行为 |
|---|---|
| `scopes(peer?)`、`items(scope, query)` | 离线查询持久化的数据；每页 1–500 条 |
| `publish(scope, route, draft)` / `delete` | 先事务提交本地序号和数据，再排入当前会话；离线时保留 |
| `putAsset` / `asset` / `requestAsset` | SHA-1 定址缓存，按 AppKey 登记访问权限 |
| `requestSync()` | 重发 SyncStart 并检查缺失的 Asset |
| `awaitProgressBarrier(timeoutMillis)` | 可选的 DATA_SYNC_PROGRESS 屏障，超时不当作成功 |

- `DataScope(peer, localNodeId, remoteNodeId, networkId)` 是持久身份：同一蓝牙地址但节点被重置时，数据和游标分开保存。
- 状态 `RESUMING`（已发 SyncStart）→ `ACTIVE`（收到对端 SyncStart）；`NEEDS_RESYNC` 表示数据身份无法解码或存储失败，请求重建物理会话。
- 入站在 64 条的有界队列中按序处理，满时挂起读取（`data_queue_backpressure`），不丢包、不推进游标。存储或发送失败冻结数据会话并请求重连，不能跳过第 N 项而提交第 N+1 项的游标。
- SQLite `no_backup/data-sync.sqlite`：scopes、cursors、sequences、items、item_assets、assets / asset_acl。远端条目、Asset 引用、来源游标和 relay 序号在同一事务中提交；Asset 提交后才 ACK；权限取并集。资产预算 64 MiB，数据库页数预算 128 MiB，满时报告问题，不清游标或假 ACK。
- 本地并发发布用一个互斥锁把序号分配和入队放在同一临界段，避免高序号先发导致低序号被远端去重。

## 12. 待验证

真实设备上的：通知动作和回复、HFP 通话音频、第三方 VoIP 的可见性、媒体 v2、手机找表的声音和振动、锁屏配置、MWF 表盘安装、SOS 短信和定位的完整流程、勿扰规则、root 更新 Bridge、无线 ADB 的 TLS 修正、Google 服务的签名准入。
