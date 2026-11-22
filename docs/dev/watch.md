# 手表组件

手表上有三部分代码，都随手机 APK 分发：

| 模块 | 产物 | 运行方式 |
|---|---|---|
| `:watch:healthbridge` | Local Bridge APK（包名 `dev.ticbox.companion`，与手机相同） | 普通应用，后台服务由手机唤醒 |
| `:watch:toolboxagent` | 打包进 Local Bridge APK 的 `ToolboxAgentMain` 和 PTY 原生库 | `app_process` 启动：ADB 下为 shell，root 下为 UID 0 |
| `:watch:healthmodule` | 可选的 Magisk / Zygisk 模块 ZIP（ID `ticwatch_health_settings`） | 加载进官方健康应用的主进程 |

手机 APK 内嵌同一次构建的手表 APK（`assets/watch/local-bridge.apk`）和模块 ZIP（`assets/watch/health-module.zip` 与 `health-module.json`），`npm run android:check` 核对它们与独立产物逐字节一致。

## 1. Local Bridge

最低 API 30，最终清单移除了 INTERNET 权限，健康数据只经过专用的蓝牙 RFCOMM。

| 组件 | 作用 |
|---|---|
| `BridgeSetupActivity` | 设置页：允许手机读取健康记录、蓝牙权限、root 运动采集、导出入口 |
| `HealthWakeService` | 接收 `/ticbox_local_health/wake`，开启短时的读取窗口，见[本地桥接协议](../spec/local-bridge.md) |
| `ToolboxWakeService` | 接收 `/ticbox_toolbox/wake`，认证后以 `su -c app_process` 启动 root Agent 并转发字节 |
| `FindWatchListenerService` / `FindWatchActivity` | 手机找手表的响铃页，见[手机找手表](../spec/watch-finding.md) |
| `DndListenerService` | 勿扰同步，维护本应用自己的 `AutomaticZenRule` |
| `HealthExportActivity` | 手动导出健康 JSON 到 `Download/TicBox/` |
| `HealthSampleActivity` | 只在 debug 构建中的采样检查页 |

- 首次安装后在手表上打开一次设置页并允许读取，之后不需要每天打开；唤醒事件由 `WearableListenerService` 派送。系统强行停止、禁用应用、撤销蓝牙权限或 GMS 不派送事件时无法自行唤醒。
- 健康读取开关只影响健康数据；找手表、勿扰和 root 工具不依赖它。
- root 运动采集在应用私有目录中建立只读的一致快照，需要在设置页打开并允许 Magisk 授权。
- 界面跟随手表系统语言显示英文、简体或繁体中文。Logcat 标签 `TicBoxHealthBridge`，不记录 nonce、健康值或数据库内容。
- 手表增量状态是独立的 `health-incremental.sqlite`（1.0.9 起为版本 2，使用 WAL；修改规则见[构建与检查 §5](building.md#5-数据库与格式的兼容)）。

### 1.1 安装与更新

- **首次安装**：手表开启无线调试，在手机“高级 → 无线调试”配对并连接，然后在本地桥接页安装内嵌的 APK（`toolbox.adb.install`）。
- **安装后开启**：无论从手机还是从电脑用 `adb install` 安装，新装的 Local Bridge 都处于关闭状态。必须在手表上打开它，开启“允许手机读取”（从电脑安装时，开启时还会请求蓝牙权限，需要允许）。关闭时手表直接忽略手机的唤醒消息，不回复任何错误，所以手机只能看到“没有响应”（`UNREACHABLE`），实际上不会出现 `bridge_disabled`。因此本地桥接页在 `UNREACHABLE` 或 `NOT_ALLOWED` 时，在页面顶部显示开启提示和“再次检查”按钮；手机安装成功后的提示也会说明这一步。
- **更新**：无线调试已连接时走 ADB；否则在已获 root 的 Bridge 上经 root 通道更新（`toolbox.localBridge.updateHost`），规则见[功能 §9.6](features.md#96-内嵌的手表-apk-与更新)。手机和手表的私有协议必须同时更新，版本不一致时工具连接被拒绝。
- 覆盖安装保留签名和数据，不要卸载或清除数据。
- 从 TicWatch 迁移时，旧的 TicWatch Local Bridge 是另一个包名，需要在手表上卸载；TicBox 的 Local Bridge 重新安装并授权，进程内模块原地升级（模块 ID 不变）。

## 2. 工具箱 Agent

同一份 Agent 代码服务两种连接：ADB 模式把手机内嵌的手表 APK 部署到 `/data/local/tmp` 作为 classpath；root 模式直接用已安装的 Local Bridge APK。READY 报告实际的 UID、SDK、能力和设备身份摘要；root 不会被人为降成 shell，文件和命令的权限仍由 Magisk、SELinux、只读挂载和系统服务决定。

- 原生 PTY 库由 `buildPtyNative` 用 NDK 构建，`packagePtyAssets` 打包；没有 NDK 时这两个任务需要排除（`-x`）。主机上的原生测试用系统的 C++ 编译器。
- scrcpy 服务端是固定的 `assets/toolbox/scrcpy-server-v4.1.jar`，Agent 启动前校验 SHA-256，见[远程屏幕](../spec/remote-screen.md#1-scrcpy-服务端)。
- root 下的操作不强杀 Agent；连接丢失时关闭 stdin、继续读完剩余输出，让操作走完 finally。各操作类型的收尾见[工具箱协议](../spec/toolbox.md)。
- 每个操作独占一个 Agent，同时最多 4 个。

## 3. 进程内模块

模块的准入、RPC 和调用链见[监测与提醒设置 §5.2](../spec/monitoring-settings.md#52-进程内模块)，SystemUI 补丁见[通知的声音与振动 §3](../spec/notification-alerts.md#3-本应用的-systemui-补丁)。

### 3.1 构建

```bash
sdkmanager "ndk;27.2.12479018"
./android/gradlew -p android :watch:healthmodule:check :watch:healthmodule:moduleZip
```

- 产物 `android/watch/healthmodule/build/distributions/ticbox-healthmodule-<版本>.zip`。Java DEX 不含 Kotlin 运行时或第三方 hook 库，原生引导层只使用系统库；ARM32 和 ARM64 两个 ABI。
- 模块是普通的 `java` 插件工程，测试编译任务为 `:watch:healthmodule:compileTestJava`。
- `verifyModuleZip` 检查 DEX 入口、双 ABI 的 ELF、固定的 Zygisk 头文件来源和安装清单。Zygisk SDK 的来源和许可见 [SDK_PROVENANCE.md](../../android/watch/healthmodule/SDK_PROVENANCE.md)。
- 构建链：`:watch:healthmodule:verifyModuleZip` → 手机生成 assets → release APK。catalog 绑定 ZIP 中每个文件的摘要，APK 检查再从签名后的 APK 中提取它们，与独立产物和编译进 DEX 的 buildId 比较。同一版本号的不同源码有不同的 buildId。

### 3.2 安装接口：`services.healthModule`

```kotlin
val client = services.healthModule
val bundled = client.bundled()     // 只读手机 assets，离线可用，不申请 root
val observation = client.refresh() // 连接当前配对手表的 root Local Bridge
val result = client.installBundled { progress -> /* ZIP 上传进度；100% 后仍需等待结果 */ }
```

| outcome | 含义 |
|---|---|
| `READ` | 已读取磁盘、Magisk 和健康进程的元数据 |
| `STAGED_REBOOT_REQUIRED` | 内置载荷已在 Magisk 的更新目录通过逐文件摘要回读，需要重启手表 |
| `UP_TO_DATE` | 磁盘上已是内置版本，没有重装；不代表运行中的进程已加载 |
| `REJECTED` | 前置条件不满足或 Magisk 明确失败；不表示副作用已回滚 |
| `UNCONFIRMED` | 安装可能已开始或完成；先 refresh，不要自动重试 |

`snapshot` 分别报告：`target`（已验证的 root 设备和连接）、`magiskVersionCode`、`installed` / `staged`（内容身份、逐文件的 `matchesBundled`、禁用 / 移除标记）、`healthRuntimeBuildId`（健康进程内 socket 返回的编译常量）、`runtimeIssue` 和 root 端持久的 `lastInstallBuildId` / `lastInstallOutcome`。**只有** `healthRuntimeBuildId` 等于 `bundle.buildId`、`installed.matchesBundled=true` 且模块未禁用，才证明这一版已经加载。`module_runtime_not_observed` 可能是尚未重启、Zygisk 未开、DenyList 隔离、版本不支持或健康进程还没启动。

- 前提：Android 11+、ARM32 / ARM64、Magisk 26+；Zygisk 和 DenyList 由用户管理。接口只针对当前真实配对的手表（不是高级页选择的任意 ADB 目标），校验物理会话、root 通道代次和 READY 的设备身份。演示手表只在内存中模拟安装。
- 上传到 `/data/adb/ticbox-module-installer/upload-*/module.zip`（4 MiB，解压预算 8 MiB），Agent 检查 ZIP 摘要、每个文件、模块 ID、版本和 buildId，以跨进程文件锁防止并发安装，然后执行 `magisk --install-module ZIP`，成功后回读 `modules_update/ticwatch_health_settings` 的 DEX、两个 ELF 和构建元数据。安装器的输出和退出码都不作为成功依据（Magisk 可能提前更新活动目录的 `module.prop`）。
- 安装前查看活动目录和暂存目录有没有通知控制的关闭记录 `notification-alerts.disabled`；回读通过后，新的暂存副本缺少记录就补上（root，`0600`），补不上时报告 `module_alerts_off_not_kept`。这样关闭的选择不依赖 `customize.sh` 的复制（见[通知的声音与振动 §3.3](../spec/notification-alerts.md#33-开关与模式)）。
- 模块被用户禁用或标记删除时拒绝安装（官方安装器会清除这些标记），同 ID 的其他模块拒绝覆盖。
- 断线或取消时已提交的安装不回滚、不重放；仍在同一会话时 `state.lastOperation` 标为 UNCONFIRMED。
- 常见 reason：`companion_link_required`、`bluetooth_bond_required`、`root_required`、`module_root_required`、`module_bridge_update_required`、`device_changed`、`connection_replaced`、`module_device_unsupported`、`magisk_unavailable`、`magisk_too_old`、`module_disabled_or_removing`、`module_id_conflict`、`module_busy`、`module_payload_unavailable`、`module_payload_invalid`、`magisk_install_failed`、`module_install_unconfirmed`、`module_alerts_off_not_kept`。不要都简化为“重新配对”。

`HealthModuleClient` 本身不重启、不改 Zygisk、DenyList、SELinux 或通知补丁的模式；它只把已有的关闭记录带进新副本。

### 3.3 系统步骤：`services.watchModuleHost`

全部是经 root Local Bridge 执行的固定命令，不需要更新手表应用：

| 方法 | 命令 | 说明 |
|---|---|---|
| `refresh()` | `magisk --sqlite`、`magisk --denylist ls`、`pidof`、`dumpsys package` | Zygisk 设置、健康应用是否在 DenyList、是否运行、两个目标的版本是否受支持 |
| `stayAwake()` | 写 `/sys/power/wake_lock`，5 分钟后自动释放 | 防止熄屏休眠让安装停滞 |
| `turnOnZygisk()` | `magisk --sqlite "REPLACE INTO settings ..."` | 下次开机生效；写后必读 |
| `restart()` | 后台 `svc power reboot`，失败才用 `reboot` | 以连接断开为准，60 秒没断就报错 |
| `restartSystemUi()` | `kill` 系统界面进程，后台循环 `input keyevent KEYCODE_WAKEUP` | 手表和连接不断；之后读取确认是否加载 |

手表熄屏后很快休眠，上传结束时蓝牙也没有流量，`magisk --install-module` 几乎不前进，直到有人点亮手表。root Agent 没有 wake lock，所以手机在上传前经另一条 Agent 连接设置带超时的内核 wake lock（推断的原因，尚未实机验证）。仍然卡住时，`/data/adb/ticbox-module-installer/last-install.json` 为 `UNCONFIRMED` 说明 Magisk 进程没有退出，为 `STAGED_REBOOT_REQUIRED` 说明它退出了而结果没有及时回到手机。

### 3.4 手机界面

入口：连接页、本地桥接页、健康监测页缺少模块的提示、通知页的“声音和振动控制”。[WatchModuleScreen](../../android/ui/src/main/kotlin/dev/ticbox/companion/ui/bridge/WatchModuleScreen.kt) 是一张清单（手表连接、模块、Zygisk、健康应用）、一个“通知声音和振动控制”开关和一个主按钮，逻辑在 [WatchModuleSetup](../../android/ui/src/main/kotlin/dev/ticbox/companion/ui/bridge/WatchModuleSetup.kt)。

- **一次完成**：`modulePlan` 根据手表的回答算出缺少的步骤，确认一次后按顺序完成，只重启一次：保持唤醒 → 上传并安装 → 开启 Zygisk → 按开关删除或写入通知控制的关闭记录 → 重启 → 等连接断开再恢复 → 确认，通知控制加载后还停在 `OFF` 或 `OBSERVE` 时切换到 `ALL`。通知控制默认开启，只有关闭会写记录；关闭时手表上还没有模块，记录在安装后写进暂存目录。流程在应用级作用域中运行，离开页面不中断，进度保存在 `WatchUiServices.moduleSetup`。不需要的步骤被跳过，所以同一个按钮也负责更新、补开 Zygisk 和单纯重启。
- **只改通知开关**：模块本次开机已生效时，只需重启系统界面：删除关闭记录 → 结束系统界面进程 → 手表在后台循环点亮屏幕约半分钟 → 等 20 秒 → 确认，最多再读约 35 秒。不亮屏时系统界面可能不绘制，恢复显示约需 20 秒，页面和确认弹窗都会提示。仍未加载就报错，主按钮回到“重启手表”。安装、更新和开 Zygisk 仍需重启手表。
- 每次连接进入页面读取一次；没有安装 Local Bridge 时不读取。旧版 Bridge 可能把已加载读成未加载，所以每次连接还会读取 Bridge 的 versionCode，比手机旧时清单第一行给出更新入口，并隐藏主按钮。
- 普通刷新只读一轮，刚启动健康应用时再看两次；读取失败时保留上次结果但不提供任何操作，连接断开时清空。Magisk 没有回答显示“没有返回”，不当作未开启。
- 确认弹窗打开时固定目标手表，整个计划只接受该手表重启后的重连；换手表立即终止。安装、主机命令和通知引擎也通过 `WatchModuleTarget` 协程上下文核对目标。
- 自动切换到 `ALL` 的机会在发送前消费；失败、超时或回包丢失后只读，不自动再写。Zygisk 和关闭记录必须由本次读回确认。
- `UNCONFIRMED` 只跟一次 refresh：磁盘上确实有这个版本才继续，否则停下，不重试。Zygisk 没开成或关闭记录没写成、没删成，都停在重启之前。
- 未加载时按 Zygisk、DenyList、健康应用未安装、版本不支持、进程未启动的顺序给出原因；原因明确时不再重复读取，其余情况重启后最多连续读 6 次（约 1.5 分钟）。健康进程未启动时读一次它的设置 Provider，顺带把进程带起来，不强制启动或停止。

### 3.5 停用

在手表的 Magisk 中禁用或移除 `ticwatch_health_settings` 并正常重启。模块不改变官方应用的持久代码或系统安全配置；用户已保存的健康和闹钟偏好是官方设置，卸载时不回滚。健康变更日志是独立的数据库触发器，移除模块不会删除它，需要用变更日志的开关移除。

## 4. 待验证

真实手表上的：Zygisk 加载和 SELinux 下的 socket 通信、官方闹钟权限与提醒行为、wake lock 是否解决安装停滞、只重启系统界面后的加载、经 root 更新 Bridge 的完整过程、Local Bridge 进程被回收后的唤醒。
