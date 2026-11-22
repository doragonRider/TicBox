# Flow15 网络代理与旧 BLE 字节帧

本文规定两种独立于主 Wear Data Layer 的协议：Flow15 是可选的手机网络代理；旧 Mobvoi GATT 帧只作为离线资料。二者都不能替代[主连接](transport.md)、[首次设置](pairing-setup.md)或[本地桥接](local-bridge.md)。格式来自官方手机 App（Mobvoi Health 5.3）的实现。

## 1. Flow15 的角色与启用边界

手机为 secure RFCOMM **服务端**，手表主动连接，服务名 `Flow15`、UUID `c3cfde9a-7300-11ec-90d6-0242ac120003`。这条连接有自己的生命周期，不使用主 Channel 的 path、AppKey、RpcRequest 或 MessagePiece 包头。仅知道地址、建立 Bluetooth bond、连接主数据通道，都不意味着此代理已握手成功。

| 已发现标识 | 角色与实现范围 |
| --- | --- |
| `c3cfde9a-7300-11ec-90d6-0242ac120003` | Flow15；当前 Android 后端实际监听的 UUID |
| `fafbdd20-83f0-4389-addf-917ac9dae5b2` | 旧 `Flow` 候选；未实现独立兼容服务 |
| GATT service `3675c0ff-21a6-4f96-9984-09d4f10c64f9` | PSM 发布候选 |
| characteristic `c8625a0b-28c8-4460-874d-0cf9a7538b46` | PSM 值候选；L2CAP 接入/协商未闭合，不猜 PSM 字节布局 |

默认不启动代理。frontend 明确开启 `WATCH_INTERNET_PROXY` 后，Runtime 调 `NetworkProxy.start(selectedPeer)`；关闭时 `stop()`。它能转发手表任意 IPv4 TCP/UDP，包括厂商 HTTPS 和 Google 服务；不能将其描述成仅传健康数据。底层不重复设置 UI 同意流程。`stop()` 释放监听和全部资源，实例可再次 start；`close()` 永久关闭。只接受所选、已 bond 的 peer；同 peer 新连接替换旧代理连接，其余连接关闭。`connectionId` 是本机代次，不是主会话 ID 或线上凭据。

公开状态为 `STARTING → LISTENING → HANDSHAKING → ACTIVE`，失败另报 `FAILED`；它与主连接状态分开。Flow15 尚未在手表上验证过，编译和 TCP/UDP 回环测试不代表与手表兼容。

## 2. 字节序、边界与数值类型

字节流没有统一长度包头。每帧第一个字节为 opcode，后续结构由 opcode 确定；所有多字节整数 **big-endian**。下表只写 opcode 后的字段，固定头总长包括 opcode。

- `I32`：4 字节有符号整数；连接 ID、探针 ID、版本都保留原始 32 位，不强制正数。
- `U16`：2 字节无符号整数；长度最大 65535。
- `IPv4`：4 个原始网络地址字节；保留高位，不进行有符号十进制字符串转换。
- `B`：单字节；`payload[N]` 为无额外封装的 N 字节。
- `opaque`：确定边界、业务含义未确认的字段，保留而不解释为认证/credit/第二端口。

| opcode | 名称 | opcode 后字段 | 总长 |
| ---: | --- | --- | --- |
| 1 | TCP_OPEN | `I32 id, IPv4 destination, U16 port, U16 opaque` | 13 |
| 2 | TCP_CLOSE | `I32 id` | 5 |
| 3 | TCP_WRITE | `I32 id, U16 N, payload[N]` | 7+N |
| 4 | UDP_DATAGRAM | `IPv4 destination, U16 port, U16 N, payload[N]` | 9+N |
| 5 | PING | `32-bit token` | 5 |
| 6 | ACK | `I32 id` | 5 |
| 7 | UDP_DATAGRAM_FAILURE | 与 4 相同 | 9+N |
| 8 | PULL_REQUEST | `I32 id, I32 requested_bytes` | 9 |
| 9 | PULL_DATA | `I32 id, U16 N, payload[N]` | 7+N |
| 10 | PACKAGE_NAME | `U16 opaque, U16 N, payload[N]` | 5+N |
| 11 | ACTIVE_NETWORK_STATE | `I32 network_type, B is_metered` | 6 |
| 12 | PUSH_REQUEST | `I32 id, I32 expected_bytes` | 9 |
| 13 | PUSH_DATA | `I32 id, U16 N, payload[N]` | 7+N |
| 14 | UDP_V2_DATAGRAM | `IPv4 source, U16 source_port, IPv4 destination, U16 destination_port, U16 opaque, U16 N, payload[N]` | 17+N |
| 15 | UDP_V2_FAILURE | `IPv4 source, U16 source_port, IPv4 destination, U16 destination_port, U16 N, payload[N]` | 15+N |
| 16 | HANDSHAKE_START | `I32 version, U16 N, extensions[N]` | 7+N |
| 17 | HANDSHAKE_ACK | 与 16 相同 | 7+N |
| 18 | HANDSHAKE_DONE | 与 16 相同 | 7+N |

UDP v2 failure **没有** opaque U16。若套用 17 字节数据帧头，会吞掉下一帧的字节。单次 RFCOMM read 可以含半帧、多帧；长度字段本身也可以分散在多个 read 中。unknown opcode 没有可恢复的后续长度，保留 remainder 并停止该流，不滑动扫描猜下一帧。

## 3. 握手和工作行为

正常顺序：watch `START` → phone `ACK(version=5, extensions=empty)` → watch `DONE`。已观察的 phone ACK 固定向量为：

```text
11 00 00 00 05 00 00
```

START/DONE 的对端版本和扩展保存为观察；原厂可读分支没有按版本等于 5 拒绝，因此当前实现不加版本白名单。重复/逆序 START 或 DONE 是握手顺序错误。DONE 后手机进入 ACTIVE，发送最近网络状态；无观察到的额外 PASSED opcode。当前实现没有另加“所有 TCP/UDP 必须等 DONE”门槛；握手状态不替代固定帧边界。

| 输入/事件 | 手机动作与含义 |
| --- | --- |
| TCP_OPEN | 后台建立 IPv4 TCP socket，以 id 管理；重复 id 替换并关闭旧 socket。末尾 opaque 只保留。无独立 open-success 报文 |
| TCP_WRITE | 接受完整 payload 到该 socket 的有界写队列后回 ACK(id)。ACK 表示接受排队，**不表示远端服务器已收齐/处理** |
| 网络 socket 读到数据 | 发 TCP_WRITE，保留 id。和反向写入同一 opcode，以传输方向区分 |
| TCP_CLOSE | 排空已经接受的写队列再关闭；不自动重放请求 |
| TCP EOF/connect/read/write failure | 对受影响 id 发 TCP_CLOSE，清理该 socket；其他 socket 继续 |
| UDP v1 | 以 destination IP+port 复用 connected UDP socket；网络返回发 opcode4；发送失败回 opcode7，回显原目的/port/payload |
| UDP v2 | 以 source+destination tuple 复用；手机实际使用临时本地 UDP 端口，不绑定手表 sourceIP。返回保留原 tuple 和首次 opaque；失败 opcode15 无 opaque |
| PING | 原样回显 token 的全部 32 位；不是时间戳重编码 |
| PULL_REQUEST | 吞吐探针，按 id 返回 requested_bytes 个零字节、每块≤65535、opcode9；负数量记录并不伪造回复 |
| PUSH_REQUEST/PUSH_DATA | 记录 expected 和累计收到的字节数，达到 expected 后移除统计；不发 ACK，不将 payload 写入某 TCP socket |
| PACKAGE_NAME | 消耗确定布局，只记长度；当前没有确证 payload 的包名/编码语义 |
| 入站 ACK、NETWORK_STATE、PULL_DATA、UDP_FAILURE | 原厂方向上属于 spurious；当前消耗并诊断，不构造成功回复 |
| 入站 HANDSHAKE_ACK、UDP_V2_FAILURE | codec 可保留已知双向结构；当前 phone dispatcher 不执行该反向操作，诊断后继续 |

PULL/PUSH 没有确证是 TCP credit。TCP 背压来自队列和串行写入，不通过探针替代。所有 opcode、所有网络 worker 必须共用整帧写锁，不能把两个网络 read 的帧交叉写到 RFCOMM。

`ACTIVE_NETWORK_STATE.network_type` 当前映射：`-1` 无可用 INTERNET 网络，`0` cellular，`1` Wi-Fi，`2` Bluetooth，`3` Ethernet，`4` VPN，`5` Wi-Fi Aware，`6` LowPAN，`2147483647` 未知 transport。metered `0=false,1=true`，其余单字节保留未知值。无需把 Android VALIDATED capability 另加成协议必要条件；断网状态发 `-1,0`。

## 4. 当前实现、资源策略与定位

| 代码 | 责任 |
| --- | --- |
| [Flow15Protocol.kt](../../android/core/protocol/src/main/kotlin/dev/ticbox/companion/protocol/Flow15Protocol.kt) | 18 种布局、双向 codec、拆分/黏包解析 |
| [Flow15ProxySession.kt](../../android/core/engine/src/main/kotlin/dev/ticbox/companion/engine/proxy/Flow15ProxySession.kt) | 握手、TCP/UDP、探针、整帧串行输出与局部失败 |
| [Flow15ProxyServer.kt](../../android/core/engine/src/main/kotlin/dev/ticbox/companion/engine/proxy/Flow15ProxyServer.kt) | 所选 peer、bond、连接代次和 listener 生命周期 |
| [AndroidNetworkProxy.kt](../../android/core/platform/src/main/kotlin/dev/ticbox/companion/platform/proxy/AndroidNetworkProxy.kt) | Android secure RFCOMM 服务端 |
| [JavaNetProxyNetwork.kt](../../android/core/platform/src/main/kotlin/dev/ticbox/companion/platform/proxy/JavaNetProxyNetwork.kt) | 实际 TCP/connected UDP；直接使用地址，不发 DNS 查询 |
| [src/sysproxy.mjs](../../src/sysproxy.mjs) | 普通 Node 环境中的离线 codec，无设备/网络访问 |

本机默认预算：TCP/UDP 各32条；每 TCP 排队≤262144字节/64帧；TCP read块4096；connect15秒；网络/代理write30秒；UDP idle60秒；PULL每次≤16MiB/并发4；PUSH跟踪32个ID；decoder待解析+输入≤131104字节。这些是当前 `Flow15ProxyPolicy` 的资源决策，不是手表协议限额。队列不接受的 payload 不 ACK；局部 socket 超限/失败不停止其他 socket。共享 Bluetooth 流写入失败关闭整个 accepted connection，listener 仍可接后续重连。

取消/换代关闭所属 socket 以解除阻塞 IO；未完成 acquisition 也归创建者清理。手机平台仅要求正常 Bluetooth 权限和网络权限，没有新建 bond、扫描或隐藏 HFP connect。诊断只保留 opcode、id、阶段、字节数、版本、异常类型和决定，不记录 TCP/UDP、PACKAGE_NAME 或扩展 payload。

未闭合项：旧 Flow UUID 的行为差异、L2CAP/PSM、IPv6、新 opcode、额外鉴权扩展、当前固件实际选择条件。已有完整的边界向量和本地回环测试，尚未在设备上验证。

## 5. 旧 Mobvoi BLE：适用性与 GATT 标识

手机 APK 仍携旧 GATT 字节帧实现；所见当前 Enduro 初始化链选择 GMS/Wear 通道，未建立旧 GATT 与 Enduro 的可达关系。当前 Android 客户端不以此寻找、配对或读取 Enduro。以下是独立旧实现的确定格式，不能当作 Enduro 回退策略。

| 用途 | UUID |
| --- | --- |
| service | `735dc4fa-348e-11e7-a919-92ebcb67fe33` |
| peripheral→central READ/NOTIFY | `c5b1d8c4-3496-11e7-b687-92ebcb67fe33` |
| central→peripheral WRITE | `c5b1dc5c-3496-11e7-b687-92ebcb67fe33` |
| CCCD | `00002902-0000-1000-8000-00805f9b34fb` |

peripheral 创建 primary service。WRITE 的 Android 属性/权限整数为8/32（WRITE/WRITE_ENCRYPTED）；READ/NOTIFY为18/2（READ|NOTIFY/READ_ENCRYPTED）；CCCD权限34。广播含设备名及 manufacturerId70 的三个随机字节，所见广告不含上述serviceUUID；这些随机字节不是稳定型号指纹。

## 6. 旧 BLE 帧、分片与连接流程

```text
97 91 8d 89 | I32-LE payload_length | payload[N]
```

magic 固定4字节，长度有符号32位 **little-endian**，只含payload长度。payload内部schema未恢复。GATT权限可能要求加密；此帧层未见 CRC/MAC/加密，不因此宣称整个链路无认证。

整个8+N字节连续帧按 usable characteristic value 长度分片：首片含8字节头，后续片无header、无序号、无offset。central初始 `valueBytes=20`，MTU回调后 `valueBytes=ATT_MTU-3`；所见helper没有主动requestMtu。peripheral固定155，MTU回调未更新parser；155不是通用协商值。

合成向量：

```text
payload "abc": 97 91 8d 89 03 00 00 00 61 62 63
payload 00..20，valueBytes20：
97 91 8d 89 21 00 00 00 00 01 02 03 04 05 06 07 08 09 0a 0b
0c 0d 0e 0f 10 11 12 13 14 15 16 17 18 19 1a 1b 1c 1d 1e 1f
20
```

receiver跨通知寻找magic、累计长度和payload，完整后回调并继续；通知边界不等于帧边界，payload内magic不另起帧。原receiver拒绝 `N<=0`；超过10240只警告，不是硬上限。原sender能写零长头，与receiver不一致；本项目离线sender选择不生成它，这属于实现决定。

central流程：`DISCONNECTED(0)→CONNECTING(3)`，`connectGatt(autoConnect=false)`，连接后等1秒发现service，启用READ特征本地通知并写CCCD `01 00`；对应descriptor写成功→`CONNECTED(2)`、清parser、上报连接。当前这些类没有额外业务challenge，CCCD成功即其connected；错误15 `GATT_INSUFFICIENT_ENCRYPTION` 会按未配对处理，不代表可以绕过Bluetooth配对。

原普通GATT操作默认等5秒；分片逐次等write callback；peripheral notify等20秒；建server最多3次、各等1秒；Bluetooth重新开后5秒重建服务；断开会清状态，这些类未见自动重连调度。它们与主 RFCOMM 的断线无关。

[src/legacy-ble.mjs](../../src/legacy-ble.mjs) 提供 encode、packetize 和流decoder；不调用Bluetooth。默认1MiB最大payload是工具预算；非法长度明确错误，保留重叠magic前缀，`finish()`报告截断。旧payload语义、设备实际服务公开状态和Enduro适用性均未建立；离线 codec 不依赖任何 APK。
