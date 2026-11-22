# 连接：配对、首次设置与后台恢复

本文说明手机端如何实现连接相关的工作流。线格式和官方行为见[主传输](../spec/transport.md)、[配对与首次设置](../spec/pairing-setup.md)、[保留数据解除配对](../spec/pairing-unlink.md)和[转移到新手机](../spec/phone-transfer.md)。

## 1. 四件不同的事

| 事实 | 由谁确认 | 不能代替 |
|---|---|---|
| Android 关联与蓝牙绑定 | CDM 回调、实际的 `BOND_BONDED` | 连接成功 |
| 主连接 `LINK_READY` | CONNECT 交换完成，peer 和物理 sessionId 匹配 | 手表已激活、业务端点可用 |
| 首次设置完成 | 手表的 STATUS COMPLETE=3 和（若声明）OOBE_DONE=5 | 未选择的 Google、厂商或云功能已成功 |
| CDM 权限传输 | 系统的传输回调 | 所有远端权限都已授予 |

UI 分别展示这些状态。异常的回复按官方默认值或请求上下文尽量解释并记录观察值、期望值和处理决定；无法归属的包记录后忽略，不主动断开。设备和会话归属、长度和内存预算始终保留。

## 2. 配对

入口为 `services.pairing`（[PairingClient](../../android/core/api/src/main/kotlin/dev/ticbox/companion/api/Pairing.kt)），服务持有 `PairingCoordinator`，Android 适配在 `platform/pairing`。`start(PairingOptions(...))` 返回操作 ID，依次：

1. 检查 CDM 功能、`BLUETOOTH_CONNECT` 的实际授权和蓝牙开关；授权返回后重新读取系统状态。CDM 自己做发现，所以不要求扫描或位置权限，但**手机的系统定位总开关必须打开**（与应用的定位权限无关），界面提示这一前提。
2. 新配对默认 `replaceExistingBond=true`：先停止旧连接和自动恢复，在打开系统选择器**之前**记录已有的绑定设备，再查询本应用的 `myAssociations`。只复用同一设备、WATCH profile 的关联；未指定设备时不猜测。
3. 需要新关联时调用 `associate(AssociationRequest, Executor, Callback)`（经典蓝牙过滤、WATCH profile）。系统选择器的 IntentSender 交给 app 宿主启动；以实际的 `AssociationInfo` 确认结果，MAC 不一致即失败，缺少 MAC 时只用 API 34 的 `AssociatedDevice` 或本次按精确地址限定的请求推断。
4. 保存关联与地址的映射。如果所选设备在选择前就已绑定，而本次系统没有重新配对或加密，就清除旧绑定：API 36+ 用 `CompanionDeviceManager.removeBond(associationId)`；API 34/35 或公开接口拒绝时，请用户在系统蓝牙页忘记该设备再返回，只有实际读到 `BOND_NONE` 才算清除。然后调用 `createBond()` 并等待实际的 BONDED。不拦截 PIN、不自动确认、不使用隐藏 API。
5. 交给服务的 `PairingLink.connectAndAwaitReady(peer)` 建立主连接。
6. 用户选择同步权限时，取得 CDM 权限传输的同意，打开 `/cdm/channel/permission_sync`，交给 `CdmPermissionSync` attach 并 `startSystemDataTransfer`，系统回调成功才算完成。

`PairingSnapshot` 分别记录 `associationObserved`、`preExistingBond`、`oldBondCleared`、`bonded`、`linkSessionId`、`permissionConsent` 和 `permissionTransfer`。拒绝可选的权限同步时，配对仍可完成（`COMPLETED` 且 `permissionTransfer=DECLINED`）。Android 14 的 `buildPermissionTransferUserConsentIntent()` 返回 null 不代表已同意，状态为 `UNKNOWN_SYSTEM_STATE`，交给实际的传输调用检查；API 35+ 使用公开的 getter。

- `syncPermissions(peer)` 使用 `replaceExistingBond=false`，不停止当前连接。正常的 `connect` 和后台重连从不删除绑定。`preExistingBond` 只表示“选择前有系统记录”，不是密钥失效的证据；Android 17 可能自己修复密钥，`ACTION_KEY_MISSING` 只记录。
- `associate(peer)` 给已绑定、正在使用的手表补建本应用自己的关联（不动绑定、不传权限），用于导入配对身份之后（见[数据迁移](data-migration.md)）。手表已连接时直接返回；配对操作带回 associationId 而恢复状态里还没有时，服务触发一次 `restore`，让连接目标记下关联号并开始在场观察。
- 相同 options 的 start 是幂等的，冲突时抛 `PairingBusyException`。`cancel()` 注销观察、停止后续连接，不再发起删除或 disassociate。选择器显示期间的取消在 API 34 上成为 `CANCEL_ASSOCIATION` 动作，下一次关联要等它返回。
- 映射保存在 `noBackupFilesDir/companion/associations-v1.bin`（AtomicFile，只有 associationId、MAC、profile 和观察时间），不随备份迁移。每次运行仍查询 `myAssociations`；不存在的 ID 只删除映射，从不自动 disassociate。

### 2.1 系统界面的宿主

`core:api` 的 `PairingUserAction` 只有不透明的 ID、operationId、动作类型、权限名和原因键；Android 对象留在进程内的 `AndroidPairingActions`。app 调用 `hostActions.prepare(action.id)` 得到描述，再用对应的 ActivityResultContract 启动：

| 描述 | 启动方式 | 回传 |
|---|---|---|
| `AndroidPairingLaunch.Permissions` | RequestMultiplePermissions | `completePermissionsResult(actionId)`，随后重新检查授权 |
| `AndroidPairingLaunch.Sender` | StartIntentSenderForResult | `completeActivityResult(actionId, resultCode, data)` |
| `AndroidPairingLaunch.ActivityIntent` | StartActivityForResult | 同上 |

`prepare` 只能领取一次；Activity 不能启动时 `releaseLaunch`，启动抛异常时 `failLaunch`。`SystemPairingHost` 为每个动作使用独立、可保存的 ActivityResultRegistry key，配置重建后重新注册，旧选择器的晚到结果归旧 key。未绑定时最多保留 8 个待回传结果。进程死亡后 token 不可恢复，新进程重新检查系统状态并创建新操作。

Manifest 需要 `android.software.companion_device_setup`、`REQUEST_COMPANION_PROFILE_WATCH`、`BLUETOOTH_CONNECT` 和 `DELIVER_COMPANION_MESSAGES`。

## 3. 首次设置

`services.setup`（[Setup.kt](../../android/core/api/src/main/kotlin/dev/ticbox/companion/api/Setup.kt)）由服务持有的 `SetupEngine` 实现，在已就绪的主连接上打开 `/setup/channel`：

```kotlin
val probe = setup.probe() // 只读：状态和系统能力
setup.start(SetupOptions(
    termsAccepted = null,                         // 由界面明确作答
    accountChoice = SetupAccountChoice.ASK,
    checkinChoice = SetupCheckinChoice.SKIP,      // 默认不做 Google 设备注册
))
setup.state.value.action?.let { action ->
    setup.resume(action.id, SetupUserResponse(decision = SetupDecision.CONTINUE, termsAccepted = true))
}
setup.cancel() // 只取消本地流程并关闭 Channel，不重置手表
```

- `start()` 被接受后立即返回，工作在服务作用域中进行。`resume()` 只接受当前的 action ID 和允许的决定，过期调用返回 false。必选依赖失败只能重试或取消；可选步骤可以跳过。
- `probe()` 不发送 TIME、OPTIN、tether 或 COMPLETE。
- **Google 设备 check-in**：默认 `SKIP`，即使手表声明 `GMS_CHECKIN` 也不查询、不等待。界面提供默认不勾选的“尝试 Google 设备检查”，并说明会使用手表网络。选择 `ATTEMPT` 后，等待从一开始就暴露 `CHECKIN` 动作（`phase` 仍为 RUNNING），用户可随时 `SKIP_OPTIONAL`；失败或超时后可以重试、跳过或取消。跳过不会把实际状态改写为成功。
- `SetupEnvironment` 接入其他模块：`prepareInitialData`（语言、时间制式、手机 SDK、明确的定位选择、条款审计）、`awaitDataSyncCompletion`（只在 `awaitDataSyncCompletion=true` 时等待 GMS 的数据同步屏障）、`transferGoogleAccount` 和 `runOptionalStep`。没有实现的依赖返回 `UNAVAILABLE`，不伪造完成。
- `SetupSnapshot` 分别记录 `dataPreparationCompleted`、`dataSyncCompletionObserved`、`watchCompleteObserved` 和 `watchOobeDoneObserved`。
- `CHANNEL_CLOSED` 表示设置流结束时主连接仍然就绪，界面提供“重试设置”，不要求重新配对；`LINK_LOST` 才表示主连接断开。
- 日志事件（`query_started`、`watch_status_received`、`workflow_completed` 等）只含命令、数值状态、能力和连接阶段，不含账号、运营商或原始报文。

步骤顺序、超时和兼容推断见[配对与首次设置 §4–§6](../spec/pairing-setup.md#4-setup-channel)。

## 4. 会话与 Channel 引擎

[SessionEngine](../../android/core/engine/src/main/kotlin/dev/ticbox/companion/engine/SessionEngine.kt) 拥有唯一的物理 RFCOMM 会话，实现 `ChannelBackend`；[ChannelMultiplexer](../../android/core/engine/src/main/kotlin/dev/ticbox/companion/engine/ChannelMultiplexer.kt) 管理每个物理会话内的 Channel，[ChannelConfig](../../android/core/engine/src/main/kotlin/dev/ticbox/companion/engine/ChannelConfig.kt) 给出官方的重试默认值和本地的资源上限。

| 方法 | 语义 |
|---|---|
| `openChannel(path)` | 等到匹配的 OPEN_ACK 才返回 |
| `acceptChannel()` | 当前物理会话的下一个入站 Channel |
| `read(maxBytes)` | 非空字节或 EOF；不保留块的边界 |
| `write(bytes)` | 分块发送，逐块等 ACK；ACK 不是业务完成 |
| `finishOutput()` / `closeInput()` | 只结束一个方向 |
| `closeGracefully(errorCode)` | 拒绝新写入，排空已开始的写后发送 CLOSE |
| `close()` | 立即终止本地等待，尽力发送 CLOSE，不截断 RFCOMM 记录 |

句柄包含 peer、sessionId、channelId 和本端是否为 opener；断线后旧句柄失效，重连后必须重新 OPEN。本地选择（不是官方行为）：直接的 `read` 在完整的块交给读取方之后才 ACK；发送块 64000 字节，最多 16 条活动 Channel、每会话最多 4096 次本地 OPEN；重试从本地写完 RPC 开始计时；对重复的、完全相同的 DATA 重传补发原 ACK。[WearChannelStreams](../../android/core/platform/src/main/kotlin/dev/ticbox/companion/platform/transport/WearChannelStreams.kt) 把 Channel 适配为阻塞的 Java 流，读写各自串行，中断转换为 `InterruptedIOException`。

原始 Message 观察流是有界、非阻塞的，满时丢弃新事件并计入 `droppedMessageEvents`；业务处理由 `RoutedMessageSource` 按路由分派到各处理器自己的有界队列，溢出、异常和超时计入 `failedBusinessMessages`。断线或注销取消队列，不自动重放有副作用的消息。

## 5. 后台恢复

`services.recovery`（`ConnectionRecoveryClient`）保存用户“与这块手表保持连接”的选择。服务重建、蓝牙重新开启、开机、应用更新和 CDM 的在场事件都经同一个协调器交给 SessionEngine；协调器没有定时器，也没有第二套重连循环。

| 阶段 | 含义 |
|---|---|
| `IDLE` | 没有保存的选择，或用户已暂停（`paused=true` 时保留 desiredPeer） |
| `RESTORING` | 正在读取意图并核对系统环境 |
| `RUNNING` | 已交给 SessionEngine；是否 `LINK_READY` 看 WatchClient |
| `WAITING_BLUETOOTH` | 蓝牙关闭，开启后恢复 |
| `WAITING_PERMISSION` | 需要蓝牙权限，界面授权后调用 `resume()` |
| `WAITING_ASSOCIATION` | 保存的 CDM 关联已不存在 |
| `WAITING_BOND` | 系统绑定不存在，需要配对工作流 |
| `WAITING_START_ALLOWED` | 系统拒绝启动前台服务，等待可见界面或下一个合法事件 |
| `FAILED` / `CLOSED` | 存储或环境操作失败 / 服务对象已销毁（意图仍保存） |

恢复不会重新执行 Setup、创建绑定、弹出权限界面或上传健康记录。

- **持久化**：`AtomicFileConnectionIntentStore` 在 `noBackupFilesDir/companion/connection-intent-v1.bin` 保存至多 512 字节：蓝牙地址、可选的 associationId、本地随机 revision 和 `reconnectEnabled`，提交后回读验证，不进入系统备份。
- **断开与解除**：普通断开保留 peer 和关联，写 `reconnectEnabled=false` 和新的 revision，然后释放连接和在场观察；后台事件、绑定、包更新和进程重建都尊重这个开关，只有 `resume()` 或 `connect(peer)` 重新启用。显式解除配对用 `forget()` 写 `peer=null`。revision 用于忽略断开或切换前就已排队的旧唤醒。写盘失败时仍释放连接，报告 `disconnect_persistence_failed` 和 `durable=false`。
- **手表终止关联**：收到 `TERMINATE_ASSOCIATION` 时引擎以 `ASSOCIATION_TERMINATED` 失败并停止重试；服务持久暂停该 peer，停止在场观察和相关工作，不清除身份、健康库、绑定或 CDM 关联。协调器在被动唤醒时也直接检查引擎的这一失败，避免状态还没写盘就再次连接。
- **已有手表**：没有保存的 peer 时，协调器只读查询本应用的 CDM 关联并核对权限、开关和绑定，结果发布为 `pairedWatches`。欢迎页列出它们供用户点击重新连接，不自动连接，不触发新配对或 Setup。读取错误报告 `SAVED_CONNECTION_UNREADABLE`，不显示成首次使用。
- **服务**：`BackendSessionService` 返回 `START_STICKY`；以 null Intent 重建时先发布 connectedDevice 通知，再读取意图，没有意图就停止。运行时动态注册蓝牙状态和绑定状态的广播（`RECEIVER_EXPORTED`，只消费系统保护的广播）；蓝牙关闭时停止 worker，保留意图和服务。
- **广播**：`ConnectionRecoveryReceiver` 处理 `BOOT_COMPLETED`、`MY_PACKAGE_REPLACED` 和蓝牙开启。用普通开机广播，因为保存位置在用户解锁后才可用。Manifest 广播能否冷启动进程取决于系统和厂商。
- **CDM 在场**：`WatchPresenceService`（`exported=true`，`BIND_COMPANION_DEVICE_SERVICE`）只把匹配当前选择的事件交给会话服务；消失事件只触发重新核对。API 34/35 使用 `startObservingDevicePresence(deviceAddress)`，API 36+ 使用 `ObservingDevicePresenceRequest.Builder().setAssociationId(id)`。只观察本应用已有的关联，不请求 UUID 观察的特权权限。

权限：`RECEIVE_BOOT_COMPLETED`、`REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE`、`REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND`、`FOREGROUND_SERVICE_CONNECTED_DEVICE`。不申请电池优化豁免。Android 不保证在用户强行停止、厂商限制、权限撤销或没有在场信号时一直运行。依据：[后台启动前台服务的限制](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)、[前台服务类型](https://developer.android.com/develop/background-work/services/fgs/service-types#connected-device)、[CompanionDeviceManager](https://developer.android.com/reference/android/companion/CompanionDeviceManager)。

## 6. 断线排查

- 手机上看到的“断开”可能来自三层：蓝牙绑定 / ACL / socket、主连接的 CONNECT 和 Channel、业务请求是否收到确认。诊断页和 `TicBoxProtocol` 日志分别记录它们。
- 官方 GMS 封装层的 30 秒是 API 连接超时，不是手表的蓝牙监督超时或保活周期。Channel 的 ACK 重传、RFCOMM 写超时、节点刷新和业务确认是不同的计时器。
- 重连发现手表仍处于迁移模式时，引擎在新连接上协商迁移标记，查询手表账号并等待空列表处理的回执，然后沿用当前物理连接恢复业务；确认前保持 `NEGOTIATING`。见[转移到新手机 §4](../spec/phone-transfer.md#4-本应用的时序)。
- 短暂的 CONNECT 成功后又断开不会重置退避。
- 排查重连时用 `am force-stop` 暂停进程即可（不清除自动连接目标）；不要卸载或 `pm clear`，否则会丢失连接身份。
- 保留数据的远端解除配对（`services.unpair`）是另一个操作，只有后端接口，见[保留数据解除配对](../spec/pairing-unlink.md)。

## 7. 待验证

真实设备上的系统选择器与 CDM 回调、厂商的 WATCH profile 策略、权限传输的端到端结果、sticky 重建、广播投递、API 34/35/36 的在场事件、厂商的后台策略，以及重复迁移后免重启恢复。
