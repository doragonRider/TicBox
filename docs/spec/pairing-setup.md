# 配对与首次设置

本文规定手机如何从一块新手表进入可用的本地伴侣会话：系统关联、蓝牙配对、主连接和 Setup。底层格式见[主传输](transport.md)，身份和系统权限见[身份与权限](identity.md)。实现位于 [PairingCoordinator.kt](../../android/core/engine/src/main/kotlin/dev/ticbox/companion/engine/pairing/PairingCoordinator.kt)、[SetupEngine.kt](../../android/core/engine/src/main/kotlin/dev/ticbox/companion/engine/setup/SetupEngine.kt)，字段见 [Setup schema](../../schemas/setup-channel-reference.proto)。

## 1. 各阶段的完成条件

| 阶段 | 完成依据 | 不代表 |
|---|---|---|
| 系统发现与关联 | 本应用拥有的 AssociationInfo 和所选设备 | 已配对或已激活 |
| 蓝牙配对 | 系统报告 `BOND_BONDED` | RFCOMM、CONNECT 或 Setup 完成 |
| 主连接 `LINK_READY` | 本客户端已接受同一对端、同一会话的 CONNECT | 手表的所有业务服务都已就绪 |
| Channel OPEN_ACK | Channel 状态机接受了 OPEN | SetupWizard 已接管，或业务成功 |
| Setup 完成 | 手表回复 STATUS 3（COMPLETE），或之后的 5（OOBE_DONE） | 账号、云、权限或 OTA 已完成 |
| 手表显示已连接手机 | 手表接受了 `handle_remote_intent` 的可达节点 | 所有健康数据或长期恢复都已正常 |

已有的正常身份和配对应保留。重复清库、重置手表或删除节点身份都不是通用的重试方法。

## 2. 发现、关联与配对

手机最低 Android 14（API 34）。后台服务持有 PairingCoordinator；Activity 只接收不透明的 actionId 并启动系统界面，结果回到对应的操作。后端每次都重新读取实际的权限、蓝牙、关联和配对状态，不信任界面传来的布尔值。

正常顺序：

1. 取得 `BLUETOOTH_CONNECT`；蓝牙关闭时请用户通过系统界面开启。
2. 记录选择设备之前已有的配对。
3. 通过 CDM 关联并选择设备，记录本应用的关联映射。
4. 新配对时，按第 2 步的快照清理所选设备在选择前就存在的旧配对。
5. 调用公开的 `createBond` 并等待 `BONDED`。
6. 建立主 RFCOMM 连接并交换 CONNECT。
7. 可选：CDM 权限传输（第 7 节）。

默认使用 WATCH profile；对同一设备且选项允许时，复用本应用已有的关联。由 CDM 负责发现时，不需要应用自己申请扫描或定位权限。

**手机的系统位置服务总开关必须开启**，否则 CDM 的设备列表为空。这与授予本应用定位权限是两回事。诊断信息应记录位置开关和过滤器配置，但不自动修改系统设置。

本实现使用经典蓝牙过滤器，可附加精确地址或名称，由 CDM 系统选择器显示。下面的 BLE 广播格式供调整发现方式时参考，当前代码没有使用。

### 2.1 官方 BLE 广播过滤格式

manufacturer companyId 为十进制 224（`0x00e0`）。以下偏移从 manufacturer **内容**开始，不含 AD 长度、类型和 companyId：

| 过滤项 | data | mask |
|---|---|---|
| 格式 14，Mobvoi 组 91 | `00 14 00 00 00 00 5b` | `00 ff 00 00 00 00 ff` |
| 格式 13，Mobvoi 组 91 | `00 13 00 00 00 00 5b` | `00 ff 00 00 00 00 ff` |

匹配条件为 `(received[i] & mask[i]) == (data[i] & mask[i])`。偏移 0、2–5 不限定；组号 91 不是密钥。不要求设备名称，也不需要猜测配对用的 service UUID。

普通广播内容共 17 字节：0=`08`，1=`14`，2=`00`，3 的低 2 位为全球版 1 / 本地版 3，4 的 bit1 表示有配对历史，5=`01`，6=组号 `5b`，7=配对系统与换手机标记（低位），8=能力版本 1，9–10=`07 12`，11–16=经典蓝牙 MAC（按打印顺序的 6 个字节）。偏移 7 会被定时刷新。Fast Pair 是独立入口，普通设置不需要。

### 2.2 系统回调与取消

`onAssociationCreated` 和 Activity 结果可能对同一次选择各报告一次，接受其中一个实际结果即可。已知 MAC 与所选设备不符时不得关联；缺少 MAC 时，优先使用 API 34 的 AssociatedDevice 或本次的精确地址，并记录来源，不能随便取某个已关联的设备。映射只保存本应用的 associationId、MAC、profile 和观察时间，每次都用 `myAssociations` 重新核对。

先注册配对状态监听，再读取旧配对快照并调用 `createBond`，以免漏掉很快到来的回调。新配对默认清理所选设备在选择前已经存在、且系统没有重新配对的旧配对：API 36+ 使用公开的 `CompanionDeviceManager.removeBond`，API 34/35 请用户在系统设置里“忘记设备”，状态实际变为 NONE 才算删除成功。选择器本轮新建的配对予以保留。正常的后台重连和权限同步不删除配对；“选择前已有配对”不能说明密钥已失效。只有实际 `BONDED` 后才继续；不拦截 PIN、不自动确认、不调用隐藏 API。取消会停止后续步骤，但无法撤回系统已受理的删除；系统配对对话框由系统和用户结束。平台细节见[连接与恢复](../dev/connection.md)。

每个界面动作绑定 operationId 和注册表实例。过期或重复的结果不推进新的操作；API 34 上取消关联选择器使用它自己的 `cancellationIntentSender`，以免迟到的取消影响新的选择器。进程被杀后重新查询系统状态并重建操作，旧的内存 token 不能当作授权。服务不持有 Activity。

## 3. 手表的初始配置与可选 GATT

手表在正常配对完成后关闭配对服务，记录伴侣地址和 `paired_device_os_type=1`（Android），随后创建启用的服务端配置：type 1、role 2、dataItemSyncEnabled=true、restrictions=null、isMigrating=false。手机 SDK 的本地配置是 type 5、role 1。两边都是**本地配置**，数值不必相同，CONNECT 中也没有对应的 type、role 字段。手表的 GMS 在 type 1/5 服务端监听主 UUID。

普通 Android 入口不需要在配对前写入 OEMData 或 PairingCapabilities；能力保持默认 0/0 时，配对完成后即可直接完成手表端的配对流程。CTKD 由系统蓝牙栈处理，应用不需要手写 CTKD 数据包。

以下 GATT 服务属于可选或其他入口，供识别协议用，正常流程不应无条件写入：

| 用途 | UUID | 注册值 |
|---|---|---|
| 配对主服务 | `1c81447b-f48d-4d17-a606-3ff36527045a` | serviceType 0 |
| 配对状态特征 | `6fcfb474-ce57-48ff-a4ce-b43767d6d04a` | properties 18、permissions 4 |
| 能力特征 | `b2ae0493-d87f-475c-b656-5840e0a13fc8` | properties 8、permissions 16 |
| 状态 CCCD | `00002902-0000-1000-8000-00805f9b34fb` | permissions 17 |
| Fast Pair RFCOMM | `df21fe2c-2515-4fdb-8886-f12c4d67927c` | 与主 WearableBt 不同 |

能力特征写入 `uint16_le N || N 字节 protobuf`：field1=int32 companionOs，field2=int32 companionBleRole，缺省都为 0，取值 0/1/2。OS 为 1 时进入 iOS 等待分支，为 2 时接受但不走该分支；role 各值的名称未确认。长度只要求不少于 N+2，多余字节忽略。`00 00` 是空 proto，`04 00 08 02 10 02` 表示 2/2。状态特征可读和通知，1 字节，BLE 掩码 1、经典蓝牙掩码 2；它按设备类型和配对状态设置，不能证明两套链路密钥都存在。

## 4. Setup Channel

在已 `LINK_READY` 的主会话上，用有效的伴侣 AppKey 打开 `/setup/channel`，Channel 内外两层身份保持一致。Channel DATA 拼成字节流，其中每条记录为 `int32_be N || N 字节 SetupMessage`。N=0 是合法的空 protobuf，不是 EOF；缺少字段时不得凭空当作 READY 或 COMPLETE。Channel 的 final 块可以不带 Setup 记录，仍然只表示 EOF。

SetupMessage 的字段都是独立的 optional：1=enum type，2=Command，3=Event，4=Status，5=SystemInfo，6=CompanionTime，8=ConnectionInfo，9=EnrollmentInfo；tag 7 未定义。type：0 unknown、1 COMMAND、2 EVENT、3 STATUS、4 SYSTEM_INFO、5 TIME、6 TIME_ZONE、7 CONNECTION_INFO、8 ENROLLMENT_INFO。type 的数值与同号的 body tag 不是一回事。

| 子消息 | 字段 |
|---|---|
| Command | 1=enum type：1 GET_STATUS、2 GET_SYSTEM_INFO、3 STOP、4 SYNC_TIME、5 GET_CONNECTION_INFO、6 HEARTBEAT_REQUEST（较新的手机端才有）；2=int64 id |
| Event | 1=type：1 STARTED、2 STOPPED、3 OPTIN_UPDATED、4 UNKNOWN_COMMAND、5 UNSUPPORTED_COMMAND、6 HEARTBEAT_RESPONSE（同上）；2=OptinStatus；3=int64 commandId |
| Status | 1=state：0 unknown、1 READY、2 COMPLETE_PENDING、3 COMPLETE、4 UPDATING、5 OOBE_DONE；手机端还识别 10 ready resumable、11 start resumable、12 critical phase complete、13 exiting watchface、14 watch failure、15 phone failure |
| CompanionTime | 2=int64 epoch 毫秒，5=string 时区 ID（IANA） |
| ConnectionInfo | 1=CellularInfo：1=int32 MNC，2=int32 MCC，3=string 运营商 |
| EnrollmentInfo | 1=string enrollmentIntent |

OptinStatus：1=type（1 ACCOUNTS、2 CLOUD_SYNC、3 COMMUNICATION、4 CALENDAR、5 NOTIFICATIONS、6 LOGGING、7 LOCATION），2=state（1 ACCEPTED、2 DECLINED、3 SKIPPED），3=int64 决定时间（毫秒）。只发送用户实际做出的选择，不能把一次条款确认当成所有隐私项的同意。

SystemInfo：

| tag | 类型 | 含义 |
|---:|---|---|
| 1 | enum version | 0 unknown、1 Emerald 及更早、2 Feldspar、3 Iolite、4 Kyanite |
| 2 | repeated enum（非 packed） | 旧能力：1 WIFI、2 SPEAKER、3 CELLULAR、4 ACCOUNTS、5 LEGACY_CALLING、6 SETUP_PROTOCOMM_CHANNEL |
| 3 | enum edition | 1 GLOBAL、2 LOCAL |
| 4 / 5 | int32 | 平台 SDK / maintenance release |
| 6 | repeated enum（packed） | setupCapabilities，见下 |
| 7 | enum | setupCapabilityVersion：0 unknown、1 V1 |

setupCapabilities：1 restore、2 GMS check-in、3 day-0 OTA、4 FRP、5 tether config、6 signals OOBE done、7 TalkBack 误触发、8 预装助手、9 独立联网、10 受限联网、11 设置中启用备份、12 可恢复的 OOBE。11、12 只在手机端的枚举中出现，不能因为手机认识就认定手表支持。未知或重复的值原样保留；列表为空或 version 为 0 时客户端不得直接拒绝。

有效能力的规则：setupCapabilityVersion 缺失或为 0 时，有效能力为空；为任意非 0 值时使用所声明的列表（不只接受版本 1）。tether、check-in、OOBE 等分支都按有效能力判断；原始值保留用于诊断。

## 5. Setup 状态机

`SetupClient.start(options)` 启动一个归服务所有的工作流，离开页面不会停止它；相同选项的重复调用是幂等的，冲突的调用返回忙。`probe()` 只查询状态和 SystemInfo，不发送时间、同意项、tether 或 COMPLETE；手表主动请求时间时 probe 也只记录。`resume(actionId, response)` 只推进当前有效的动作，过期的动作返回 false。

正常顺序：

1. 固定当前的对端和会话，打开 Channel，启动持续读取和连接观察。
2. 依次发送 GET_STATUS、GET_SYSTEM_INFO。GET_STATUS 带递增的 int64 id，GET_SYSTEM_INFO 与官方一致不带 id。STATUS 和 SystemInfo 的回复**不回显查询 ID**，按当前唯一的查询类型和新收到的数据关联；UNKNOWN/UNSUPPORTED 事件用 commandId 关联，缺少或为 0 时只在唯一活动查询下推断并记录。
3. 收到 READY(1) 后继续。已经看到 COMPLETE(3) 或 OOBE_DONE(5) 时，走已完成的观察路径，不再重新确认条款；若只有 COMPLETE(3) 而没有 OOBE_DONE(5)，补发尚未发送的“跳过恢复”通知，避免永远等待 OOBE。UPDATING(4) 等状态不能当作就绪。
4. 发送 TIME：epoch 毫秒和时区。手表声明了 TETHER_CONFIG 时，按用户选择的模式调用第 6 节的 tether RPC；未声明时记为跳过。
5. 用户确认必需的条款和具体的同意项，保存并提交初始 DataItem。账号可以明确跳过；云同步、日志默认关闭。
6. 手表声明了 RESTORE 而用户没有选择恢复时，先发送“恢复已跳过”（值 1）；这一选择会触发手表的 OEM 初始化，不会启动云恢复。check-in 默认跳过（`SetupCheckinChoice.SKIP`）；只有用户选择尝试（`ATTEMPT`）且手表声明了 GMS_CHECKIN 才查询和等待，运行中随时可以跳过，失败后可以重试或跳过。跳过记为 `SKIPPED`，不伪造注册成功。用户明确选择、且环境提供的恢复、OTA、助手、锁屏、应用安装、CDM 等依赖按选择执行；未实现的依赖报 UNAVAILABLE，必需步骤可以重试或取消，可选步骤可以显式跳过。本实现的默认宿主不执行备份恢复。
7. 只有显式启用时才等待 Data Layer 屏障。队列为空、收到 SyncStart 或 Channel ACK 都不能代替屏障。
8. 手机发送 STATUS COMPLETE(3)，等待手表回复 COMPLETE(3)（本实现 6 次、每次 10 秒）；OOBE_DONE(5) 也表示已经越过完成阶段。只有声明了 SIGNALS_OOBE_DONE 且未关闭该选项时，才继续等待 5。

Channel ACK 只确认字节已被读取，不确认条款、账号或设置完成。COMPLETE_PENDING(2) 不是 COMPLETE(3)。只观察到 5 也可以结束工作流，但 `watchCompleteObserved` 仍为 false；工作流分别报告 `dataPreparationCompleted`、`dataSyncCompletionObserved`、`watchCompleteObserved`、`watchOobeDoneObserved` 四项事实。

### 5.1 不主动查询 GET_CONNECTION_INFO

官方手机端初始化时不发送这项查询。手表没有蜂窝硬件时，Provider 可能返回 null，随后构造回复时异常，导致 Setup 流结束。因此本实现初始化时只发送 GET_STATUS 和 GET_SYSTEM_INFO，但仍能接收 ConnectionInfo，并回复手表主动发来的 Command 5。缺少蜂窝信息不阻塞设置。

Channel 可能在 OPEN_ACK 和双向数据之后以空的 final DATA 结束输入。同一个 `LINK_READY` 主会话上的逻辑 EOF 报告为 CHANNEL_CLOSED；只有主连接真正断开或换了会话才报告 LINK_LOST。空的 final 块不隐含“激活成功”。

### 5.2 异常、EOF 与取消

完整的内层记录如果 body 损坏，记录长度和异常后跳过，继续读下一条；长度损坏或 EOF 留下半条记录时结束本次 Setup，因为没有可靠的重新同步点。type 缺失或与 body 不一致时，若只有一个已知 body、或只有一个活动查询需要的 body，可以兼容推断；有多个 body 时优先使用 type 指定的那个并记录，未知数值不得改写为已知的成功状态。

只有在已经观察到足够的 COMPLETE/OOBE 状态时，EOF 才算正常结束，否则以 CHANNEL_CLOSED、PROTOCOL 等明确的失败结束。取消会立即关闭**本次**的 Channel，不发送重置、不解除配对、也不修改设置状态。新的一次运行重新打开 Channel；旧会话推送的数据不得修改新的运行。

本实现的计时（[SetupDependencies.kt](../../android/core/engine/src/main/kotlin/dev/ticbox/companion/engine/setup/SetupDependencies.kt)）：查询 15 秒，READY、check-in、OOBE 和依赖各 120 秒，check-in 轮询间隔 5 秒，完成等待 6×10 秒，每次读取 64000 字节。[AndroidCompanionRuntime](../../android/core/platform/src/main/kotlin/dev/ticbox/companion/platform/AndroidCompanionRuntime.kt) 把可能涉及系统界面的依赖等待放宽到 900 秒。除完成等待外，这些都是本实现的取舍，不是手表强制的期限。

## 6. 旁路 RPC、初始 DataItem 与 OEM 信息

以下业务走主连接的 MessageApi/RequestApi，**不是**往 Setup 流里写自定义的记录：

| 路径 | 请求、回复与判定 |
|---|---|
| `/clockworkSetupWizard/set_tether_config_request` | 请求 field1 enum：0 unknown、1 standalone、2 tethered、3 restricted；回复空 proto。普通 tethered 为 `0802` |
| `/clockworkSetupWizard/watch_restore_status_request` | 请求 field1 enum：0 unspecified、1 skipped、2 started；回复空 proto。跳过恢复为 `0801` |
| `/watch_gms_checkin_status/rpc/watch_gms_checkin_status_request` | 请求为空 proto；回复 1=status enum，2=bool factoryReset |
| `/watch_gms_checkin_status/rpc/watch_gms_checkin_status_update` | 手表主动推送，结构同上；处理后回复空 proto |

check-in 状态：0 unknown、1 started、2 started non-blocking、3 finished success、4 finished failure。查询回复为 2 或 3 时可以继续；推送只有 3 算成功。factoryReset=true 只保留用于诊断，不得触发本客户端重置手表，也不得覆盖 status 的结果。未知的推送也按已知请求回复空 proto，但不标记成功。手表可能先报告失败，在收到空回复后再按自身策略转为允许继续；因此本实现收到失败时先等一个轮询间隔再查询一次，持续失败才请用户重试或取消。回复和推送的 status、factoryReset 和来源都记录在日志中，不把失败或 ACK 自行转换为成功。

这个 RPC 只查询手表的 check-in 状态，请求为空并不保证手表相关服务不联网。本实现默认不发起：只有用户选择尝试（`checkinChoice=ATTEMPT`）且手表声明了 GMS_CHECKIN 才执行；云设置只约束账号和 CLOUD_SYNC、LOGGING、LOCATION 等同意项，不会隐式开启 check-in 或网络代理。运行中的 CHECKIN 动作只表示可以跳过（`phase=RUNNING`），不表示 RPC 已暂停；失败后等待用户决定时才是 WAITING_FOR_USER。不同固件声明的 setupCapabilities 不同：较旧的 Wear OS 3.5 固件可能为空，较新的 Wear OS 4 固件可能声明 `1,2,6`、版本 1。check-in 通过也不等于完成了 Google 的公网注册。

恢复选择通知和 COMPLETE 是两项独立的蓝牙业务。手表在 Android 手机且支持恢复时，配对后只发送 OEM 信息，然后等待手机的恢复选择；收到选择后执行 OEM 初始化（“跳过”同样会触发）。手表的等待流程还会等 OEM 初始化完成。缺少这个选择时，即使 COMPLETE(3) 交换成功，手表仍可能停在“Continue on phone”。

语言、设置和服务条款使用 Data Layer。服务条款路径 `/audit_recording/data/event/common/TERMS_OF_SERVICE` 是审计 proto；语言使用 SharedSetting 的 feature/name `supported_locales`，内层 field1 为 `LocaleList.toLanguageTags()`。它们不是 SetupMessage 的新字段。本机的初始数据准备负责保存和提交，远端是否接受以及 Data 屏障另行观察。

服务条款以伴侣 AppKey（`com.mobvoi.companion.at / 5cc9866db03e530a21f842a421c7eb42bac58961`）发布，完整 URI 为 `wear://<本地nodeId>/audit_recording/data/event/common/TERMS_OF_SERVICE`。内容是原始的审计 protobuf，不能再套一层 SharedSetting：

| tag | 类型与值 |
|---:|---|
| 1 | context message：#1 device=PHONE(1)，#2 context=OOBE(1) |
| 2 | Timestamp：#1 int64 seconds，#2 int32 nanos；由毫秒按 floorDiv/floorMod 换算 |
| 3 | event enum：TERMS_OF_SERVICE(1) |
| 4 | action enum：ADDED(1) |

只有用户明确接受才创建这条记录；本地保存和提交不等于远端已接受。编码见 [CompanionSettingsCodec](../../android/core/protocol/src/main/kotlin/dev/ticbox/companion/protocol/CompanionSettingsCodec.kt)。

手表的 `/setup/oem_setup_info` 是建立连接后的 DataMap，不是配对前的 GATT：string `product_name`、`internal_name`、`manufacturer_name`，可选 map `oem_companion_colors.watch_hardware_color`，bool `has_oem_info`、`is_china_edition`，可选 long `system_android_wear_version`、IntegerArrayList `system_capabilities`、Asset `product_image`；`wear_platform_versions` 是**以 bytes 形式存放的序列化 DataMap**（内含 int `platform_version`、`maintenance_release_version`），不是独立的 protobuf。

`/end_of_setup/rpc/companion_setup_complete` 只在换手机流程中使用，普通首次设置不必发送。`user_setup_complete`、`device_provisioned` 等由手表的设置向导在本地修改，手机不得直接写手表的 Settings 来冒充激活。

## 7. CDM 权限传输（Android 14+）

用户选择后，用本应用真实的关联请求权限传输同意，然后打开 `/cdm/channel/permission_sync`。手机把 Channel 的输入流和输出流原样交给公开的 `attachSystemDataTransport(associationId, in, out)`，再调用 `startSystemDataTransfer(..., OutcomeReceiver)`；取消或断线时 detach 并关闭自己的 Channel。

associationId 只在手机本地使用，不编码到线上。API 34 上 consent Intent 返回 null 不能断定用户已经同意，可能是保存了拒绝状态，此时报告 UNKNOWN_SYSTEM_STATE，由实际的传输回调确认。API 35+ 先用公开的 consent 查询接口。WATCH 角色、`DELIVER_COMPANION_MESSAGES` 权限、关联归属本应用和用户同意分别检查；不得使用其他应用的关联，也不得复制厂商签名。

UKEY2/D2D、AndroidSystem 证明和 PermissionController 备份都由系统完成。本实现不支持 Android 13 手机，也不复制厂商 JNI。回调成功只表示系统的传输请求完成，不代表每个应用的每项权限都已授予；手表恢复时还会按包名和签名过滤。安全层和可能的系统联网见[身份与权限](identity.md#63-cdm-权限流的-securechannel)。

## 8. 未确认与待验证

- 公开 CDM 端到端的权限恢复效果。
- 用户选择时，真实的 check-in、账号和云功能。
- 不同固件上的发现和初始化差异。
- 断线、重启和长期省电后的恢复。

维护时按阶段的错误和元数据定位问题；不能因为单次业务 EOF 就随意修改协议版本、networkId、nodeId 或心跳参数。

## 9. 保留数据的解除配对

`services.unpair` 是显式的远端解绑入口；`WatchClient.disconnect` 只是本地临时断开。前者先根据新收到的 `/settings/home_info` 确认手表 WCS 版本在已分析的范围内，再固定发送 `erase_esim=false, skip_wipe=true`。默认的旧入口可能恢复出厂设置，版本未知时不尝试。路径、字段、版本依据和回执的局限见[解除配对与迁移](pairing-unlink.md)。
