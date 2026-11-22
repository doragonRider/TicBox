# 身份、AppKey 与权限

本文说明独立客户端如何拥有自己的安装身份、保持稳定的节点身份、构造 AppKey，以及哪些操作必须交给 Android 系统完成。配合[主传输](transport.md)和[配对与首次设置](pairing-setup.md)阅读。

## 1. 六类身份

| 身份 | 来源与存储 | 作用 |
|---|---|---|
| APK 包名与真实签名 | PackageManager、安装证书 | 本机 Binder、signature 权限、应用更新 |
| 蓝牙配对（bond）与 MAC | 两端的 Android 蓝牙栈 | secure RFCOMM 链路和对端设备 |
| nodeId / networkId | 本地持久文件、CONNECT、对端登记 | GMS 节点、网络和重放范围 |
| 线上 AppKey | RpcRequest、Channel Control、DataItem 中的包名和证书摘要 | 对端业务路由和资源命名空间 |
| CDM associationId 与用户同意 | 本应用拥有的系统关联 | 系统权限传输、后台唤醒等系统能力 |
| Google / Mobvoi 账号或云 token | 独立的账号流程 | 私有服务和云业务；本地健康数据不需要 |

这些值不能互相替代。证书摘要是公开的，不是私钥；nodeId 不是账号、MAC 或 associationId；在线上写某个包名不会改变 APK 的真实签名。主链路就绪也不代表以上各层都已授权。

## 2. 稳定的节点身份

本实现的 [AtomicFileIdentityStore.kt](../../android/core/platform/src/main/kotlin/dev/ticbox/companion/platform/identity/AtomicFileIdentityStore.kt) 在 `noBackupFilesDir/companion/identity.bin` 保存 nodeId、networkId 和 nodeName。首次使用时用两个随机 UUID 生成 nodeId 和 networkId，先原子写入并核对，再发到线上。多个实例共用进程内锁，避免生成相互竞争的身份。应用更新、进程重启和断线重试都读取同一份值。

手表第一次在 CONNECT 中记录手机的 nodeId 后，之后的普通连接必须使用同一个值，而且这一记录发生在网络和版本检查之前，握手失败后也不能换一个 nodeId 再试。身份文件损坏或无法写入时，必须报告 IDENTITY 失败并保留文件，不得自动重建身份。存放在 noBackup 目录可以避免 Android 云备份把同一节点复制到另一部手机。

本地数据范围按 `peerMAC / localNode / remoteNode / network` 区分；对端重置或节点变化后，不得沿用旧的来源游标。对端 CONNECT 带有 networkId 时，本地业务范围优先使用这个值，否则用本地的 networkId。这只是本地的数据组织方式，不代表 CONNECT 字段可以随意改写。

物理 sessionId 只标识进程内的一次连接尝试，不是持久身份。待回复的 RPC、Channel、资源组装和后台操作必须与它绑定，旧会话的结果不得进入新会话。界面层只通过 API 观察状态，不读取或修改身份文件。

身份可以随[数据迁移](../dev/data-migration.md)导出后导入另一个应用安装，此时手表仍认为是同一个节点；系统关联（CDM）不会随之迁移，需要在新安装中重新建立，见[连接与恢复](../dev/connection.md)。

## 3. AppKey

比较 AppKey 时，包名和摘要都必须完全相同：

```text
certificateDigest = lowercaseHex(SHA-1(Signature.toByteArray()))
OEM allowlist packageKey = lowercaseHex(SHA-256(UTF-8(packageName)))
```

`Signature.toByteArray()` 是签名证书的 DER 编码，不是 APK 文件的 SHA-1，也不是私钥。AppKey 摘要与 MessagePiece、Asset 使用的 SHA-1/Base64url 摘要是两回事。

| 用途 | packageName | certificateDigest |
|---|---|---|
| Mobvoi 伴侣的默认路由 | `com.mobvoi.companion.at` | `5cc9866db03e530a21f842a421c7eb42bac58961` |
| GMS Data 与能力命名空间 | `com.google.android.gms` | `38918a453d07199354f8b19af05ec6562ced5788` |
| 手表上的 Mobvoi 应用 | `com.mobvoi.companion.aw` | 与 Mobvoi 相同的摘要，但包名不同 |

这些是协议常量，不代表本应用获得了对应的签名资格。手机 App 和手表 Local Bridge 必须使用同一把自己的签名密钥，不能用厂商密钥签名。

### 3.1 本机 GMS API 与直接 RFCOMM 的区别

应用通过手机上的 GMS（`Wearable.get...Client(context)`）调用时，GMS 根据真实调用方的包名和安装证书生成 AppKey。`putConfig` 等第一方入口还会检查 OEM 包名和签名白名单；把官方包名重新签名或把摘要作为参数传入，都通不过真实签名的检查。默认白名单只包含 Mobvoi 的包名和摘要，不包含自建应用。

直接使用 RFCOMM 的客户端自己编码线上身份，不经过手机 GMS 的签名检查。手表的 type 1/5 接收链读取 RPC 中的包名和摘要来建立 AppKey，用于对端映射和事件路由，没有发现要求每条 RPC 带 Mobvoi 私钥签名的步骤。**不能用本机 Binder 的签名检查推断直接协议需要厂商私钥，也不能反过来认定所有 Google 私有接口都可用。** 首次设置和本地通信已经用这一方式完成；这不等于全部功能、全部固件都已获授权。

## 4. OEM home 与 TrustedPeers 路由

手表固件的默认属性：

```properties
ro.oem.home_package_names=com.mobvoi.companion.aw
ro.oem.companion_package=com.mobvoi.companion.at
ro.oem.companion_digest=5cc9866db03e530a21f842a421c7eb42bac58961
```

普通 home 集合还包括 `com.google.wear.services`、`com.google.android.wearable.media.sessions`、`com.google.android.wearable.setupwizard`。运行时也可能从 `ro.cw.home_package_names`、`cw.home_package_names`、`cw.oem_home_package_names`、`cw.oem_companion_package/digest/signature` 读取；`invert_settings_override=true` 时非空的只读属性优先，其次才是 SecureSettings。显式摘要优先于签名字符串，都没有时才查询 PackageManager。实际设备的属性、动态开关和覆盖配置可能与默认值不同。

手表处于 home 映射模式时：

1. **出站**：调用方属于 home 或 OEM home 集合时，其 AppKey 改写为配置的伴侣 AppKey。例如 `aw` 或 WearServices 发出的消息表现为 `at`。
2. **入站**：AppKey 与伴侣完全相同的事件，按 path 的**第一段**选择 home 或 OEM home 中的接收者，不是任意前缀匹配。`/setup/channel` 的第一段是 `setup`，由 SetupWizard 接收；`/health/...` 由声明了 `health` 段的接收者处理。
3. **不改写的情况**：直接投递给目标包，要求接收者实际安装的 AppKey 一致；包名相同而签名不同时不投递。
4. **本地 Channel API**：home 应用经反向映射成伴侣身份，才能操作之前以伴侣身份创建的本地 Channel。

Mobvoi 手表应用声明的路径段：`companion;ticpay;tic_lpa_app;action;health;sports;ios;tic_app_store;tic_watchface_market;screen_shot`。SetupWizard 声明：`clockworkSetupWizard;enable_phone_switching;end_of_setup;watch_gms_checkin_status;watch_ota_status;watch_preloaded_assistant;watch_talkback;setup`。同一段可以有多个接收者，不能凭名字推断唯一的消费者。

TrustedPeers 是另一层按对端缓存的映射，没有匹配时保留原 AppKey；它不是逐条验签，也不是简单的 at↔aw 文本替换。能力 URI 内的目标 AppKey 也可能被映射，外层仍是 GMS 命名空间。Channel 外层 RPC 和内层 Control 各自携带身份；协议未要求两者相等，但客户端应保持一致，保证路由和回传正确。

## 5. 节点访问策略

GMS 对 RPC 和 Channel 的访问检查等价于：手表角色直接允许；包过滤开关关闭或该节点没有有效限制时也允许；只有手机角色且存在有效限制时才检查包名单。之后仍要经过接收者 AppKey 和路径路由。手机本地的 sendMessage、sendRequest、openChannel 可能返回 4004，这是本机 API 的结果，线上没有同名的错误帧。

限制通过 SyncStart.tag4 同步，由包名、Data URI 过滤和能力过滤组成。首次连接时服务端配置的限制为空，**首次设置之前不需要先安装 Mobvoi 节点策略**，这个字段也不要求对端证明持有 APK 私钥。持久化的限制损坏时应恢复为只允许所有者包，不能当成没有限制。

普通服务端仍要求：配置已启用、远端 MAC 与已绑定的地址一致、CONNECT 的迁移状态以及节点、网络、版本与记录相符。迁移配置会暂时只挂控制面，RPC 写入方可能还没注册，此时没有回复与包名单拒绝是两回事。普通首次设置不得设置 `isMigrating=true` 来绕过状态。

## 6. 三套密码过程

### 6.1 Android 普通主链路

由 Android secure RFCOMM 提供蓝牙层安全，然后交换 CONNECT。type 1/5 路径不启用 type 3 专用的 A2A/UKEY2。CONNECT 字段、AppKey 摘要和 MessagePiece 校验和都不是密码学挑战。这条链路不需要加载任何厂商原生库。

### 6.2 GMS type 3 A2A 与 iOS 认证

CryptoMessage（WearMessage.17）：1=握手 bytes，2=32 字节的恢复认证码，3=初始标记 bool（只见过 true，用途未确认），4=enum 0..2（1 已保存会话，2 新会话）。它使用 P256_SHA512 UKEY2、16 字节用户确认码、30 秒期限、D2D 确认，以及 RESUME/CLIENT/SERVER 派生。完整的原生密码状态和恢复分支未确认。

手表的 L2CAP 入口还支持 WearMessage.19（真正的 oneof）：1 challenge（256 字节随机数），2 response（appId、keyIdentifier、attestation、assertion），3 result（1 失败、2 成功、3 未知密钥）。它在该入口的 CONNECT 之前进行，使用 Apple App Attest 或已保存的 EC 密钥，不是 Android RFCOMM 的前置步骤；手机端的 GMS 没有声明 tag 19。未知的 type 3 和 iOS 数据应原样保留。

### 6.3 CDM 权限流的 SecureChannel

`/cdm/channel/permission_sync` 上是 Android 系统自己的协议，应用不得直接写入 CDM 的逻辑帧。本实现（Android 14+）使用正式的 CDM API，把有序的双向字节流原样交给 system_server；由系统完成 UKEY2/D2D、Android KeyStore 证明和 PermissionController 备份。应用不需要厂商私钥，也不需要冒充 AndroidSystem，但系统关联的归属、用户同意和平台权限必须成立。

以下格式只用于识别和诊断，业务代码不得自行构造来替代系统：

| 偏移 | 内容 |
|---:|---|
| 0..3 | int32 BE version=1 |
| 4..5 | int16 BE type |
| 6..9 | int32 BE payload 长度 |
| 10.. | 明文握手或 D2D 密文 |

type：HI=`0x4849`（明文角色 uint8：0 发起方、1 响应方，加 UKEY2），HF=`0x4846`（明文 ClientFinish），PK=`0x504b`（加密的 PSK token），AT=`0x4154`（加密的 DER 证书链，直接拼接），VR=`0x5652`（加密的 int32 BE 验证结果，1 为成功），SM=`0x534d`（加密的 CDM 逻辑帧）。D2D 的 AAD 是 version 和 type 共 6 字节，不含长度。

正常握手：发起方 HI（角色 0 + ClientInit）→ 响应方 HI（角色 1 + ServerInit）→ 发起方 HF（ClientFinish）。双方同时发起时，先比较 ClientInit 长度，再按 Java **有符号 byte** 逐字节比较，较小的一方改为响应方，不能换成无符号比较。权限逻辑帧可以先暂存在系统的有限队列中，安全连接建立后逐个封装为 SM；8192 字节的复制缓冲不是协议分包大小。

debug 平台仍使用 SecureTransport 和 UTF-8 `CDM` 作为 PSK，不是明文传输。PK 的明文为 `SHA-256(UTF8(role.name) || PSK)`；非 debug 的公开 API 默认不用 PSK，改走 AT/VR。本地 KeyStore 挑战为 `SHA-256(UTF8(localRole.name) || sessionUnique)`，验证对端证书链时使用对端的角色名；双方 VR 都为 1 才接受。

SM 明文：int32 BE messageType、int32 BE sequence、int32 BE payload 长度、payload。请求序号从 1 递增，回复沿用请求的序号；associationId 不在线上。messageType：上下文同步 `0x63678883`、ping `0x63807378`、权限恢复 `0x63826983`、失败 `0x33706573`、成功 `0x33838567`。ping 原样回显 payload；权限恢复正常时回复空的 SUCCESS，异常时回复空的 FAILURE。这个 SUCCESS 可能只表示监听器已返回，不代表所有权限都已恢复。

非 debug 平台默认双向 AT/VR，要求受信任的证书链、有效的挑战、TEE、已验证且锁定的启动状态，以及系统拥有的证明（attestation 的包集合恰为 `{AndroidSystem}`）。普通应用用自己 UID 的 KeyStore 生成的证明不满足“系统拥有”的要求，复制厂商原生库也只能提供密码运算，无法产生系统身份。安全补丁要求、信任根和平台实现随版本变化，业务层不应自己维护一套固定的判断。

**系统认证可能联网。** 验证方会读取证书吊销列表 `https://android.googleapis.com/attestation/status`，失败可能导致认证失败，因此不能承诺 CDM 权限传输完全离线。首次设置默认不执行 CDM 权限传输，只有用户明确选择后才按系统的实际结果进行。界面上不应把系统权限同步描述为“完全离线”。

## 7. 不打包厂商原生库

手表固件中的 `libukey2_jni.so` 是依赖系统私有库的 32 位 ARM JNI，无法从源码重建。它或许能复用 UKEY2/D2D 的字节格式，但会带来 ABI、链接、更新和分发上的依赖，而且仍然解决不了 AndroidSystem 证明、CDM 关联、权限备份格式和 Google 私有服务的资格问题。

本实现手机最低 Android 14，使用正式的系统 CDM API，普通主链路不依赖任何厂商原生库，仓库中也不包含厂商二进制。

## 8. 表端健康读取与本地优先

手表 Local Bridge 以自己的 UID 读取官方健康应用导出的 Provider，通过本地蓝牙把原始行送到手机入库和查询，见[本地桥接协议](local-bridge.md)。读取 TicHealth 的 `data_point` / `data_session` 不需要与厂商相同的签名。Provider 内部可能有初始化或读库的副作用，“不调用 insert/update/delete”不保证 Provider 完全不写库。

运动数据的 fitness Provider 虽然导出，但受 `com.mobvoi.ticwear.permission.FITNESS_DATA`（signature 级）保护，自建应用的 UID 会被拒绝。线上 AppKey、蓝牙配对和手机端 CDM 都不能改变手表上的 signature 权限。可选的 root 采集见[本地桥接协议](local-bridge.md)。

默认数据流为 Provider → 本地蓝牙 → 手机后端 → 本地数据库，本地资料不绑定 Mobvoi 云账号。Google / Mobvoi 云、网络代理和账号相关业务只在用户明确开启后使用，关闭时不调用。CDM、账号复制、云分析和本地健康同步不得合并成同一个必选开关。

## 9. 未确认

独立客户端已经走通的路径，不代表以下各项自动可用：Google 第一方账号复制和专用云接口、真实的 CDM 权限恢复、完整的跨平台 UKEY2、iOS App Attest、运动 Provider 的 signature 权限、所有 OEM 运行时覆盖和全部固件。遇到这些情况应保留“未知/不可用”及具体原因，不因为能构造出字段就宣称已获签名或权限，也不因为某个私有 API 受限就否定已经可用的本地功能。
