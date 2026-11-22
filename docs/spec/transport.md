# 主传输：RFCOMM、RPC、Channel 与 Data Layer

本文规定手机与手表之间主蓝牙连接的字节格式和状态机。字段号、整数宽度、长度边界和身份以本文及以下 schema 为准，不能从 JSON 展示形式反推：[GMS 根 schema](../../schemas/gms-reference.proto)、[Channel](../../schemas/gms-channel-reference.proto)、[Data](../../schemas/gms-data-reference.proto)、[Control](../../schemas/gms-control-reference.proto)。这些 schema 是按线上行为整理的字段描述，不是 Google 的原始源文件。

本文中“官方实现”指手表与手机上 Google Play 服务（GMS）及 Mobvoi 组件的行为；“本实现”指 TicBox 的取舍。客户端不应以 Wear OS 版本号拒绝连接。

## 1. 分层与基本编码

```text
手机主动连接 → 手表 secure RFCOMM server
  int32 BE 长度 + MessagePiece protobuf
    重组得到 WearMessage protobuf
      RpcRequest / ChannelRequest / SetDataItem / Asset / Control
        Channel DATA 拼出的连续字节流
          int32 BE 长度 + SetupMessage 等业务记录
```

| 用途 | UUID / 路径 | 方向与说明 |
|---|---|---|
| 主 GMS 数据流 | `5e8945b0-9525-11e3-a5e2-0800200c9a66`，服务名 `WearableBt` | 手机为 client，手表为 server；secure RFCOMM |
| Flow15 网络代理流 | `c3cfde9a-7300-11ec-90d6-0242ac120003` | 手机监听；独立于主连接，见 [Flow15](flow15-legacy.md) |
| 首次设置 | Channel 路径 `/setup/channel` | 手机在主连接上打开 Channel；Channel 分块可以跨越内层记录 |
| 系统权限传输 | Channel 路径 `/cdm/channel/permission_sync` | 双向字节流交给 Android 系统 CDM，见[身份与权限](identity.md) |

protobuf 使用标准 wire type：varint=0、fixed64=1、length-delimited=2、fixed32=5。`int64`/`int32` 是有符号 varint，**不是** ZigZag；只有明确标为 `sint32` 的字段使用 ZigZag。fixed64、float、double 按小端序；长度前缀是大端 int32（与 Java `DataInputStream` 相同）。字符串为 UTF-8。int64 必须用 64 位整数保存（Kotlin `Long`、JavaScript `BigInt`），不得经 double 转换。

字段缺省、显式空 bytes、显式 0、显式 false 可能含义不同，解码时必须分别保留。WearMessage、ChannelRequest 和 SetupMessage 的各个 body 字段**不是 oneof**。未知字段、未知枚举值和原始字节应保留；重复的标量字段按 protobuf 规则取最后一个，重复的 message 字段合并，并记录兼容处理。

## 2. RFCOMM 记录与 MessagePiece

一个记录是 `int32_be N || N 字节 body`，N 不含自身的 4 字节。普通连接（type 1/5）的 body 是 MessagePiece。type 3 的 A2A 分支可能先加密再分帧，能否解析成 protobuf 不能作为判断明文的依据；type 3 与 CDM 加密见[身份与权限](identity.md)。

| MessagePiece tag | 类型 | 含义 |
|---:|---|---|
| 1 | required bytes | 完整 WearMessage 序列化结果的一段 |
| 2 | required string | **完整** WearMessage 的 SHA-1，Base64url、无 padding、无换行（Android flags=11） |
| 3 | required int32 | 分片序号，从 1 开始 |
| 4 | required int32 | 总分片数 |
| 5 | optional int32，默认 -1 | queueId；单片可省略，多片必须是非 -1 的 ID |

同一消息所有分片的 digest、总数和 queueId 必须一致，全部到齐并校验后才交给上层。SHA-1 只用于完整性，不认证发送方。MessagePiece 没有确认机制。官方接收方要求同一队列的分片按 1、2、3 顺序到达，乱序时清空该队列；因此**发送方必须按顺序发送**。本实现的接收方额外容忍有限的乱序和内容相同的重复分片，拒绝内容冲突的重复分片。

一次 socket 读取可能不足一个长度头，也可能包含半个或多个记录，解码必须是增量的。连接在半个长度头、半个记录或未完成的分片处结束时，不得交付不完整的消息；应关闭该会话，重连后用新的解码器重新开始。长度错误后没有可靠的重新同步点，不得在旧数据流里搜索下一段看似合理的 protobuf 继续使用。

本实现的资源上限：单个记录或完整消息 1 MiB，默认分片 8192 字节，最多 8 个未完成消息、总缓冲 2 MiB，每条消息最多 16384 片，分片组装期限 30 秒（在下一次接收时检查），protobuf 字段数 32768、嵌套深度 32。这些是客户端自己的预算，**不是设备 MTU 或协商值**。实现见 [GmsFraming.kt](../../android/core/protocol/src/main/kotlin/dev/ticbox/companion/protocol/GmsFraming.kt) 和 [ProtoWire.kt](../../android/core/protocol/src/main/kotlin/dev/ticbox/companion/protocol/ProtoWire.kt)。

## 3. WearMessage 与 CONNECT

| WearMessage tag | body | 用途 |
|---:|---|---|
| 4 / 5 / 6 | SetAsset / AckAsset / FetchAsset | 资源数据、确认、请求 |
| 7 | Connect | 建立节点会话 |
| 8 / 9 | SyncStart / SetDataItem | 持久 Data Layer 同步 |
| 10 | RpcRequest | 普通 MessageApi 消息 |
| 11 | Heartbeat | 空心跳 |
| 12 / 13 | FilePiece / bool hasFile | 附件分片 / 所属消息带附件 |
| 16 | RpcRequest | 内含 ChannelRequest |
| 17 | CryptoMessage | type 3 A2A 协商 |
| 18 | RpcRequest | RequestApi 请求及其回复 |
| 19 | IosAuthMessage | iOS L2CAP 认证（部分手表版本才有） |
| 20 | ControlMessage | 迁移、暂停和恢复控制 |

CONNECT 字段：

| tag | 类型 | 含义 |
|---:|---|---|
| 1 | required string nodeId | 持久的本地节点 ID，不是 MAC 地址 |
| 2 | required string nodeName | 节点显示名称 |
| 3 | optional int64 androidId | 有符号 long；不是账号 ID |
| 4 | optional int32 connectionVersion | 官方手机端写 3；字段名未确认 |
| 5 / 6 | optional int32 wireVersion / minWireVersion | 官方手机端写 1 / 0，构成兼容范围 |
| 7 | optional string networkId | 持久的网络身份；与 Wi-Fi SSID 无关 |
| 8 | optional string companionPackage | 伴侣应用包名 |
| 9 | optional bool isMigrating | 普通连接为 false；迁移是独立流程 |
| 10 | optional string previousNodeId | 迁移时的旧节点 |
| 11 | optional int32 senderSdkVersion | 发送方的 `Build.VERSION.SDK_INT`（GMS 24.28 起） |

双方各自先写出自己的 CONNECT，再读取并检查对方的。**手机收到手表的 CONNECT，不代表手表已接受手机的全部字段**；手表是否接受要看后续业务（例如 Setup）能否往返。

手表还会检查：服务端配置已启用、蓝牙地址相符、迁移标记相符、wire 版本范围兼容。手表第一次在 `client_node_id==null` 时读到手机节点会记住它，之后非迁移的连接必须使用同一节点 ID；这一记录发生在 networkId 和版本检查之前。双方 networkId 都非空且不同时连接失败。因此重试、进程重启和应用更新都不得生成新的节点身份，持久化要求见[身份与权限](identity.md)。

连接状态：`IDLE → CONNECTING → NEGOTIATING → LINK_READY`；暂时的传输失败进入 `RETRYING` 后重试，身份或协议不兼容进入 `FAILED`。每次物理连接使用新的 sessionId；旧会话的回调、Channel、未完成的 RPC 和附件缓冲不得归入新会话。`LINK_READY` 只表示主会话可用，不代表配对授权、Setup、权限复制或健康同步已完成。

本实现的计时：socket 连接 30 秒，CONNECT 15 秒，单次物理写 15 秒；重连从 1 秒开始指数退避到 30 秒，加 ±20% 抖动，暂时失败不限次数。官方 GMS 的重连间隔为 1、2、4……256 秒，写超时 60 秒，两者不是同一算法。读或写线程任一结束时清理整个物理会话，关闭所有 Channel 并结束未完成的 RPC；业务异常尽量只影响对应的处理器。实现见 [SessionEngine.kt](../../android/core/engine/src/main/kotlin/dev/ticbox/companion/engine/SessionEngine.kt) 和 [SessionConfig.kt](../../android/core/engine/src/main/kotlin/dev/ticbox/companion/engine/SessionConfig.kt)。

## 4. MessageApi 与 RequestApi

普通业务数据位于 `WearMessage.10 → RpcRequest.7`；tag 9 是 SetDataItem，不是 RPC。Channel 位于 WearMessage.16；需要回复的 RequestApi 请求及其回复位于 WearMessage.18。

| RpcRequest tag | 类型 | 含义 |
|---:|---|---|
| 1 | required int32 | requestId，在同一 generation 内递增 |
| 2 / 3 | required string | 目标应用的 packageName / certificateDigest，即 AppKey |
| 4 | required string | targetNodeId |
| 5 | required int32 | 发送方写 0；含义未确认 |
| 6 | required string | path，区分大小写；Channel 消息的外层 path 为空字符串 |
| 7 | optional bytes | 业务数据；空与缺省分别保留 |
| 8 | optional string | sourceNodeId；缺省时接收方按当前连接的对端填充 |
| 9 | optional ChannelRequest | Channel 子协议 |
| 10 | optional int32 | generation |
| 11 | optional bool | expectsResponse |
| 12 | optional int32 | responseCorrelationToken |
| 13 | optional int32 | messageOption；含义未完全确认 |

RequestApi 的关联 token 按 Java int32 运算得出：`((generation + 527) * 31) + requestId`，溢出后取有符号值。请求带 11=true；回复带 12，数据仍放在 7。官方默认超时 60 秒。普通 MessageApi 没有通用的成功确认；应用自己的 requestId、回调 invocationId 或 JSON ack 是更内层的独立关联。

接收方先处理带回复 token 的消息，再处理普通请求。目标节点是本机时本地投递，其他节点转发或丢弃。generation 为正时，接收队列按应用、来源和 path 分组：generation 变化时清空队列并以第一个序号为起点；旧序号丢弃，有缺口时等待；队列超过 100 条或队首等待超过 20 秒时跳过缺口（超时在**下一次入队**时才检查）。generation≤0 的消息直接分派。

本实现只在当前会话、同一对端、AppKey 相符时接受回复，未知或迟到的 token 记录后忽略。处理器在独立的工作队列中执行，回复、超时和取消都只结束对应的请求。同一会话内重复的 RequestApi 由有界的去重缓存吸收，不会重复执行业务。发送成功只表示数据已写入 socket，不能当作远端业务成功。

## 5. Channel

Channel 复用主连接，封装为 `WearMessage.16 → RpcRequest.9`。外层 RPC 的 payload 缺省、path 为 `""`、generation 为 0；业务路径在 OPEN 的 Control.tag6。requestId 与 Channel 序号相互独立。

ChannelRequest：2=Control，3=Data，4=Ack，6=int32 minimumVersion，7=int32 origin。三者同时存在时，接收方按 Control、Data、Ack 的顺序只处理第一个。minimumVersion：普通 ChannelApi 为 0，LargeAssetApi 为 1，大于 1 时丢弃。origin：0 为普通 Channel，1 为 LargeAsset，其他值丢弃。origin 1 的 LargeAsset 流程未确认，不得用普通文件冒充。

| Control tag | 类型 | 含义 |
|---:|---|---|
| 1 | enum，缺省 1 | OPEN=1、OPEN_ACK=2、CLOSE=3 |
| 2 | fixed64 | channelId，8 字节小端 |
| 3 | bool | **发送方**是否为打开方 |
| 4 / 5 | string | AppKey 包名 / 摘要；OPEN、CLOSE 填写，OPEN_ACK 通常省略 |
| 6 | string | OPEN 的路径；缺省为空，不做空白裁剪 |
| 7 | int32 | CLOSE 的 applicationError；正常关闭可显式写 0 |
| 8 | bool | allowOverMetered，OPEN/OPEN_ACK 使用，常规为 true |
| 9 | bytes | 含义未确认，原样保留 |

type 缺失或为未知值时按 OPEN 处理（与官方实现一致），同时保留原始值并记录。

DataHeader：1=fixed64 channelId，2=bool fromOpener，3=int64 sequence。Data：1=Header，2=bytes payload，3=bool final。Ack：1=Header，2=bool final。每个方向的 sequence 从 0 开始，**每个 DATA 块**加 1，不是字节偏移。

通道以 `(peerNodeId, channelId, localIsOpener)` 区分；收到对端的 DATA、ACK 或 OPEN_ACK 时，本地角色取 `!sender.fromOpener`。双方同时用相同数字打开的两个 Channel 是不同的通道。AppKey 决定事件路由和调用授权，不能因为通道键里没有 AppKey 而忽略它。

### 5.1 状态

1. 本地打开：生成非负 ID（`Random.nextLong() & Long.MAX_VALUE`，碰撞时最多重试 5 次），记为打开方，进入 OPEN_SENT 并发送 OPEN。
2. 接受对端 OPEN：记为非打开方并记录路径，进入 ESTABLISHED，先发 OPEN_ACK，再异步通知应用。**OPEN_ACK 不保证业务监听器已经接管数据流**。
3. 打开方收到匹配的 OPEN_ACK：取消 OPEN 重试，打开调用完成。迟到的 ACK 不能挽回已经失败的打开。
4. ESTABLISHED 后，发送方向为 `WAITING_TO_READ → WAITING_FOR_ACK → WAITING_TO_READ/CLOSED`，接收方向为 `WAITING_FOR_DATA → WAITING_TO_WRITE → WAITING_FOR_DATA/CLOSED`。
5. `closeGracefully(errorCode)` 进入 CLOSING，拒绝新的写入，等已开始的写入发完后发送 CLOSE 并释放资源；排队中尚未开始的写入被拒绝。`close()` 立即中止，并尽力发送 CLOSE。收到对端 CLOSE 时直接清理本地，没有 CLOSE_ACK。

已建立的通道收到相同 ID 和路径的重复 OPEN 时，重发 OPEN_ACK，但不得再次通知应用；路径不同的冲突应记录并清理对应通道。

### 5.2 DATA、ACK 与重试

发送方同一时间只允许一个未确认的 DATA 块：从应用的输出流读取数据，发送当前序号，然后等待匹配的 ACK 再继续读取。没有数据时不发送空块；EOF 时可以发送空的 final 块。官方默认块大小：蓝牙 64000 字节，BLE 5000 字节，缓冲限制在 128～65536 字节之间。

接收方只交付期望序号的块，payload 最多 65536 字节。**必须等应用实际读走数据，或本地输入已关闭而明确丢弃后，才能回 ACK**；应用还没拿走 InputStream 时保留缓冲，不提前确认。`ACK.final = DATA.final || 本地输入已关闭`，然后接收序号加 1。发送方收到匹配的 ACK 后发送序号加 1；ACK.final 关闭这个发送方向，否则继续读取下一块。超前的 ACK 是状态错误，过期的 ACK 不推进状态。

| 包 | 官方最多发送次数 | 默认间隔 | 失败处理 |
|---|---:|---:|---|
| OPEN | 2 | 15000 ms | 没有匹配的 ACK，本地打开超时 |
| DATA | 3 | 手机 18000 ms；手表蓝牙/BLE 8000 ms | 超时后关闭该 Channel 并发送 CLOSE 0 |
| OPEN_ACK、DATA ACK、CLOSE | 1 | 不等待确认 | 发出即完成 |

本实现的重试计时从**数据实际写完**开始，排队时间不计入对端的应答期限。官方接收方会忽略重复的 DATA 而不补发 ACK；本实现额外保存最后一个已消费块的序号、长度、final 标记、SHA-256 和对应的 ACK，收到内容相同的重传时补发 ACK 而不重复交付，同序号不同内容则记录并忽略。

### 5.3 EOF、取消与断线

DATA.final 表示**这一方向的字节流结束**。final 块可以带数据也可以为空；接收方先交付数据，再回 ACK.final，然后让读取返回 EOF。另一方向仍可继续工作。它不是 Setup 完成，不会关闭整个 Channel，也不表示 RFCOMM 断开。

本地 `closeGracefully(errorCode)`、对端 CLOSE、流 EOF 和主会话断开是四种不同的事件。线上的 applicationError 与本地关闭原因不同：官方本地原因为正常 0、断线 1、对端关闭 2、本地关闭 3、DATA 超时 4，不能用来解释 Control.type。节点不可达或不允许按流量计费网络时清理通道；重连后必须重新 OPEN，不得沿用旧的 channelId 和序号。

本实现中取消会立即中止对应通道，但不会从中间切断共享的物理记录。InputStream 和 OutputStream 的 close 分别结束各自方向；关闭输出流可以等待 final ACK。阻塞的流操作在 IO 线程执行，线程中断转为 `InterruptedIOException` 并保留中断标记。实现见 [ChannelMultiplexer.kt](../../android/core/engine/src/main/kotlin/dev/ticbox/companion/engine/ChannelMultiplexer.kt) 和 [WearChannelStreams.kt](../../android/core/platform/src/main/kotlin/dev/ticbox/companion/platform/transport/WearChannelStreams.kt)。

本实现的默认上限：16 个活动 Channel，每个物理会话 4096 次打开，单次写入 512 KiB，每通道 4 个排队写入，64 个待发包，路径 4096 字节，见 [ChannelConfig.kt](../../android/core/engine/src/main/kotlin/dev/ticbox/companion/engine/ChannelConfig.kt)。

`chl-...` 形式的 token 只存在于 GMS 本地 Binder API，不在蓝牙上传输。它的 proto 为 node1/package2/digest3/fixed64 id4/opener5，使用 `Base64.DEFAULT`（带 padding 和换行），不要与 MessagePiece 的 Base64url 摘要混用。独立客户端不需要生成这种 token。

## 6. DataItem 同步

SetDataItem 位于 `WearMessage.9`，双方都可以发布：

| tag | 类型 | 含义 |
|---:|---|---|
| 1 / 9 | required string | packageName / certificateDigest，即 Data 命名空间 |
| 2 | required string | 完整 URI `wear://<ownerNode>/<path>` |
| 4 | optional bytes | 数据；只有已知业务才按 DataMap 解析 |
| 5 | optional int64 | sourceSequenceId |
| 6 | optional bool | deleted 墓碑；删除不得用空数据代替 |
| 7 | optional string | sourceNodeId |
| 8 | repeated AssetEntry | 资源引用，保持顺序 |
| 10 | optional int64 | lastModified |

tag 3 未定义。AssetEntry：1=required 名称，2=required AssetReference，3=required int32 type（schema 默认 1，官方发送方写 4）。AssetReference 只有 tag 4（optional string digest）。这里的 type 4 与 DataMap 中 Asset 的类型 13 不是同一个枚举。

SyncStart 位于 `WearMessage.8`：1=int64 receivedSequenceId；2=repeated SyncEntry（1=sourceNodeId，2=int64 sequenceId）；3=int32 version，当前为 2；4=SyncRestrictions。没有游标时写 -1。version<2 使用旧的单游标顺序；≥2 按每个来源的游标重放，只发送高于对方已提交游标的项。

SyncRestrictions 是一组按包名的条目：1=packageName，2=DataFilters，3=CapabilityFilters。DataFilters.items 中 1=kind（0 未指定、1 精确 URI、2 前缀），2=uri；CapabilityFilter 中 1=name。它限制重放和能力选择，不是首次设置前必须安装的认证凭据，权限条件见[身份与权限](identity.md)。

同步状态：发送 SyncStart 后为 `RESUMING`，收到对方的 SyncStart 后为 `ACTIVE`。ACTIVE 只说明重放协商已经发生，协议没有通用的结束消息。每个 `(peerMAC, localNodeId, remoteNodeId, networkId)` 有自己的数据范围，每个来源保存已提交的序号。接收时，数据项、引用、游标和转发序号**必须在同一事务**内提交；序号不高于已提交游标的视为重复。缺少来源时按当前对端处理并记录，重放时补上显式来源，避免下一跳归属错误。同一 URI 的新版本覆盖旧值，删除保留墓碑；这一层只保存最新状态，不等于健康历史。

本地发布也必须在同一临界区内分配持久递增的序号、提交数据项再入队，不能每次重连都从 1 重新编号。入站队列暂满时读取方等待，不丢包也不因此重连；存储失败或无法确定数据归属时不推进后续游标，冻结该数据会话并请求重连，从已提交的游标恢复，避免漏掉第 N 项却提交了 N+1。未完成的附件随旧会话丢弃，引用已持久化时可以重新获取。实现见 [DataSyncEngine.kt](../../android/core/engine/src/main/kotlin/dev/ticbox/companion/engine/data/DataSyncEngine.kt)。

### 6.1 DATA_SYNC_PROGRESS 屏障

RequestApi 路径为字面 `DATA_SYNC_PROGRESS`（GMS 的 AppKey），请求和回复结构相同：1=string trackerId，2=int64 sequenceId。请求给出本地已可发送的边界；对端等到来源游标达到该值、相关资源完成后，回复自己当前的边界；本地再等对方边界的数据和资源落库。

每个对端串行执行。回复仍由外层 RequestApi token 关联；trackerId 不一致只记录。缺少序号、不支持、超时或断线都不算完成。屏障只覆盖当时的 Data Layer 数据，**不代表健康历史、后续数据或云同步完成**。首次设置不强制等待它。

## 7. DataMap

DataMap 根的 tag1 为 repeated Entry；Entry：tag1=required string 名称，tag2=required Value；Value：tag1=required int32 type，tag2=optional Storage。Storage 的各字段不是另一层类型标签。

| Value.type | 类型 | Storage 字段 |
|---:|---|---|
| 1 | byte[] | 1 bytes |
| 2 | string | 2 string |
| 3 / 4 | double / float | 3 fixed64 IEEE754 / 4 fixed32 IEEE754 |
| 5 / 6 | long / int | 5 int64 / 6 int32 |
| 7 | byte | 7 **sint32 ZigZag**；保留原始数值 |
| 8 | boolean | 8 bool |
| 9 | DataMap | 9 repeated Entry |
| 10 | ArrayList | 10 repeated Value；元素为 String、Integer 或 DataMap |
| 11 / 12 | String[] / long[] | 11 repeated string / 12 repeated int64 |
| 13 | Asset | 13 int64，指向外部资源列表的下标；引用名通常为 `"0"`、`"1"` |
| 14 | null | 不写 Storage |
| 15 | float[] | 14 repeated float |

发送方按 Java 字符串顺序（UTF-16 码元）排列键。数组按非 packed 方式写出，解码时兼容 packed。重复键取最后一个值并记录；未知类型保留整个原始 Value，不猜测单位或丢弃数据。普通 byte[] 不能因为恰好能解析成 DataMap 就改变业务类型。实现见 [DataMapCodec.kt](../../android/core/protocol/src/main/kotlin/dev/ticbox/companion/protocol/DataMapCodec.kt)。

## 8. Asset 与附件

| 消息 | 字段 |
|---|---|
| FetchAsset（WearMessage.6） | 1 required packageName，2 required digest，3 optional bool permissionOnly，4 required certificateDigest |
| SetAsset（WearMessage.4） | 1 required digest，2 optional 内联 bytes，3 optional AssetPermissions |
| AckAsset（WearMessage.5） | 1 required digest |
| AssetPermissions | 1 repeated AppKey；AppKey：1=required package，2=required certDigest |
| FilePiece（WearMessage.12） | 1 required ownerMessageDigest，2 required bool finalPiece，3 required bytes data，4 optional fileDigest |

资源摘要和 ownerDigest 都是 SHA-1、Base64url、无 padding。ownerDigest 针对**所属 WearMessage 的原始字节**，fileDigest 针对资源本身。不得把已知字段重新编码后再计算 ownerDigest，未知字段或字段顺序会改变结果。

小资源可以放在 SetAsset 里内联；大资源由所属消息标记 `hasFile=true`，随后按顺序发送多个 FilePiece，finalPiece 结束。收到完整资源后，必须核对 `SetAsset.digest == SHA1(实际字节)`，再在一个事务里保存数据和权限并发送 AckAsset。FilePiece 末片上的 fileDigest 不符时官方实现只记录，但不能因此跳过对 SetAsset 摘要的校验。重复的 Ack 忽略，不产生第二次业务事件。

只含权限的 SetAsset 可以不带数据，只增加权限，不删除已有数据。FetchAsset 按请求方的 AppKey 检查权限；无权访问时回复空权限，不发送内容。缺数据时请求完整资源，只缺权限时设置 permissionOnly。同一资源有多个 AppKey 时分别补权限。被拒绝后在本会话内暂停请求，显式重新同步或重连后可再试，不要立即反复请求。

本实现的默认上限：每个数据项 512 KiB，每个资源 16 MiB，4 个并发附件，128 个待取/待发摘要，内联阈值 32 KiB，FilePiece 60 KiB，入站 64 个消息；资源总存储 64 MiB，数据库 128 MiB。资源权限与 APK 安装权限无关。

## 9. 能力公告与手表连接状态

协议没有独立的能力查询或公告消息。每个能力都是一个普通的 SetDataItem：

```text
外层 AppKey = com.google.android.gms
摘要       = 38918a453d07199354f8b19af05ec6562ced5788
URI        = wear://<公告节点>/capabilities/<目标包名>/<目标证书SHA-1>/<能力名>
data       = 单字节 UTF-8 "s"(0x73) 表示静态，"d"(0x64) 表示动态
```

URI 内的目标 AppKey 与外层的 GMS 命名空间不同。能力名先经 `Uri.encode` 再作为路径段追加，因此是双重转义，读取时解码两次；只含 ASCII 下划线的名字不受影响。空数据、缺数据或未知首字节按动态处理并记录；多出的字节忽略；删除仍是墓碑。payload 不是 DataMap。

| 端 | 自己公告 | 查询对端 |
|---|---|---|
| 手机端 Mobvoi 伴侣 | `ticwatch_phone_connect` | `ticwatch_wear_connect` |
| 手表端 Mobvoi 伴侣 | `ticwatch_wear_connect` | `ticwatch_phone_connect` |

手表默认的 OEM 映射使其公告 URI 内的 AppKey 可以是 `com.mobvoi.companion.at / 5cc9866db03e530a21f842a421c7eb42bac58961`，不能因为手表本地包名是 `aw` 就照写 `aw`。TrustedPeers 还可能按对端映射改写内部 AppKey。能力查询基于本地已同步的数据和节点图，排除自己，再按可达性、迁移状态和 SyncRestrictions 过滤。能力存在不代表节点在附近。

手机**必须**提供动态能力 **`handle_remote_intent`**，否则手表会显示“Disconnected from phone”。手表按这一能力的可达节点判断连接方式：尚未取得集合为 UNKNOWN(0)，集合为空为 DISCONNECTED(1)，有附近节点为 BT(2)，没有附近节点但跳数有限为 CLOUD(3)，其余为断开。系统界面在蓝牙开启且为 BT(2) 时显示与手机蓝牙连接。电量同步、健康桥可用或 `ticwatch_phone_connect` 存在都不能代替这一能力；达到 BT(2) 不需要云或 Flow15。

为使该能力对应真实功能，手机接收 `/remote_intent/rpc` 的 DataMap：可选 string `action`、`uri_data`、`package_name`、`mime_type`，String[] `categories`，DataMap `extras`、`activity_options`，int `start_mode`（0 Activity、1 Broadcast、2 Service、3 WebView、4 第三方 Activity；未知按 0），以及 string `com.google.android.clockwork.actions.RpcWithCallback.invocation_id`。手机按模式分派，Activity 带 `FLAG_ACTIVITY_NEW_TASK`；WebView 返回失败。回复 `/remote_intent/result`：相同的 invocationId，嵌套键 `com.google.android.clockwork.actions.RpcWithCallback.result` 中含 bool `successful` 和 int `message_id`=1。成功只表示分派调用返回，不代表目标已显示。系统的后台启动限制和组件权限仍然生效；本实现未完整复现官方“等待解锁/点亮屏幕”的交互。

## 10. 心跳、控制消息与迁移

Heartbeat 是没有字段的消息，最小的完整 WearMessage 为 `5a00`。接收方先检查 tag 11，存在时只处理心跳而忽略同一容器中的其他 body，因此不要把心跳与业务数据放在同一条消息里。它是“收到非心跳消息、且没有其他待写数据时”发出的回执，不是逐包确认，也不是周期性 ping；收到心跳不再回复心跳。官方默认配置 `disable_bt_heartbeat=true`、`version_dependent_heartbeats=false`，普通情况下不发送心跳，因此不能把没有心跳当成断线的原因。

ControlMessage：1=enum type，2=AccountMatchingMessage。type：0 未知，1 终止关联，2 暂停数据同步，3 恢复数据同步，4 迁移失败，5 账号匹配，6 迁移取消。**1 会移除系统关联并请求删除蓝牙配对，不是普通断开**。2 只暂停数据层的监听，控制流仍在；3 恢复已有的写入方，不新建 socket。5 只用于迁移时的账号匹配，普通首次设置不需要发送。

AccountMatchingMessage：1=int32 operation，2=repeated accounts，3=error string；account：1=Google 账号 ID，2=`Account.name`。operation：1 GetAccounts → 2 AccountsResponse → 可能有 3 RemoveAccounts → 4 Confirm；5 Cancel；6 RemoteError。它可能删除手表上的账号，不得用来探测普通连接。需要回复时官方等待 3000 ms，写入失败 1000 ms 后重试，最多再试 3 次；GET_ACCOUNTS 另有 5 次的计数上限。完整字段见 [Control schema](../../schemas/gms-control-reference.proto)。本实现只处理对端明确处于迁移模式且账号列表为空的情况，流程见[解除配对与迁移](pairing-unlink.md#55-迁移标志协商与无账号分支)；非空账号列表的匹配未实现。

## 11. 兼容与日志

解码时尽量保留可解释的数据：未知字段、未命名的数值、缺失的可选字段和异常 body 按具体情况记录处理方式，不能因为新增字段、重复能力或未命名的业务值就让整条连接失败。另一方面，有界分配、可信的帧边界、资源摘要校验、会话隔离和游标原子提交是数据一致的前提，不能为了“尽力而为”而伪造成功。

诊断日志记录层级、会话和对端、path、字段号、数值状态、长度、序号、异常类型和处理决定，不记录完整健康数值、账号、URI extras 或密钥。需要数据样本时，由用户主动导出 JSON。

未确认：Control.tag9 的业务含义、origin 1 的 LargeAsset 流程、type 3 A2A 的完整加密状态、iOS 认证、OEM 的全部动态配置，以及不同固件之间的差异。已知格式中的未知值和原始数据继续保留；没有依据的功能不自行编造协议。
