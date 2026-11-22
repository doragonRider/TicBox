# 解除配对与迁移

本文规定保留手表数据的解除配对、转移到新手机的流程，以及迁移状态下的连接协商。手机换机的完整时序见[官方换手机流程](phone-transfer.md)。

手表上存在一个保留数据、跳过恢复出厂的解除配对实现，但它复用了默认会恢复出厂的旧消息入口。因此客户端必须先确认手表 WCS（Wear Companion Service，包名 `com.google.android.wearable.app`）的版本，才能使用这个操作。`phone_switching` 是另一个流程，它的回复不能当作解除配对完成，也不能证明旧入口支持 `skipWipe`。需要重新配对时，应使用第 5.4 节的系统迁移入口。

## 1. 四种不同的操作

| 操作 | 行为 | 不代表 |
|---|---|---|
| 本地 `WatchClient.disconnect` | 关闭本地会话，暂停本地重连 | 手表已解除关联 |
| `companion_unpaired`，`skipWipe=true` | 手表移除连接配置和伴侣蓝牙配对，设为未配对状态，跳过恢复出厂 | 收到 ACK 即已全部清理完成；任何 WCS 版本都支持该字段 |
| `enable_phone_switching` | 进入等待新手机的转移流程，需要手表有 Google 账号等前提 | RPC 返回 `COMPLETE` 时旧手机已解除关联 |
| 官方手机 App 的普通“解除配对” | 默认发送 `skipWipe=false`，会恢复出厂 | 与无重置入口相同 |

**重新配对应优先使用系统迁移确认，而不是先删除手机配对再搜索。** 手机通过 `deviceSettings.openSettings(WatchSettingsScreen.TRANSFER)`，用 Remote Intent 打开手表的 `android.settings.WATCH_TRANSFER`。这一操作不发送 `companion_unpaired`，不清理手机配对和 CDM 关联，也不主动断开连接；确认在手表上完成。返回 `REMOTE_UI_OPENED` 只表示页面已打开，迁移和新手机配对需要另行观察。无重置解除配对是另一项独立的显式操作，不会自动与迁移串联。

官方手机 App 的转移入口在手表有 Google 账号等条件下，保留旧连接，等新手机迁移成功后才清理。“无重置解除配对 → 打开迁移页 → 同一部手机重新添加”与官方流程并不等价，见[官方换手机流程](phone-transfer.md)。

面向独立（standalone）模式的 `detach` 只关闭蓝牙、禁用连接配置，见第 7 节。

## 2. 无重置解除配对的消息

### 2.1 路由

消息使用当前配置的伴侣 AppKey（手表默认配置下）：

```text
package = com.mobvoi.companion.at
digest  = 5cc9866db03e530a21f842a421c7eb42bac58961
```

这与手表上实际接收的包 `com.google.android.wearable.app` 不同。WCS 是 GMS 的默认 home 应用，它的出站消息和 DataItem 经 GMS 映射为伴侣 AppKey；入站的伴侣消息按路径第一段分派，`companion_unpaired` 由 WCS 处理。不要把 WCS 的本地包名直接当作线上路由。路由规则见[身份与权限](identity.md#4-oem-home-与-trustedpeers-路由)。

### 2.2 路径与字段

这是 `MessageClient.sendMessage` 类型的消息，ACK 另行发送，不是返回结果的 `sendRequest`：

```text
请求 = /companion_unpaired/rpc
ACK  = /companion_unpaired/ack
```

```proto
// 两个字段都有独立的 presence，默认值为 false。
message CompanionUnpairedPayload {
  optional bool erase_esim = 1;
  optional bool skip_wipe = 2;
}
```

客户端必须固定、显式地发送：

```text
erase_esim = false（字段存在）
skip_wipe  = true（字段存在）
payload    = 08 00 10 01
```

不得省略任一字段，也不得向调用方暴露可以随意修改这两个值的通用重置接口。

### 2.3 旧默认分支的风险

WCS 对空正文直接执行旧的恢复出厂分支，之后才解析非空的 protobuf。非空消息缺少 `skip_wipe` 时默认值也是 false，同样会恢复出厂。旧版本若不认识字段 2，可能忽略它而按 false 执行。不得为了兼容而退回空正文、删除字段 2 或改用普通解除配对。

不得在 `/companion_unpaired` 下猜测 `/supports`、`/version` 之类的探测路径。WCS 注册的是前缀，处理器不再检查完整路径，猜测的消息可能进入同一个恢复出厂的处理器。

## 3. 只对已确认的 WCS 版本使用

### 3.1 读取 HomeInfo

WCS 发布只读的 DataItem：

```text
path  = /settings/home_info
route = 当前配置的伴侣 AppKey
```

| DataMap 字段 | 类型 | 已确认支持 `skipWipe` 的值 |
|---|---|---|
| `HOME_VERSION_CODE` | int32 | `790427547` |
| `HOME_VERSION_NAME` | string | `1.15.81.626317553` |
| `BUILD_FINGERPRINT` | string | 仅用于诊断 |
| `BUILD_NUMBER` | string | 仅用于诊断 |
| `WEAR_ANDROID_SDK_VERSION` | int32 | 只记录平台信息，不能单独证明支持；换手机流程用它识别不支持迁移的 API 30 |

`HOME_VERSION_CODE` 和 `HOME_VERSION_NAME` 是 WCS 查询**自身安装包**得到的版本，不是 Mobvoi 健康应用或 GMS 的版本。

### 3.2 准入规则

以下规则只用于无重置解除配对，其他功能不受这份版本名单约束：

1. 使用当前会话的目标对端、已确认的路由和准确的 `/settings/home_info` 路径。可以主动请求 Data Layer 重放这一项，收到后再判断；不得向 `companion_unpaired` 前缀发送探测消息。
2. 不复用其他对端、上一次会话或其他路由的本地缓存。重新连接、换了对端、该项被删除或版本值变化时，重新判断。
3. `HOME_VERSION_CODE` 和 `HOME_VERSION_NAME` 必须类型正确，并同时与上表完全一致。缺失、损坏、不一致或读取超时，一律返回 `Unsupported`，普通的本地断开仍然可用。
4. 不使用“最低版本 ≥”的猜测，也不把 Android 33、SystemInfo version 4、`phone_switching` 能力、手表型号或 GMS 版本当作支持字段 2 的证明。新版本必须在确认其接收实现后才能加入名单。
5. 实际发送前再次确认判断结果仍属于当前对端、路由和会话，避免等待期间换表或重连后误用旧结果。

HomeInfo 是软件自报的持久数据，不含密码学证明。版本码与版本名联合匹配比只看版本码更精确，但无法证明被修改过、却保留相同版本声明的 APK 与已分析的实现一致，不能把这条规则描述为远端认证。

读取 HomeInfo 时可能触发一批 Data Layer 重放。数据层必须对突发重放做有界背压，不能因为队列满而断开连接，否则检查会以“中断”结束而不是“不支持”。`probe()` 返回该次检查自己的结果；连接已切换到新会话时，不用新会话的 UNKNOWN 覆盖旧检查的失败。手机分别提示已支持、版本未验证、检查中断、超时和读取失败。

本地 Binder 的 `WcsInfo` 只列出各客户端 API 的版本，没有无重置解除配对的字段；手表上也没有独立的 `UNPAIR_WITHOUT_FACTORY_RESET` 之类的 Intent 或专用能力。更早的 WCS 版本未经分析，最低支持版本未确认。

## 4. ACK、清理与结果

### 4.1 ACK 先于执行

WCS 收到消息后，先向来源节点发送空的 `/companion_unpaired/ack`；只有这次发送成功，才解析正文并执行操作，发送失败只记录。官方手机端等待 ACK 时只核对路径和来源节点，不解析正文，10 秒未收到即返回失败。因此：

- 收到 ACK 可以记为 `Acknowledged`，不能记为“远端清理已全部完成”。
- ACK 之后 protobuf 解析仍可能失败，配置清理也可能失败或尚未结束。
- 断线或 ACK 超时可能发生在执行之后，应记为结果未确定，不得伪造成功，也不得自动退回会恢复出厂的操作。
- ACK 没有操作 ID；同一对端不得并发发送多个解除配对请求，以免 ACK 对应错误。

如果收到 ACK 后立即关闭 RFCOMM 并删除手机配对，可能切断手表的执行。本实现收到 ACK 后进入 `WAITING_FOR_WATCH`：等待该物理会话结束，最多保留连接 5 秒，然后再做本机清理。这个窗口在界面取消后仍会完成，正常重连入口在此期间返回忙；会话被替换不影响清理目标。5 秒参考了官方手机端延迟删除配对的时间，不能证明远端已完成。诊断分别记录 `request_written`、`ack_received`、等待结束的原因和本机清理的最终结果。

### 4.2 手表的无重置分支

`skipWipe=true` 时，WCS：

1. 退出云同步（`cloud_sync_opt_in_action=2`）。
2. 异步获取所有连接配置，逐个移除有名字的配置（不按当前对端筛选）。
3. 有蓝牙权限时，移除保存的伴侣地址对应的蓝牙配对。
4. 设置 `paired_device_os_type=0` 后返回，不进入恢复出厂或 eSIM 重置分支。

这一分支没有恢复出厂广播、卸载应用、清除应用数据或删除健康数据库的操作，可以确定跳过了恢复出厂；但它不保证所有厂商应用在随后断线或账号变化时都不改变自己的私有数据。应描述为“解除配对并跳过恢复出厂”，不承诺每个第三方应用的状态都不变。成功后 `device_provisioned` 和 `user_setup_complete` 保持为 1，官方健康应用中的历史记录保留。

### 4.3 官方手机端的本地清理

官方手机端的无重置入口先发请求，然后移除本机的连接配置和 CDM 关联，默认等待 5 秒，再删除这块手表的蓝牙配对。RPC 超时或失败后它仍继续本地清理；调用时手表未连接则直接返回失败。结果字段 `wasWatchFactoryReset = ackSuccess && !skipWipe`，无重置操作成功时本来就是 false，不能据此判断失败。客户端应分别记录：已发送、收到 ACK、远端清理能否确认、本地清理结果。

## 5. 转移到新手机

### 5.1 消息

```text
能力 = phone_switching
请求 = /enable_phone_switching/rpc/enable_phone_switching_request
回复 = /enable_phone_switching/rpc/enable_phone_switching_response
路由 = 当前配置的伴侣 AppKey
```

```proto
message EnablePhoneSwitchingRequest {
  optional bool require_user_confirmation = 1;
}
message EnablePhoneSwitchingResponse {
  optional Status status = 1;
  enum Status {
    UNKNOWN = 0;
    COMPLETE = 1;
    LOW_BATTERY = 2;
    USER_DECLINED = 3;
    PENDING = 4;
  }
}
```

需要手表确认时发送 `08 01`。第一次用 `sendRequest` 取得同步回复；回复为 `PENDING` 时，后续结果通过独立的回复路径以 Message 发送。缺省的 `UNKNOWN` 或未知值都不算成功。必须先注册后续消息的监听，并限定当前对端、路由和会话。

官方手机 App 的“开始”按钮发送字段 1 为 false（`08 00`），满足条件后手表直接执行转移；需要确认、异步 90 秒等待的分支也受支持，但不是官方界面的必经路径。

### 5.2 前提与确认

手表只在 `persist.sys.phone_switching_supported=true` 时注册该 RPC，并据此发布 `phone_switching` 能力。请求还要求 `paired_device_os_type=1` 且手表上有 Google 账号；`require_user_confirmation=false` 同样受账号条件限制。没有账号时返回 `UNKNOWN`，不会开启转移。

官方手机 App 的转移选项默认隐藏，只有 SDK 报告 `AVAILABLE` 时才显示；SDK 同时检查能力、手机 GMS 的支持开关和手表的账号数量，没有账号时为 `NO_GOOGLE_ACCOUNT`。

电量不足时返回 `LOW_BATTERY`。需要确认时，手表打开 `android.settings.WATCH_TRANSFER`（带 `setTimer=true` 的包装页）并立即返回 `PENDING`；用户拒绝时为 `USER_DECLINED`，接受且转移开始后异步发送 `COMPLETE`。官方手机端等待确认 90 秒。账号检查不在“需要确认”的分支内，不能通过改为 false 绕过。

### 5.3 `COMPLETE` 不代表已经解绑

手表开始转移时设置 provision/setup 标志，令 `phone_switching_status=1`，启动设置向导，之后开启可发现模式并把状态置为 5。此时旧手机的配对和配置都还在，可以取消转移。

只有新手机完成账号匹配和迁移后，手表 GMS 才向旧手机发送 `TERMINATE_ASSOCIATION`，开始移除旧配置和旧配对。官方手机 SDK 收到 `ASSOCIATION_TERMINATED`（状态 6）后停止 CDM 的设备在场观察并标记转移完成，并不直接删除 CDM 关联。旧手机的 GMS 先发布状态 6 再清理配置和配对，所以状态 6 也不能代替观察到 `BOND_NONE`。取消状态为 -9。

因此界面可以把这一功能叫作“转移到新手机”，但必须区分等待确认、已进入转移模式、等待新手机、已完成和已取消，不能把进入转移模式当作解除配对成功。手表处理损坏的 protobuf 时可能仍然开始转移，不要用损坏的消息探测支持情况。

### 5.4 系统迁移确认页

手表设置把 `android.settings.WATCH_TRANSFER` 分派到换手机页面。该页检查最低电量；普通启动不带 `setTimer=true`，用户确认后由页面自己开始转移，不依赖旧手机处理返回结果。它启用设置向导、修改 provision、setup 和换手机标志并打开迁移向导，不会恢复出厂，也不删除健康数据库。

系统设置菜单中的换手机项另外以支持属性、`paired_device_os_type=1` 和存在 Google 账号为显示条件。菜单被隐藏不代表这个标准 Intent 不存在；页面本身的确认路径不检查账号，但这不能保证新手机后续的迁移和账号匹配一定成功。

已连接时，手机通过蓝牙 Remote Intent 打开这个页面，不需要 USB。调试时也可以用 ADB：

```bash
adb shell am start -W -a android.settings.WATCH_TRANSFER
```

普通启动保持默认的 `setTimer=false`，不要给这条命令或 Remote Intent 加 `setTimer=true`；后者只让确认页返回结果，由包装页收到后开始转移，直接启动时没有这个调用方。

已配对手表蓝牙设置中的“Pair new devices”是扫描外围设备，不会开启伴侣配对广播。回到手机 App 添加手表之前，必须先在手表上确认系统迁移并进入配对流程。它与“Disconnect & reset”是两个不同的入口。

确认后，手机可以发现并开始配对。如果第一次 Setup 很快结束而手表仍停在“Continue on phone”，可在手表上取消（显示“Transfer aborted”），然后重新确认双方的配对码，连接即可恢复，原有健康记录保留。新的 Setup 诊断会记录手表状态、COMPLETE/OOBE 观察和 check-in 选择，便于定位。

### 5.5 迁移标志协商与无账号分支

手表 GMS 先发送自己的 CONNECT，再检查对方的字段 9（isMigrating）是否与本端配置一致，不一致时报告 `Migration status mismatch between watch and phone` 并关闭 RFCOMM；迁移时的 CONNECT 在字段 10 中携带旧手机的节点。手表已回到表盘、`phone_switching_status=0`，并不代表 GMS 的接收配置已经退出迁移状态。手机收到手表的 CONNECT 也不代表手表接受了手机的 CONNECT。

迁移连接先只开放控制面，等待账号处理：手表收到空的 `REMOVE_ACCOUNTS` 列表时完成等待并发送操作 4 确认，不删除账号。随后迁移流程准备 DataItem、恢复数据面、记录新手机的节点，把配置改为普通服务端并移除迁移配置；账号确认与迁移流程最终完成之间可能有间隔。

本实现（[SessionEngine](../../android/core/engine/src/main/kotlin/dev/ticbox/companion/engine/SessionEngine.kt)、[AccountlessMigration](../../android/core/engine/src/main/kotlin/dev/ticbox/companion/engine/AccountlessMigration.kt)）：

1. 默认发送普通 CONNECT。只有对端 CONNECT 报告迁移状态不一致时，才关闭这条物理连接，在下一条连接上按观察到的字段 9 重试；不在同一条流上追加第二个 CONNECT，也不更换持久的 nodeId 和 networkId。
2. 迁移连接保持 `NEGOTIATING`，暂不启动 Channel、DataApi 和电话等业务。通过 WearMessage.20 发送账号操作 1，等待操作 2；只有手表实际报告账号列表为空时，才发送操作 3 的空列表，并等待操作 4。账号列表非空或无法解读时，报告需要账号匹配，保留账号，不发送删除请求；未知的控制类型或操作值只记录并继续等待。失败、取消、远端错误和提前到达的操作 4 都不算完成。
3. 收到有效的操作 4 后，沿用当前的迁移连接恢复业务（与官方一致），保留原有的解码器、分片组装器和串行写入方，不主动断开后再用普通 CONNECT 验证。确认之前收到的业务帧在 CONNECT 阶段的数量和字节预算内暂存；确认所在批次的控制消息全部处理后，才进入 `LINK_READY` 并按原顺序交出这些帧。超出预算时明确失败；确认前不推进 DataApi 游标、不启动业务。之后的真实断线按普通 CONNECT 恢复，健康库和客户端身份不清除。操作 4 只确认账号处理，不代表手表的异步收尾已经完成。
4. 本地预算：迁移交换最多 20 秒，账号查询每 3 秒重试、最多 4 次，只重发没有副作用的查询。短暂的 `LINK_READY` 后立即 EOF 不重置退避；普通连接持续 30 秒后才清零失败计数。

控制类型 1（`TERMINATE_ASSOCIATION`）在 CONNECT、账号协商和业务阶段都产生 `FailureKind.ASSOCIATION_TERMINATED`，保留对端和 sessionId，停止自动重试；类型 4、6 以及账号取消或错误都不算成功。服务据此持久暂停恢复并停止在场观察，不清理身份、健康库、蓝牙配对或 CDM 关联；生命周期唤醒同样检查这一失败，过期会话的失败不会暂停之后选择或连接的会话。只有用户显式重新连接才再次尝试。

协议向量见 [MigrationProtocolTest](../../android/core/protocol/src/test/kotlin/dev/ticbox/companion/protocol/MigrationProtocolTest.kt)，会话夹具见 [SessionMigrationTest](../../android/core/engine/src/test/kotlin/dev/ticbox/companion/engine/SessionMigrationTest.kt)，恢复夹具见 [RecoveryCoordinatorTest](../../android/core/engine/src/test/kotlin/dev/ticbox/companion/engine/recovery/RecoveryCoordinatorTest.kt)。非空账号列表的匹配未实现。

### 5.6 迁移后手表不接受连接

迁移完成后，手表的 GMS 可能处于这样的状态：持久配置已是普通服务端（`Migrating=false`），但运行中的监听器没有晋升为主配置（`mainConfig` 为空，监听线程 `shouldAccept=false`），手机的 CONNECT 一直得不到回复。单纯关闭再打开手表蓝牙不能恢复；正常重启手表后，GMS 重新初始化监听，连接即可恢复，健康数据同步也随之恢复。原因和规避方法见[官方换手机流程](phone-transfer.md)。

## 6. 官方普通“解除配对”会恢复出厂

官方手机 App 设置页的“解除配对”经确认对话框后调用 `unpairWatch`，默认参数为 `eraseEsim=false`、`skipWipe=false`、`delayBondRemoval=true`；界面文字也写着 `Unpair & reset` / `Reset watch?`。只有专用的 `unpairWithoutFactoryReset` 才是无重置入口。

## 7. Standalone detach

手表还定义了无正文的 `sendRequest` 路径 `/detach/rpc/detach_connection_request`，只在独立模式开启时注册。处理器先回复空响应，等伴侣断开后关闭蓝牙、设置 `cw_bt_settings_pref=0`、禁用所有可见的连接配置，并广播 `ACTION_DETACH_COMPLETE`（禁用失败时也会广播）。

它不恢复出厂，但也不删除配对和配置记录，因此不能作为无重置解除配对的替代。
