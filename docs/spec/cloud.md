# 账号、云与 Google 服务

本应用默认完全离线：健康数据走[本地桥接协议](local-bridge.md)，不需要任何账号。本文规定可选的厂商账号与健康云 REST、手表资料桥、Google 账号复制和 Google 私有服务的协议，以及官方应用自己的云同步算法。REST 的格式来自官方手机 App（Mobvoi Health 5.3）的实现，**没有在真实账号服务上验证过**；Google 相关的权限判断来自 GMS 24.28.35，不能推广到所有版本。

## 1. 默认关闭的能力

界面在用户明确确认后调用 `services.privacy.setEnabled(capability, true)`。以下能力默认全部关闭：

| `CloudCapability` | 允许的效果 |
|---|---|
| `MOBVOI_CLOUD` | Mobvoi 账号 REST、健康和运动云、向手表发送含真实账号凭据的 CompanionInfo，以及有云同步副作用的命令 |
| `GOOGLE_ACCOUNT_TRANSFER` | Google 账号复制和重新认证 |
| `GOOGLE_CLOUD_SYNC` | Wear 私有的 cloud opt-in 和 setting |
| `GOOGLE_LOCATION_SERVICES` | 首次设置中的 Google 网络定位选择 |
| `VENDOR_DIAGNOSTICS` | 诊断同意和系统日志设置 |
| `WATCH_INTERNET_PROXY` | [Flow15](flow15-legacy.md) 的 TCP/UDP 网络代理，可以承载厂商或 Google 的 HTTPS |

读取手机本地记录、云缓存和恢复本地凭据都不会联网；关闭云开关不删除本地健康记录。账号只由后端持有，界面状态中不含 session、token 或密码。Google 账号、Mobvoi 账号、蓝牙绑定、主 RFCOMM 身份和本地健康档案是不同的对象，不能用一个“已登录”概括。

这些是本应用自己的入口约定，不代表手表本身不会访问外网。首次设置的 CHECKIN 使用单独的 `checkinChoice`，默认 SKIP；只有用户选择 ATTEMPT 且手表声明支持时才查询，等待中可以立即跳过。它不隐式打开 Google 云开关或网络代理。

API：[MobvoiAccountHealth.kt](../../android/core/api/src/main/kotlin/dev/ticbox/companion/api/MobvoiAccountHealth.kt)、[GoogleServices.kt](../../android/core/api/src/main/kotlin/dev/ticbox/companion/api/GoogleServices.kt)、[CloudPrivacy.kt](../../android/core/api/src/main/kotlin/dev/ticbox/companion/api/CloudPrivacy.kt)。

## 2. Mobvoi 账号 REST

### 2.1 编码、身份与响应

默认 base 为 `https://ticwear-account.mobvoi.com/`，JSON 使用 UTF-8。公共 query 为 `pkg, platform=android, region, vn, vc`；region 为 `i18n` 或 `china`，Accept-Language 取用户语言。本应用使用自己的包名和版本，不冒充官方应用。可选的 `app` 参数在官方应用中的默认值未确认，本实现默认省略，不编造。

成功状态为 `status="success"`。登录响应：`result.session_id` 是之后的 session，`result.wwid` 是健康用户 ID，`result.info.account_id` 用于运动路径；info 还包括 `email, phone, nickname, head_image_url, birthday, sex, height, weight, pii, agree, region` 等，未知成员保留。只有拿到实际的 session 和 wwid 才算登录成功，HTTP 200 本身不够。

| method | path | 字段与效果 |
|---|---|---|
| POST | `/v3/login` | body `email` 或 `phone`、`password`，可选 `app` |
| POST | `/v3/login/captcha` | body `phone, captcha_value`，可选 `app` |
| GET | `/v3/captcha/sms` / `/v3/captcha/email` | query `phone` 或 `email`、`usage=login`、`language`；会发送验证码，虽然是 GET 也不能自动重试 |
| GET | `/v3/info/session-id` | query `session_id, phoneId, app`；刷新资料，缺少 phoneId 或 app 时不填猜测值 |
| POST | `/v3/info/update` | AccountInfo JSON 及 `wwid, account_id, session_id, app`；修改后用 GET 回读服务端规范化的结果 |
| POST | `/v3/logout` | query `session_id` |
| POST | `/time-zone/save` | body `wwid, accountId, timeZone`（注意是 camelCase） |

`password` 是用户密码 UTF-8 的 **MD5 小写十六进制**，外层仍是 HTTPS；这是兼容官方的格式，不是额外的保护。密码及其 MD5 不持久化，也不写日志。

update 不是 JSON Patch：本实现保留当前已知的 `email, nickname, head_image_url, birthday, sex, height, weight, pii, agree, region`，覆盖用户修改的字段后发送身份字段，再回读。资料中的身高和体重是字符串，不因本地绘图需要改成数值。

状态码 306、HTTP 401 和健康服务的 `err_code=400100` 都表示当前账号的 session 过期；403 本身不自动退出。没有可用的 refresh token 交换，过期后需要重新登录。

### 2.2 凭据、并发与取消

账号引擎按 generation 和 owner/session 租约区分请求；切换账号、退出或关闭会取消旧的工作，旧的 HTTP 结果不能写入新用户的缓存。退出时先删除本地凭据，再尝试一次远端撤销；远端失败仍视为已退出，本地删除失败如实报告 STORAGE。

Android 上的凭据保存在 noBackup 私有文件中，使用 AndroidKeyStore 中不可导出的 AES/GCM 密钥、随机 IV 和 AtomicFile，不保存密码。密钥失效或密文损坏时报告存储错误，不退回明文。健康 SQLite 不在这一层加密之内。

HTTP 实现：4 个工作线程、64 个排队、解压后 16 MiB 的响应预算、连接和读取超时，取消时关闭 socket；只跟随同源的重定向。只有可重放的读取在网络错误、408、429 和 5xx 时自动重试；POST 和 DELETE 不自动重试。这些是本应用的资源策略，不是服务端的协议常量。

## 3. 健康、运动与资料 REST

### 3.1 服务与 URL

| 类别 | 海外 | 中国区 |
|---|---|---|
| 普通健康数据 | `https://api-tichealth.mobvoi.com/` | `https://fitness.mobvoi.com/` |
| 运动记录 | `https://api-ticfitness.mobvoi.com/` | `https://health.ticwear.com/` |
| 健康资料和设置 | `https://api-tichealth.mobvoi.com/` | 同样使用这个 base，不能套用中国区的健康 base |

普通健康请求带 header `Token: <session_id>`，健康资料的 header 为 `token`（HTTP 字段名不区分大小写）；运动请求另带 query `sessionId`。`S` 是单个 URL 路径段的 data_source_name（必须正确地百分号编码，保留字面的 `+`），默认查询为 `derived:com.mobvoi.fitness`。`F/T` 是毫秒的起止值。`data_sources`（下划线）和 `data-sources`（连字符）必须按下表的拼写使用。

| method | path | 请求与响应 | 本实现 |
|---|---|---|---|
| GET | `/v1/users/me/data_sources` | 响应 `data_sources` | 已实现 |
| POST | 同上 | body `{device:{...}, application:{package_name, version}}` | 格式已知，未实现 |
| GET | `/v1/users/me/care/{care_wwid}/data_sources` | 关注账号的来源 | 未实现 |
| GET | `/v1/users/me/data-sources/{S}/data-sets/{F}-{T}` | 可选 query `data_type`，响应 `data_sets` | 已实现下载和缓存 |
| GET | `/v1/users/me/data-sources/{S}/data-sets/care/{care_wwid}/{F}-{T}` | 关注账号版 | 未实现 |
| PATCH | `/v1/users/me/data-sources/{S}/data-sets` | gzip JSON `{data_sets:[...]}`，响应 `data_set_responses` | 未实现上传 |
| GET | `/v1/users/me/data-sources/{S}/data-sessions/{F}-{T}` | 可选 query `activity_type`，响应 `data_sessions` | 已实现下载和缓存 |
| GET | `/v1/users/me/data-sources/{S}/data-sessions/care/{care_wwid}/{F}-{T}` | 关注账号版 | 未实现 |
| POST | `/v1/users/me/data-sources/{S}/data-sessions` | gzip JSON `{data_sessions:[...]}`，响应 `success_session_ids, repeat_session_ids, fail_session_ids` | 未实现上传 |
| GET | `/data/accounts/{accountId}/records/motion` | `timeBefore, timeAfter, size, motionType, noPoints`；`motionIds` 读取指定详情；响应 `records` | 已实现列表、详情和轨迹 |
| DELETE | 同上 | query `recordId` | 会删除云端记录；远端成功后才删除本地缓存 |
| GET | `/user/userInfo` | query `wwid`，header `token`；响应 `user_info` | 已实现 |
| GET | `/v1/users/setting/base` | query `type`；响应 `list[{type, value}]` | 已实现 |
| POST | 同上 | JSON `{type, value}` | 已实现；之后用 GET 确认 |

上传请求带 `Content-Type / Accept: application/json` 和 `Content-Encoding: gzip`；只有 body 存在且 encoding 恰为 gzip 时才压缩，并附加 query `encoding=gzip`，Content-Length 为压缩后的长度。下载和账号请求不压缩。

健康服务的成功为 `err_code=0`。会话上传时 success 和 repeat 中的 ID 都记为已同步，但不能据此声称服务端是 exactly-once。官方在 data-set 的逐项错误码全为 400102 或 400103 时也视为完成，这两个码的含义未确认，**不能命名为“重复”**。

### 3.2 JSON 对象

| 对象 | 字段 |
|---|---|
| DataSource | `data_source_name:string, data_source_id:long, device:{id, type, model, version}, application:{package_name, version}` |
| DataSet | `data_source_name:string, time_zone:string, min_start_time_ms:long, max_end_time_ms:long, points:[]` |
| DataPoint | `data_type:string, start_time_millis:long, end_time_millis:long, deleted:可选 int/bool, fp_val:double?, int_val:long?, string_val:string?` |
| DataSession | `data_source_name, session_id, activity_type, start_time_ms, end_time_ms, time_zone, deleted, points` |
| Sport | `id, device_id, type, start_at, end_at, total_motion_time, total_distance, total_calorie, total_steps, avg_heart_rate, gps_type, track_points` |
| Sport.track_points 的项 | `timestamp, wall_clock_timestamp, gps_point, gps_state, heart_rate, cumulative_steps, cumulative_distance, velocity, elevation, calorie` |
| user_info | `birthday, gender, height, weight, unit, goal_today_active, goal_today_exercise, goal_today_step, expected_sleep_time, expected_get_up_time, heart_rate_warning_fitness_upper_limit, heart_rate_warning_rest_lower_limit, heart_rate_warning_rest_upper_limit` |
| setting | `type, value`；未知的设置名和值保留，value 可以是任意 JSON 值 |

DataSet 外层用 `_ms`，point 用 `_millis`，不能混用；`time_zone` 原样保存。0 与缺失不同；fp 和 int 可以同时有值。官方上传时把本地的 values 字符串放在 `string_val`，下载时优先取 string，其次非零的 fp，最后 int；本实现同时保存三个字段，避免丢掉 0。`data_type` 是健康类型名（见[健康数据](health.md)）。GPS 的原始字符串和 `gps_type` 保留，不猜测坐标转换和单位。

### 3.3 手机缓存与查询

云缓存的所有者包含 region、wwid 和 accountId，SQLite 主键为 `(owner_key, kind, record_key)`。point 的身份为来源、类型、起止时间，缺失时用规范化 JSON 的摘要；session 用来源和 session ID；运动用原始记录 ID。未知的 JSON 保留。界面通过 `services.healthRecords` 查询，`services.health` 提供类型化的接口。本地设备档案与云账号完全独立。

`deleted` 作为墓碑保留，默认隐藏，可以显式查询；响应中没出现的旧记录不等于删除。每段的可用记录和完成的时间窗在同一事务中提交；列表缺失或类型错误时保留可用的相邻项并标为 PARTIAL，不把该时间窗记为完整。只有显式的 `[]` 才可能是成功的空结果。`noPoints` 的概要不会清除已下载的轨迹。

运动分页用最早的 `start_at` 推进 `timeBefore`，边界重叠时按 ID 去重，不猜测隐藏的 pageToken；游标不前进或页数预算用完时标为 partial。取消、退出或切换账号后，不提交过期所有者的结果。

## 4. 手表资料桥

以下是伴侣路由上的普通 Message（见[伴侣业务消息](companion.md)）。只有开启了 Mobvoi 云、有真实账号且当前 session 可用时才发送；不能用空 session 假装解绑，也不能把它当作普通的语言设置。

| path | 方向、内容与效果 |
|---|---|
| `/companion/send_companion_info` | P→W，完整 JSON；手表保存账号和显示偏好，可能启动绑定信息的上传；没有业务确认 |
| `/companion/login` | W→P，空；已登录时回复上一条，未登录时设置 `watchLoginRequested`，由界面提供入口 |
| `/companion/sync_account_info` | W→P，紧凑 JSON `b, g, h, w, u, t?`；手机使用 b/g/h/w/u，缺失的项保留旧值 |

完整 JSON 的字段（精确拼写）：`accountId, birthday, capabilities, dateformat, deviceId, email, headUrl, height, isVpa, model, nickName, phoneNumber, sessionId, sex, systemVersion, temperature, unit, versionCode, versionName, weight, wwid`。isVpa 为 boolean，其余为字符串。主机型号、SDK、版本、设备 ID 和能力取自真实的 Android 环境和已有设置，不编造官方的激活凭据。

紧凑格式：b 是 Unix **秒**的生日，按明确的时区转为 `yyyy.MM.dd`；w 和 h 是数值，这里没有单位换算；g 是健康的性别编码，与账号的 sex 不同：国际版 `g=2 → sex 1`，其他为 0；国内版 `g=0 → sex 1`，其他为 0。另外保存 healthGender 的原始编码。国际版 sex 名表为 `UNKNOWN / MALE / FEMALE / NON_BINARY`（默认索引 1），国内版为 `MALE / FEMALE`；无法可靠转换的值保留原样并省略名称。u 是单位字符串；t 是当前毫秒，这一分支不使用。

手表的 AccountInfoProvider 有本机权限和签名检查，拒绝伴侣写入空值。没有确认存在 `/companion/logout` 之类的无线端点，所以退出手机上的 Mobvoi 账号不能说已删除手表上的账号。TicYcx 的 `token / userId / dateFormat / timeFormat` 属于另一套格式，不能映射到主 sessionId、wwid 或 dateformat。

## 5. Google 账号复制

### 5.1 入口

这一功能把手机上已有的 Google 账号复制到手表，也支持重新认证。它与蓝牙配对身份、CDM 权限同步和本地健康读取无关；本应用完成首次设置并同步健康数据不需要它。默认不启动、不枚举账号、不绑定 GMS。

手表发送普通 Message `/accounts/start_host_activity`，body 为 DataMap `transfer_options:DataMap{reauth:bool}`（null 表示没有选项），没有专用的确认。只有用户在界面上操作或开启该功能后，手机才响应：在当前伴侣路由上打开 `/accounts/sync` Channel。Channel 经过本应用的主会话，内层的账号数据是 GMS 产生和消费的不透明字节。

### 5.2 流帧与 protobuf

双向流是连续的 `I32BE 长度 + protobuf envelope`，与 Wear Channel 的 DATA 或 MessagePiece 边界无关。envelope 由多个 optional 字段组成，**不是 oneof**：

| envelope 字段 | 内容 |
|---|---|
| #1 | 外层枚举，默认 1，已见 1/2/3/4 |
| #2 | Command：`#1 enum (START=2), #2 repeated Pair` |
| #3 | Status：`#1 enum (STARTED=2), #3 repeated Pair` |
| #4 | OpaquePayload：`#1 bytes` |
| #5 | 扩展：`#1 int32, #2 string, #3 int64, #4 string, #5 int32`，用途未确认 |

Pair 的字段 1、2 都是字符串，没有发现使用方，不能自造凭据字段。START 的测试向量：

```text
00 00 00 04 12 02 08 02
```

START 不写外层类型；从 GMS 发往手表的 payload 设置外层类型 3 和 `#4.#1` 字节。流程：打开 Channel → 发送 START → 等待手表的 status 2 → 启动 GMS 的 DirectTransfer → 双向转发不透明字节。未知的枚举或额外字段不能当作成功。本实现只做转发，不解析密码、token 或恢复密钥，也不保存不透明数据。编解码见 [GoogleAccountProtocol.kt](../../android/core/protocol/src/main/kotlin/dev/ticbox/companion/protocol/GoogleAccountProtocol.kt)。

### 5.3 手机上的 Binder 与管道

以下是手机本机的 IPC，不是线上格式。Parcel 中的 Binder 和文件描述符不能发送到手表。

| 服务 | 约定 |
|---|---|
| broker | `com.google.android.gms.common.internal.IGmsServiceBroker` transaction 46；调用方为真实的 `Context.packageName`；GetServiceRequest version 6，client version 12451000 |
| DirectTransfer | serviceId 210；action `com.google.android.gms.smartdevice.directtransfer.SourceDirectTransferService.START`；descriptor `com.google.android.gms.smartdevice.directtransfer.internal.ISourceDirectTransferService` |
| start / abort | transaction 1 / 2，oneway；start 的参数为 callback、config、两个 PFD 和 listener；abort 带 callback |
| start 回调 | `com.google.android.gms.smartdevice.directtransfer.internal.IDirectTransferCallback` transaction 1，Status；接受只表示可以开始 |
| 结果监听 | `com.google.android.gms.smartdevice.d2d.internal.IDirectTransferListener`：transaction 1 为账号结果，2 为 Google 的 PendingIntent，3 为状态和错误字符串 |

GetServiceRequest 的 SafeParcel 字段：1 int version=6，2 int serviceId，3 int client version=12451000，4 string 调用方包名，6 空 scopes，7 空 Bundle，10 / 11 空 feature 数组，12 bool true，13 int 0，14 bool false。broker 回调 `IGmsCallbacks` 的 code 1 / 3 返回 status、IBinder 和 Bundle 或 ConnectionInfo，code 2 是校验回调；只有 status 为 0 且 descriptor 匹配的 binder 才算就绪。

配置 SafeParcel：字段 1 / 3 为标题文本，4 为手表图标，5 为跟随系统的主题，6 bool 是否显示账号选择，7 为选择器选项，8 / 9 为进度文本，11 为显式的账号列表（元素的字段 2 为名称、3 为类型），12 int 用户验证偏好 = 0。选择器选项的字段 2 / 3 为标题，4 bool allowSkip = !reauth，5 到 8 为 false。不请求只有 Google 签名才有的偏好 1，也不实现受监护儿童账号的模式。具体的界面文本不是线上常量。

创建两对管道：GMS 拿到 `{toGoogle[0], fromGoogle[1]}`，手机用 `toGoogle[1]` 写、用 `fromGoogle[0]` 读；把 GMS 那端提交给 Binder 后关闭本机的副本。每个方向只有一个写入方。监听的 transaction 1 返回 AccountTransferResult 列表，字段 3 的 result=1 表示该账号成功；不记录账号名。

| 结果 | 条件 |
|---|---|
| COMPLETE / PARTIAL | 至少一个账号成功 |
| FAILED | 没有成功、至少一个失败 |
| SKIPPED | 结果为空，代码 10800 |

已知的错误码：10598 取消；10800 跳过；10757 / 10758 / 10759 超时；10562 没有源账号；10563 / 10576 蓝牙失败；10580 网络失败；10703 被手表阻止；10801 没有可复制的账号。其他代码原样保留。start 回调、Channel 写入和界面返回都不是最终结果。

Google 的 PendingIntent 保存在运行时的注册表中，API 只提供 token；前台 Activity 取得 IntentSender，按 ActivityResult 启动，返回后仍要等 GMS 完成。取消、断线或结束时移除 token，关闭 Channel、管道和绑定；未完成的 start 尽力发送 oneway abort，但已经复制的账号无法撤销。本实现的预算为：打开 30 秒、等待手表 STARTED 60 秒、全流程 10 分钟、broker 30 秒、帧 1 MiB、队列 16。

## 6. Google 私有服务的权限

GMS 24.28.35 中 DirectTransfer 默认 `restrict-third-party=true`，只有 Google 签名或批准的调用方可以使用。批准名单按**实际安装的包名 + 单一签名证书的 SHA-256** 匹配，官方伴侣 App 在名单中，例如：

```text
com.mobvoi.companion.at:639250F44A41120E225636905835BC2C142524DED45C40136258167B74665669
```

本应用 `dev.ticbox.companion` 不在名单中。线上的 AppKey、修改包名、复用厂商的原生库或跳过主机界面都不能产生官方的签名，普通的 OAuth 项目也不能解决这一限制。GMS 的版本、实验 flag 和设备配置可能不同，所以本实现仍然真实地连接服务，返回真实的 SecurityException、broker 状态或超时，不硬编码“一律拒绝”。broker 的非零状态也可能是服务缺失、版本或配置问题，不全是签名限制。

Wear 私有的 cloudSync 同样检查调用方：Wear 证书、厂商包签名白名单、系统 Wear 或厂商主屏配置，最后是 Google 或平台证书。这一授权检查没有在实际的 GMS 上运行过，不能断定一定拒绝或允许。**真实的 Google 账号复制和 cloud API 都没有在设备上成功过。**

CDM 权限同步是另一个功能：Android 14 起把 `/cdm/channel/permission_sync` 的原始 Channel 输入输出交给系统的 CompanionDeviceManager transport，见[配对与首次设置](pairing-setup.md)。

## 7. Google 云同步与诊断同意

Wearable 服务 ID 14，action `com.google.android.gms.wearable.BIND`，descriptor `com.google.android.gms.wearable.internal.IWearableService`。transaction 48 为 `optInCloudSync(enabled)`，50 为 `setCloudSyncSetting(enabled)`，参数为 callback 和 bool。`com.google.android.gms.wearable.internal.IWearableCallbacks` 的 transaction 11 返回 Status，只有真实的成功状态才算完成。opt-in 和随后的 setting 是两次不同的调用，不能用 DataItem 伪造私有 cloud API 的结果。

诊断同意：在 companionData 命名空间读取并修改 `/system_logging/settings` 的 DataMap，只替换 `system_logging_enabled:bool`，保留其他条目和未知的线上内容。写入持久化并在本会话提交后记为完成，远端的同步进度另外观察；**这不等于手表已开启报告或服务器收到了日志**。旧 DataMap 解析失败时保留原值并返回失败，不用空的 DataMap 覆盖。`system_logging_enabled_le` 等其他同意模式未实现。

首次设置中的各项同意相互独立：SKIPPED 不做更改，一项失败不影响后续结果；cloud opt-in、诊断 DataItem 和网络定位分别报告。

## 8. 官方应用的云同步算法

以下是官方应用自己如何把数据同步到云端，供理解手表上的 `synced` 等字段，本应用不实现这些上传。

### 8.1 TicHealth：按待同步行上传

手表上的 TicHealth 自己就有 HTTP 上传路径（base `https://api-tichealth.mobvoi.com/`），不是先把数据库经蓝牙导出到手机。它在自己的数据库中维护待同步状态，选择未确认的数据上传，收到成功响应后再确认：

```sql
-- 普通 POINT
SELECT * FROM data_point
WHERE synced=0 AND wwid=:currentAccount AND time_to<=:cutoff
ORDER BY time_to DESC LIMIT 2000;
-- 已结束的 SESSION
SELECT * FROM data_session
WHERE time_from>0 AND time_to>0 AND synced=0
  AND wwid=:currentAccount AND time_to<=:cutoff
ORDER BY time_to DESC LIMIT 2000;
```

两者都不按 `_id` 分页，也不排除 `deleted=1`；结束时间较新的优先，积压很多时一轮不保证清空。每轮先异步提交一批会话；如果还有结束超过 30 分钟的未同步会话，本轮暂停普通 POINT 的上传，否则再提交一批 POINT。每轮各取一批，没有“成功后立即取下一批”的循环。

- **POINT**：按设备和类型分组，每个点包含类型名、起止毫秒、原始 CSV（`string_val`）和非零的删除标记，`PATCH .../data-sets`。成功后按**所选点的 time_to 区间、账号和类型**执行 `UPDATE data_point SET synced=1`，不是逐 ID 确认，也不过滤 device_id，所以 HTTP 期间写入同一区间的其他行可能被一起标记。
- **SESSION**：附带同设备、同账号、time_to 在 `(start, end]` 内的所有 POINT（不看 synced，不受 2000 的限制），`POST .../data-sessions`。只有响应中与本次提交匹配的 success 和 repeat ID 才确认；**确认会话不会确认它附带的点**，这些点之后还可能再随普通 POINT 上传一次。
- 上传前把 device_id 映射为 data_source_name，缺失时先注册来源并缓存。

睡眠修改能重新上传：官方保留会话的 UUID，结束、延长或合并时构造新的会话对象，复制 ID、设备、账号、时间和 deleted，但**不复制 synced**（默认 0），然后按同一 UUID 全字段 UPDATE，rowid 不变。所以已上传的冗余会话用软删除，未上传的可以直接物理删除。普通 POINT 不都是这样：当天心情的修改直接更新旧行，不清除 synced。

手表的调度：每小时一次的周期任务（需要网络），联网、界面刷新、同步广播和对应的 Message 也会触发；两次尝试至少间隔 10 秒，正常的后台间隔超过 60 分钟（强制同步跳过 60 分钟，但不跳过 10 秒）。持久的进度只有行的 synced；失败的行等下一次触发重选，没有持久的退避队列。手机 App 也有同样的上传算法，但不受这两个间隔限制。

### 8.2 TicFitness：按整场训练上传

候选为当前账号下 `END_TIME>0 AND DELETED=0 AND SYNCED<>3` 的摘要，再检查 `(SYNCED & 1) == 0`（bit0 为云端，bit1 为预留的另一个客户端）；只有未被云端确认的训练才加载全部 POINT。所有待传训练的完整摘要和嵌套的 `track_points` 组成一个 `records` 数组，gzip 后 `POST /data/accounts/{account-id}/records/motion`；没有逐点的水位或分页，失败时下次重传整场。

成功（`err_code=0`）后按请求中的 HASH 设置 bit0，响应没有逐点的回执。之后的摘要或采样更新**不会清除** bit0，依靠的是“训练结束后才上传一次”的业务约束。删除另有闭环：没被任何客户端持有的训练直接删除；已上传的先标 DELETED、删除 POINT，再按 HASH 向云端发 DELETE，所有客户端确认后才物理删除 RECORD。

### 8.3 Health Connect 与 Google Fit

官方手机 App 写入 Health Connect（设备上的数据库，不是 Google 云）时，三类数据分别保存一个时间水位：首次从当前时间减 7 天开始，普通健康按 time_to，睡眠按会话结束时间，运动按 START_TIME 筛选却以最大的 END_TIME 推进。睡眠 UUID 和运动 HASH 映射到 Health Connect 的记录 ID，已映射的直接跳过，不比较内容，也不更新或删除已有记录。水位都是采样或会话时间，不是修改序列，不能保证补写、更正和删除不遗漏。

这个入口在写入前还会尝试从 Mobvoi 云预取数据，并异步上传待同步的普通健康数据，所以即使目的地在本地，也可能产生云端的副作用。Google Fit 只是通过 Mobvoi 的 OAuth 接口绑定，App 中没有逐条上传到 Google 的代码，服务端如何转发未知。

## 9. 诊断与未实现

诊断只记录操作、阶段、HTTP 或服务的状态码、计数、异常类型和未知字段的数量，不记录完整的 URL query、请求或响应正文、session、密码、账号、Google 的错误字符串或健康值。

未实现或未验证：真实的 Mobvoi 登录和当前服务器的兼容性、`app` 参数的默认值、注册、第三方授权绑定、头像上传、关注账号、普通 data-set 和 session 的上传、云端的高级分析；Google 私有服务的实际授权和账号复制的完成；手表账号的无线退出；首次设置 CHECKIN 的真实联网行为。未完成的功能如实返回 UNAVAILABLE、FAILED、PARTIAL 或 SKIPPED，不伪造成功，也不用云数据冒充本地采集的数据。
