# 伴侣业务消息

本文规定手机与手表之间的业务消息：路径、方向、承载方式、回执和副作用。主连接、Message/Request、Channel、DataItem/Asset 和能力编码见[主传输](transport.md)；首次设置见[配对与首次设置](pairing-setup.md)；AppKey 与 Android 权限的关系见[身份与权限](identity.md)。健康数据不搭载在通知或步数消息里，见[健康数据](health.md)；账号与云见[账号与云](cloud.md)。

未知字段、枚举和原始值应保留并记录处理决定，不能因为缺少未经证明为必填的字段而拒绝整条消息。

## 1. 约定

`P→W` 表示手机到手表，`W→P` 反之。`Message` 是普通单向 MessageApi，即使路径中含 `/rpc/` 也不会变成 RequestApi；`Request` 才有外层的回复 token 和回复；`Data` 是持久 DataItem；`Channel` 是双向字节流。除非明确写出长度头、JSON 或 DataMap，protobuf 直接作为业务数据，没有额外的包头。

| 身份与路由 | 适用范围 |
| --- | --- |
| 当前会话的伴侣路由，默认 `com.mobvoi.companion.at / 5cc9866db03e530a21f842a421c7eb42bac58961` | Mobvoi 设备消息、通知、电话、媒体、设置、应用目录、表盘、锁屏、账号 Channel。按当前确认的配置路由，不因名称带 Google 就改用其他身份 |
| `com.google.android.gms / 38918a453d07199354f8b19af05ec6562ced5788` | 找手机（MDM）消息及其 `ringable` 能力 |
| GMS 外层 Data 命名空间 + URI 内的伴侣 AppKey | 能力公告，见[主传输](transport.md#9-能力公告与手表连接状态) |
| 独立的 TicYcx/MWF AppKey | `/ycxbp/*` 和表盘 ZIP Channel；调用方必须显式提供，不能默认用伴侣或 GMS 身份 |

路由只是线上的选择器，不会授予同名包的 Binder 或系统签名权限。所有引擎都绑定物理的 `peer + sessionId`；迟到的旧会话回调、取消和断线不得作用于新的连接。结果状态含义各不相同：`written`、`PERSISTED_AND_SUBMITTED`、`SUBMITTED_UNVERIFIED` 只表示本机持久化或发送完成；`REMOTE_UI_OPENED` 只表示对端打开了页面；`DISPATCHED` 只表示 Android 接受了本机调用。不能统称为“操作成功”。

本实现的状态：

| 功能 | 已实现 | 未实现或未确认 |
| --- | --- | --- |
| 表信息、电量、步数 | 读取、解析、历史观察和查询 | 完整电量 proto 和动态振铃的专用功能 |
| 通知 | 系统通知监听、过滤、DataItem/Asset、动作和快捷回复、撤回、重连恢复 | 复杂样式的显示、回复和双向取消需逐项在设备上确认 |
| 电话 | InCallService、状态同步，拨号、接听、挂断、保持、拒接、DTMF、静音，系统音频端点 | 系统授权和 HFP 依赖设备；Google Dialer 的独立业务未实现 |
| 媒体 | 系统 MediaSession、传统 Data/Asset、命令、可选 v2 | 并非所有播放器和封面都逐项确认；adaptive 分支未实现 |
| 找设备 | 手机本地响铃和振动、GMS 回执；自建的手表响铃页和本地反馈，见[手机找手表](watch-finding.md) | — |
| 设置、应用、锁屏 | 语言、时间制式、信息、应用目录和图标、远端设置、卸载确认、锁屏流程、心脏健康开关 | 双向的勿扰和剧场模式同步 |
| 表盘 | 目录、当前表盘、收藏、编辑 Channel、照片、本地 ZIP 传输 | ZIP 的身份和安装完成确认；商店和付费不在本地客户端实现 |
| 截图、SOS、部分历史消息 | 完整的已知格式和离线编解码 | 专用的生产功能（SOS 见第 11 节） |

纯协议编解码在 [core/protocol](../../android/core/protocol/src/main/kotlin/dev/ticbox/companion/protocol/)，界面契约在 [core/api](../../android/core/api/src/main/kotlin/dev/ticbox/companion/api/)，业务引擎在 [core/engine](../../android/core/engine/src/main/kotlin/dev/ticbox/companion/engine/)。离线消息检查工具为 [src/mobvoi-message.mjs](../../src/mobvoi-message.mjs)。

## 2. 设备信息、资料与健康提示

以下都是伴侣路由上的普通 Message，JSON 为 UTF-8。

| 路径 | 方向与内容 | 回复与效果 |
| --- | --- | --- |
| `/companion/sync_wear_info` | P→W 空查询；W→P `WearInfo` JSON，连接时手表也会主动发送 | 同路径的 JSON，不是 RequestApi |
| `/companion/sync_wear_battery` | P→W 查询，手表忽略内容；W→P 十进制电量百分比文本 | 回复给请求节点、同一路径，没有额外 JSON |
| `/companion/get_phone_battery` | W→P，手机忽略请求内容 | 手机以 JSON 回复；完整字段和手表的使用方式未确认 |
| `/companion/setup_wizard` | W→P `{"time":epochMillis}`，手表完成设置后发送一次 | 不能代替 Setup 完成状态 |
| `/companion/battery_full` | W→P `{"time":epochMillis}`，充满或充电且 ≥99% 时 | 官方手机端检查本地开关、且时间差小于 10 分钟才提醒；本实现无专用提醒 |
| `/companion/launch_intent` | P→W JSON `{intent:string,type:int,permission:string?}` | 手表执行 `Intent.parseUri(intent, 1)`；type 0 Activity、1 Service、2 广播（可附权限）；没有结果回复。编号与第 3.3 节的 `start_mode` **不同** |
| `/health/cmd_new_wear_push_to_phone` | W→P JSON `{current_step:int,goal:int}` | 官方手机端回复同路径 `{messageId:long,status:0}`；手表不等待这一回复 |
| `/health/cmd_wear_push_to_phone` | P→W 空内容 | **触发官方健康应用的云同步并上报步数**，不是本地健康历史导出。本实现默认不使用厂商云，不用它代替本地同步 |
| `/health/cmd_wear_pull_from_phone` | P→W `{goal:int,current_step:int}` | 手表只在 goal>0 时更新目标；无回复，不会改写已有步数 |
| `/health/open_wechat_qr_ticket` | P→W ticket 文本 | 打开手表上带 `qr_ticket` 的页面；无回复 |
| `/health/cmd_change_heart_health_switch` | P→W ASCII `1` 显示入口、`0` 隐藏 | 修改功能入口的可见性；隐藏时同时关闭监测，显示不会自动开启监测；无回复。见[监测设置](monitoring-settings.md) |
| `/health/sync_step_to_wechat` | W→P 十进制步数文本 | 本实现不实现微信功能 |
| `/health/sleep/share` | W→P sleepId 文本 | 官方手机端打开分享页；本实现不实现 |

`WearInfo` 的字段：`wearType, wearVersionNumber, isMfiSupported, wearDeviceId, wearCapability, region, skuTheme, skuColor, btName, btAddress, hasNfcFeature, ticwatchChannel, sn, certModel`。`wearVersionNumber` 是 **Android SDK_INT**，不是固件版本号。不能假定所有字段每次都有，或类型总是相同。解析电量时接受 BOM、空白、数字字符串和等价的整数表示；回复异常时保留之前的值并记录，不伪造新的观察。

旧的 `Recorder` 模型还声明了 `cals, distances, durations, heart_rates, motionTypes, steps, time_list`（`{time:int,value:int}` 数组）以及 `startTime, endTime, interval, total, goal, health_count_goal, health_count_start, health_time_goal, err_code, err_msg, current_step, messageId`，但官方手机端只使用 `messageId` 和 `current_step`，不保存这些数组。模型里的声明不能证明存在批量历史发送；本实现的健康数据来自[本地桥接](local-bridge.md)。

### 2.1 旧的运动消息

以下是官方手机端仍保留的处理入口，当前手表固件没有对应的发送方。普通 Message，UTF-8 JSON；只有这几个运动消息在内容以 `1f 8b` 开头时按 gzip 解压，其他 Mobvoi 消息不解压。

| 请求 → 回复 | 请求内容 | 结果 |
| --- | --- | --- |
| `/sports/delete` → `/sports/delete/re` | JSON 数组，每项 `a:accountId, rid:recordId, t:可选` | 删除运动数据；成功 `{"s":200,"c":"[\"record-id\"]"}`，其中 c 是**再次 JSON 编码的字符串** |
| `/sports/profile` → `/sports/profile/re` | `{a,t,birth,sex,weight,height}` | birth 是出生**年份**；更新资料；成功 200，失败可能为 621 |
| `/sports/share` | `{a,t,rid,ty}` | 打开手机分享页；没有统一的回复 |

基础检查：请求为空 412，a 缺失或不匹配 401，成功 200。没有幂等保证。`/health/data_set/*`、`/health/session/*`、`/sports/list/detail/record` 只是常量或空接口，不是可用的蓝牙历史接口。

## 3. 设置、应用目录与远端 Intent

### 3.1 Setting protobuf

通用路径为 `/protogensettings/<feature>/<name>`；共享设置直接使用，手表专属设置再追加 `/<watchNodeId>`。只有伴侣本身读写的设置可以只保存在手机上，不能因为定义了 Spec 就发送无线 DataItem。

```text
Setting: #1 SettingValue, #2 int64 last_changed_ms
SettingValue oneof: #1 int32, #2 bool, #3 bytes serialized,
                    #4 string, #5 float, #6 int64
```

修改时间取 `max(now_ms, 已有最大值 + 1)`，冲突时以修改时间较新的为准。保留原始的未知字段；不得用一个裸 bool 覆盖整个 Setting。

| 路径 | 值与方向 | 说明 |
| --- | --- | --- |
| `/protogensettings/supported_locales/supported_locales` | P→W serialized bytes，内含 `#1 language_tags:string` | 设置语言；本地偏好为空时跟随手机 |
| `/protogensettings/time/is_24_hour_format` | P→W bool | 手表是否应用取决于其自动时间设置；本地为空时跟随手机 |
| `/protogensettings/phone_info/phone_version_info` | P→W int32 SDK_INT | 不是手机的 versionName |
| `call_sync` / `calendar_sync` | 仅手机本地 | 不自创手表设置路径；电话开关保存在手机上 |

本实现的 `DeviceSettingsClient` 先保存用户偏好，离线时留到之后的会话提交；Data Layer 写入完成后标为 `PERSISTED_AND_SUBMITTED`，不伪造远端应用的回执。语言和时间制式不使用 `/companion/send_companion_info`，后者有账号绑定和上传的副作用。

### 3.2 应用目录

W→P DataItem `/installed_apps/data/installed_apps/<package>`，内容为 protobuf：`#1 package_name:string, #2 app_name:string, #3 icon_asset_name:string, #4 is_launchable:bool, #5 notification_permission_default_state:int32`。图标是同一数据项上的独立 Asset（槽位名 `app_icon`）。数据项被删除即表示应用已从目录移除。目录不是应用安装协议。

### 3.3 Remote Intent

请求为普通 Message `/remote_intent/rpc`，结果为另一条 Message `/remote_intent/result`，两端都实现。DataMap 字段：

| 键 | 类型与含义 |
| --- | --- |
| `action`, `uri_data`, `mime_type`, `package_name` | string；Android Intent 字段，缺失时保持缺失 |
| `categories` | string[] |
| `extras`, `activity_options` | DataMap，只转换能明确表示的类型 |
| `start_mode` | int32：0 Activity、1 Broadcast、2 Service、3 WebView、4 第三方 Activity；手机不支持 WebView |
| `com.google.android.clockwork.actions.RpcWithCallback.invocation_id` | 可选 string；需要回调时填写 |

结果：同一个 invocation_id，加上 `com.google.android.clockwork.actions.RpcWithCallback.result:DataMap`，其中含 `…successful:bool` 和 `…message_id:int32`。手机写 1，手表写 2；message_id 不是请求 ID。手表生成的 ID 是 base36、模 46656，本地超时 5 秒；收到其他格式的字符串也应保留。超时不会撤销已经打开的页面。

本实现用 `start_mode=0` 打开手表的设置页面（MAIN、DISPLAY、SOUND、WIFI、BLUETOOTH、LANGUAGE、DATE_TIME、APPS、ABOUT、BATTERY），或发送 `action=android.intent.action.DELETE, uri_data=package:<pkg>` 请求卸载确认。回执成功只报告 `REMOTE_UI_OPENED`；应用从目录中消失才说明卸载完成。远端的组件解析和权限由手表的 Android 决定。

`/le_remote_actions/rpc` 与 `/le_remote_actions/result` 是另一套手表到手机的 DataMap 加回调，官方手机端有处理方，当前手表固件没有发送方。其中特殊动作 `com.google.android.wearable.action.SEND_SMS` 会发送短信，不能用作普通的设置探测。本实现未实现。

### 3.4 勿扰、剧场模式与动态振铃

手机 SDK 定义了 `/protogensettings/modes/interruption_filter`（int，缺省 1）、手表专属的 `do_not_disturb` / `theater_mode`（bool），以及仅手机本地的 `dnd_sync_enabled`（默认 false）。另有旧的 `/user_settings/state` DataMap：`interruption_filter:int, enable_user_engagements:bool, dcsd:ts:long, dcsd:ots:可选 long`。这些设置在两端实际由谁构造、由谁监听和应用，未能确认，**不能认定手表官方支持任一方向的勿扰同步**。本实现的勿扰同步走 Local Bridge，见[本地桥接](local-bridge.md)。

几种本地勿扰的取值不同：Google 手动勿扰开启为 filter 2、关闭为 1；OEM 计划勿扰开启为 4、关闭为 1；就寝模式又是独立状态。手表 MCU 的勿扰图标和计划设置是手表内部的 Binder，不在线上同步。不能把这些合并成一个 bool 消息。

动态振铃：P→W `/dynamic_ringer/enabled` `{enabled:bool}`，W→P `/dynamic_ringer/status` `{disabled:bool}`。后者在 `充电中 || !enabled || !佩戴中 || 省电模式` 时为 true，表示不应联动静音手机，**与勿扰无关**。本实现未接入。

## 4. 通知镜像

### 4.1 路径与内容

手机以伴侣路由发布 DataItem：

```text
/bridger/stream_item:<creatorNodeId>:<packageName>:<notificationId>:<encodedTag>[:<encodedNotificationKey>]
```

`notificationId` 是有符号 Int。五段和六段都要兼容；外层 `wear://<owner>/...` 的 owner 不一定等于 creatorNodeId。tag 和 key 的编码：先 `Uri.encode`，再把 `~` 换成 `%7E`，最后把所有 `%` 换成 `~`，例如 `a:b~中 → a~3Ab~7E~E4~B8~AD`，`+` 保持原样。tag 为 null 时写 `null_tag`（与名为 `null_tag` 的真实 tag 有歧义，按原协议保留）。删除时可能需要六段形式的 notificationKey，五段形式不保证能取消。

内容是 [DataMap protobuf](transport.md#7-datamap)，不是 JSON 或 Parcelable；不得根据字段名改写值的类型。15 种基础类型都要完整保留；不能因为缺少 title、icon 等未被证明为必填的字段而拒绝整条通知。

| 内容 | 键与类型 |
| --- | --- |
| 应用与文字 | string `app_name, title_html, title_raw, text_html, text_raw, sub_text_html, sub_text_raw, summary_html, summary_raw, big_title_html, big_title_raw, big_text_html, big_text_raw` |
| 时间与版本 | int64 `when, post_time_millis, revision`；revision 不是 DataItem 序号 |
| 标志 | int32 `notification_flags, color, defaults, user_id, group_alert_behavior` |
| bool | `show_when, only_alert_once, has_content_intent, is_group_summary, is_group_conversation, is_work_profile, is_small_icon_vector_drawable, is_emergency` |
| 分组与类别 | string `group, group_key, bridge_tag, category, conversation_title, display_name, display_name_raw, device_user_key` |
| Asset | `icon, app_icon, large_icon, big_picture, device_user_avatar, supplemental_data` |
| 嵌套 | `supplemental_assets: DataMap<Asset>`；`actions, wearable_actions, messages: DataMap 列表` |
| 手表还会读取 | `priority:int, vibrate:long[], has_sound:bool, inbox_lines:string[], sort_key:string, people, progress, uses_chronometer`；手机不一定生成 |

HTML 与 raw 分别保留。从纯文本生成 HTML 时转义 `&<>"'`，换行转为 `<br>`；不能把 raw 当作 HTML。聊天消息项：`sender_avatar / message_image:Asset`，`message_text / message_text_raw / message_sender / message_sender_raw:string`，`message_timestamp:long`，`message_data:string`（缓存键），`message_data_mime_type:string`。`pages` 可以作为嵌套 DataMap 保存，但手表不使用，不保证分页显示。

附件：对所有嵌套 map 按 Java 字符串（UTF-16）键序、列表按顺序深度遍历；每个 Asset 的**出现位置**依次分配一个全局 int64 下标，内层 type 13 引用该下标，外层槽位名为十进制 `0, 1, ...`、type 4，带摘要或文件。不得按内容或对象去重槽位；存储层可以合并相同摘要的数据。图片可能是 WebP，不能都当作 PNG。`supplemental_data` 文件是 proto `#1 map<string,bytes>`，不是位图。

### 4.2 动作、快捷回复与结果

动作 DataMap：`title_html:string, intent_id:string`，可选 bool `allow_generated_replies, action_inline, remote_content_intent` 和 `remote_intent:DataMap`；`remote_inputs` 是 DataMap 列表，每项 `return_key:string, label_html:string, choices_html:string[], allowFreeFormInput:bool`（大小写准确）。点击通知本身使用 `has_content_intent` 和 `content_intent_id`。不透明的 intent_id 由手机的注册表关联到真实的 PendingIntent，不能从 action_index 或 notificationId 推导。

W→P 普通 Message `/bridger/rpc`：

```text
rpc_command: string = "bridged_action"
remote_stream_item_id: string  （完整的 stream 路径）
action_index: int
is_wearable_action: bool
intent_id: string
com.google.android.clockwork.actions.RpcWithCallback.invocation_id: string
remote_input_results: DataMap {
  <return_key>: DataMap { string_result: string, char_sequence_html: 可选 string }
}
```

手机按缓存的 intent_id 和 RemoteInput 键执行，回复文字优先用 string_result，其次用 HTML。执行结果以 P→W 普通 Message `/bridger/result` 返回：相同的 invocation_id，嵌套 `…RpcWithCallback.result`，其中 `…successful:bool, …message_id:int=1`。success 缺失或类型错误时保持未知；不能用传输层的确认代替业务结果。手表 5 秒的回调超时不会撤销 PendingIntent。未知的 rpc_command 只记录，不伪造结果也不执行动作。

本实现只有在用户授予通知使用权后才读取通知和系统 MediaSession，只对所选手表的当前会话执行。过滤设置和排除本应用通知在引擎里处理。日志不输出通知正文、回复内容、intentId 或完整数据。

### 4.3 撤回与重连

撤回通知即删除相应的 stream DataItem，形成墓碑；不能用空标题代替。手表删除通知可能要求手机取消真实通知，需要抑制由此产生的循环；通知监听失效不能当作所有通知都被用户删除。重连后的重放应静默，不重复提醒，也不分配新的 revision。

`/bridge_mode/dismissal/<package>` DataItem：DataMap 中的 `dismissals` 列表，每项 `{id:string, timestamp:long}`。合并时手表保留 7 天内最多 100 条旧项再追加，手机保留 99 条旧项再追加，线上没有统一的 100 条上限。桥接模式优先读取 `bridge_mode_dynamic`，其次 `bridge_mode`，值为 1 时禁用，其他值启用，另有 tag 过滤。

## 5. 电话

都是伴侣路由上的普通 Message，内容为 protobuf。结构见 [google-companion-services-reference.proto](../../schemas/google-companion-services-reference.proto)，API 见 [Calling.kt](../../android/core/api/src/main/kotlin/dev/ticbox/companion/api/Calling.kt)，实现见[伴侣功能](../dev/features.md)。

| 路径 | 方向与内容 |
| --- | --- |
| `/call/rpc/reset_call_state` | P→W 空，清空手表缓存 |
| `/call/rpc/call` | P→W Call，更新或撤去通话状态 |
| `/call/rpc/call_audio_state` | P→W `#1 is_muted:bool` |
| `/call/rpc/silence_ringer` | P→W 空，来自系统的 onSilenceRinger 回调 |
| `/call/rpc/call_action_request` | W→P `#1 call_id:string, #2 action:int` |
| `/call/rpc/call_reject_request` | W→P `#1 call_id:string, #2 reason:int, #3 text_message:string` |
| `/call/rpc/call_dial_request` | W→P `#1 number:string` |
| `/call/rpc/call_dtmf_request` | W→P `#1 call_id:string, #2 code:string` |
| `/phone`（旧） | W→P **DataMap** `command:int=3, phone_number:string`；当前手表没有发送方 |

Call 字段：`#1 state:int32 (缺省 -1), #2 call_id:string, #3 handle_uri:string, #4 handle_presentation:int32, #5 caller_display_name:string, #6 caller_display_name_presentation:int32, #7 call_capabilities:int32, #8 call_properties:int32, #9 DisconnectCause:message, #10 connect_time_millis:int64, #11 video_state:int32, #12 creation_time_millis:int64, #13 contact_display_name:string, #14 direction:int32 (缺省 -1), #15 companion_can_answer_calls:bool (缺省 true), #16 VoipCall:不透明的嵌套 bytes, #17 extras:bytes`。#3 实际写的是 `handle.schemeSpecificPart`（例如号码），不强制 `tel:` 前缀。DisconnectCause：`#1 code:int, #2 label:string, #3 description:string, #4 reason:string, #5 tone:int`。只发布能从真实 Android 字段得到的值，不猜测 VoIP 或 extras 的 Bundle 序列化。

state 沿用 Android 不连续的数值：`-1 UNKNOWN, 0 NEW, 1 DIALING, 2 RINGING, 3 HOLDING, 4 ACTIVE, 7 DISCONNECTED, 8 SELECT_PHONE_ACCOUNT, 9 CONNECTING, 10 DISCONNECTING, 11 PULLING, 12 AUDIO_PROCESSING, 13 SIMULATED_RINGING`。direction：`-1 未知, 0 来电, 1 去电`。call_id 是绑定真实 Telecom Call 的不透明 UUID，未知 ID 不能改作用于另一通电话。

action：`0 unknown, 1 answer, 2 disconnect, 3 hold, 4 unhold, 5 mute, 6 unmute, 7 silence`；手机 SDK 另定义了 `8 answer_from_watch, 9 answer_from_phone, 10 route_audio_to_watch`，手表的枚举只到 7。手机处理 8、9（8 接听后请求把音频切到手表），没有处理 10 的分支，本实现对 10 报告不支持，界面可以单独请求真实的音频端点。8 已接听但音频切换失败时，仍报告 `DISPATCHED` 并附切换失败的原因。

空的 call_id 不是坏帧：answer 选择唯一正在响铃的通话，disconnect 选择唯一的当前通话；有多个候选时不猜。手表刚取消一个还没出现的去电时，本实现最多等 3 秒看是否出现 DIALING/CONNECTING；断线、会话更替或功能关闭都会取消这个窗口。reject 的 reason `0 unknown, 1 declined, 2 unwanted` 保留；是否附带拒接短信取决于 text 是否非空，通过 `Call.reject` 完成，不另外调用 SmsManager，因此 `DISPATCHED` 不代表短信已送达。DTMF 只接受单个字符 `0123456789ABCD*#`，300 ms 后停止，新的按键会替换旧的停止定时。

本实现保留用户的默认拨号器，使用受 `BIND_INCALL_SERVICE` 保护的无界面 InCallService。控制能力由 CDM WATCH 关联和系统**实际授予**的 `MANAGE_ONGOING_CALLS` 决定；`READ_PHONE_STATE` 提供粗略状态，`CALL_PHONE` 用于拨号。只有关联不代表已获授权，粗略状态也不能伪造完整的 Call ID。`full_companion_call_control_capability` 只在真正可用且启用时动态公告，撤销权限或关闭功能时撤回。手表的音频控制还需要 HFP，主 RFCOMM 中没有通话音频。音频路由使用 API 34 的 CallEndpoint 或公开的蓝牙音频接口，不调用隐藏的 `BluetoothHeadset.connect`；没有 HFP 时返回不可用。silenceRinger 可能因为不是默认拨号器或缺少签名权限而被系统拒绝，此时不通过修改全机音量或勿扰来伪装成功。

所有动作都没有业务命令 ID 或回执，不自动重放接听或拨号。状态发送可以有限重试，结果以 Telecom 回调为准；服务解绑时发送 RESET，但不假装手机真的挂断了电话。

## 6. 媒体

### 6.1 传统 DataItem

手机以伴侣路由发布 DataItem `wear://<手机节点>/mediacontrols/mediacontrols`，内容为 DataMap，图像放在外层 Asset，删除即撤回。W→P 同一路径的普通 Message 是命令，不是 RequestApi。下表中 `m.` 表示精确前缀 `mediacontrols.`。

| 键 | 类型与含义 |
| --- | --- |
| `m.artist, m.title, m.application_label, m.package_name, m.media_session_token, m.media_uri` | string |
| `m.playing, m.reserve_space_for_previous, m.reserve_space_for_next` | bool |
| `m.watch_app_auto_launch_enabled, m.watch_app_auto_launch_extra` | bool / string |
| `m.transport_flags` | int32，Android PlaybackState actions 的低 32 位；内部 API 保留 Long |
| `m.duration, m.position, m.position_update_time` | int64 毫秒；未知的 duration/position 为 -1；update 为 epoch |
| `m.playback_speed, m.volume` | float；volume = 当前/最大，未知为 -1 |
| `supports_absolute_volume, volume_increments_number, elapsed_time` | **没有 m. 前缀**，bool / int32 / int64 |
| `m.supports_thumbs, m.user_rating` | bool / int32；rating：-2 无值、-1 踩、0 未评、1 赞 |
| `m.controls_color_from_theme, m.dominant_color, m.media_theme_primary_color, m.media_theme_color_primary_dark` | bool / int32 / int32 / int32 |
| `m.media_notification_active, m.notifications.data.source` | bool；来自系统 MediaSession 时后者为 false |
| `m.artwork, m.app_icon, m.media_notification_icon` | Asset；本实现从本地位图生成 PNG，不联网下载 media_uri |
| `m.custom_actions` | DataMap 列表，每项 `m.custom_action_action:string, m.custom_action_name:string, m.custom_action_icon:Asset` |
| `m.hash_bytes_list` | DataMap 列表，每项 `m.hash_bytes:byte[]`，为签名证书的 SHA-256 |
| `m.queue, m.queue.current, m.queue.id` | 列表 / int64 当前队列 ID / string 媒体 ID |
| 队列项 | `m.queue.id:int64, m.queue.media.id / m.queue.title / m.queue.subtitle / m.queue.description:string` |
| `m.wear_media_bundle` | DataMap，可表示的 Bundle 标量和嵌套值；未知的 Parcelable 不猜测序列化 |

本实现选择 MediaSession 时，正在播放的优先，其余按 Android 的优先级；可以指定偏好的应用。失效的 token 不转向另一个播放器。读取快照失败时保留之前的镜像，不当作没有媒体；明确的空快照、关闭或撤销权限时发布墓碑。内容和图像摘要不变时不重发；首次连接和重连时静默，自动启动默认关闭，只在内容真正变化时按偏好处理。elapsed 单调递增；positionUpdateTime 从开机时间换算为 epoch，界面自己外推播放进度，不靠轮询。

Asset 遍历规则与通知相同，同一张图出现两次也不合并槽位。图片在替换或删除成功后才退役，最终存储还会检查是否有活动的 DataItem 或其他 AppKey 的权限引用；旧会话断开不能删除新的镜像。队列最多 100 项、图片预算和取色是本实现的策略。

### 6.2 手表命令

一个 DataMap 可以包含多个独立的操作，依次处理，单项失败不影响同一帧中的其他操作：

| 键 | 行为 |
| --- | --- |
| `command:string` | `previous, togglepause, next, play, stop, pause` 发送媒体键；`seek_forward, seek_back` 调用快进/快退 |
| `current_media_item_position:int64`（存在时） | seekTo；0 有效 |
| `rating:int32 = -1/0/1` | 踩 / 未评 / 赞；-2 不操作 |
| `volume:int32 = -1/+1` | adjustVolume |
| `absolute_volume:float`（存在时） | setVolumeTo(value × 最大音量)；0 有效 |
| `custom_action:string, custom_action_extras:DataMap` | 系统 sendCustomAction |
| `play_id:int64`（存在时） | skipToQueueItem；0 有效 |

没有调用 ID 或业务回执，不对连续相同的按键去重。未知的 command 只记录，其他操作照常处理。调用被接受只表示已提交，效果以新的快照为准；日志不含歌曲、token 或参数。

### 6.3 可选的 v2

P→W 普通 Message `/media_controls_bridging_v2/rpc/media_notification_message`，只有手表公告了 `media_notification_message_watch` 能力时才使用。**内容是 protobuf，不是 DataMap**。

| 字段 | 类型与含义 |
| --- | --- |
| 1 / 2 | string autoLaunchExtra / bool autoLaunchEnabled |
| 3 / 4 / 5 | float volume / bool reserveNext / bool reservePrevious |
| 6 / 7 / 8 / 9 | string appLabel / string sessionToken / repeated certificate{#1 bytes hash} / string package |
| 10 / 11 / 12 / 13 | int32 flags / bool playing / repeated customAction{#1 bytes PNG, #2 string action, #3 string name} / bool notificationSource |
| 14 / 15 / 16 / 17 / 18 | string title / string artist / int64 position / int64 updateEpoch / int64 duration |
| 19 / 20 / 21 | string mediaUri / float speed / bool supportsAbsoluteVolume |
| 22 / 23 / 24 / 25 | int64 legacyArtworkElapsed / int64 thisElapsed / bytes wearBundle（DataMap）/ int32 volumeIncrements |
| 26–30 | 手机 SDK 的扩展：int64 / int32 / bytes / string / bool，含义未确认，原样保留 |

手表要求 v2 的 elapsed 严格大于旧状态，并且已经存在同一包名、签名和 token 的传统镜像；字段 22 决定能否复用传统镜像的封面。本实现先发布传统 DataItem/Asset，等 Data Layer 进度屏障完成后，只为仍是最新的状态发送 elapsed+1 的 v2；失败、超限或断线时保持传统方式。官方手机端限制 wearBundle 小于 1000 字节、整条 v2 小于 50000 字节。adaptive 路径 `/adaptive_media_bridging/rpc/media_update`（目标能力 `force_adaptive_media_bridging`）只有手机端发送方，本实现未实现。

## 7. 找手机

使用 GMS 的 AppKey、普通 Message，**路径没有开头的斜线**，内容为空：

| 路径 | 方向与含义 |
| --- | --- |
| `com.google.android.gms.mdm.ACTION_RING` | W→P 开始响铃 |
| `com.google.android.gms.mdm.RING_STARTED` | P→W 已开始准备或响铃；不代表已经听到声音 |
| `com.google.android.gms.mdm.ACTION_CANCEL_RING` | W→P 停止 |
| `com.google.android.gms.mdm.RING_CANCELED` | P→W 已停止 |

手机以 GMS Data 路由公告静态能力 `mdm/ringable`（`0x73`），URI 最后一段为 `mdm%252Fringable`，必须编码两次。内容异常时忽略，只记录长度。

本实现的开关默认开启，关闭时停止播放并撤回能力。使用 MediaPlayer、音频焦点和 Vibrator；准备最多 8 秒，一次响铃最多 5 分钟；出错、失去焦点、用户停止或断线都会停止。恢复临时调高的闹钟音量前，先确认用户没有手动改过；不覆盖勿扰。状态分为准备中、开始播放、请求振动和失败；准备阶段即可发送 RING_STARTED。

手机找手表不是官方协议，见[手机找手表](watch-finding.md)。

## 8. 锁屏设置

伴侣路由，以下都是 **RequestApi**，请求和回复都是 protobuf。

| 路径 | 方向与请求 | 回复与后续 |
| --- | --- | --- |
| `/lock_screen_configuration/rpc/get_lock_screen_config_request` | P→W 空 | `#1 repeated lockType`（读取时兼容 packed/unpacked）；不支持或无权限时可能为空列表 |
| `/lock_screen/rpc/change_request` | P→W `#1 lockType:int` | `#1 result:int`：0 unknown、1 不支持、2 已显示配置页、3 已显示验证页 |
| `/lock_screen/rpc/cancel_ongoing_lock_configuration` | P→W 空 | 空回复 |
| `/lock_screen/rpc/notify_configuration_started_request` | W→P `#1 status`：1 已显示配置页 | 手机解析并确认对端后回复空 |
| `/lock_screen/rpc/notify_lock_screen_unlock_finished_request` | W→P `#1 status`：1 凭据错误、2 取消、3 已解锁 | 同上 |
| `/lock_screen/rpc/notify_configuration_finished_request` | W→P `#1 result`：1 配置完成、2 取消、3 已移除锁 | 同上 |

lockType：`0 unknown, 1 NONE, 2 PIN, 3 GESTURE`，未知的数字保留。W→P DataItem `/lock_screen/data/supported_lock_screen_types`，内容为 protobuf `#1 repeated packed enum`。

change_request 只指定类型，**没有 PIN 或密码字段**，密码在手表的系统界面输入。手表需要本地允许远程修改设置（`ALLOW_REMOTE_SETTINGS_CHANGE`）；未锁定时显示配置页，已锁定时先验证。最初的 result 2/3 不代表配置完成。通知请求的空回复与业务状态分开；对端或会话不符时不执行。用户在手表上直接配置时，手表可能不发送 configuration_finished，所以不能因为没收到就认为失败。取消不会清除真实的锁屏设置。

## 9. 表盘

API 见 [WatchFaceManagement.kt](../../android/core/api/src/main/kotlin/dev/ticbox/companion/api/WatchFaceManagement.kt)，编码见 [WatchFaceProtocol.kt](../../android/core/protocol/src/main/kotlin/dev/ticbox/companion/protocol/WatchFaceProtocol.kt) 和 [extra proto](../../schemas/google-companion-extra-reference.proto)。Google 表盘目录、收藏和编辑使用伴侣路由；Mobvoi 照片表盘同样走伴侣路由；MWF 使用独立身份。

### 9.1 目录与收藏

| 路径 | W→P Data 内容 |
| --- | --- |
| `/watch_face/<package>/<class>` | DataMap 键 `KEY_WATCH_FACE_PACKAGE, KEY_WATCH_FACE_ACTIVITY, KEY_WATCH_FACE_DISPLAYED_NAME, KEY_WATCH_FACE_SDK_VERSION, KEY_WATCH_FACE_MULTIPLE_FAVORITES_OPT_IN`；`KEY_WATCH_FACE_PREVIEW:Asset`。预览生成失败时手表可能不发布该项 |
| `/active/data/active_watch_face` | proto `#1 StringValue component_name, #2 Int32Value favorite_id` |
| `/favorites/data/favorite/<id>/` | 保留**末尾的斜线**；proto `#1 Int32Value id, #2 StringValue component_name, #3 WatchFaceStyle, #4 repeated ComplicationInfo, #5 StringValue preview_asset_key, #6 StringValue instance_id` |
| `/favorites/data/favorites_order` | proto `#1 repeated int32 ids`，读取兼容 packed/unpacked |

包装类型都是嵌套 message 的 `#1 value`，不能把外层 #1 直接写成 int 或 string。WatchFaceStyle 的 `options_selected` 是 **field 2 的 map<string,bytes>**，条目 `#1 string key, #2 bytes value`；ComplicationInfo 为 `#1 Int32Value slot_id, #2 StringValue provider_component_name, #3 Int32Value data_type`，保留原始数据和未知字段。preview_asset_key 只是外层 Asset 的键，与编辑结束时的 bytes preview 不同。

收藏操作是 P→W 普通 Message，等待 W→P 普通 Message `/favorites/rpc/favorites_operation_response`，不是 RequestApi：

| 路径 | 请求 proto |
| --- | --- |
| `/favorites/rpc/add_favorite` | `#1 Int32Value request_id, #2 StringValue component_name, #3 StringValue flavor_id（可选）` |
| `/favorites/rpc/remove_favorite` | `#1 Int32Value request_id, #2 Int32Value favorite_id, #3 Int32Value fallback_id（可选）` |
| `/favorites/rpc/change_favorites_order` | `#1 Int32Value request_id, #2 repeated int32 favorite_ids` |
| `/favorites/rpc/change_active_watch_face` | `#1 Int32Value request_id, #2 Int32Value favorite_id` |

回复 `#1 Int32Value request_id, #2 status:int`；status：0 成功、1 未知错误、2 参数无效、3 未启用多收藏。proto3 中 status 缺省即为 0；其他值保留。只有 request_id 匹配时才完成操作，目录的后续变化另行观察。切换或删除收藏不会卸载应用；卸载使用 Remote Intent 的系统确认，不能先删除本机目录就当作完成。

### 9.2 编辑 Channel

手机打开 `/wf_editing_session/channel`，内容为连续的 `int32 BE 长度 + protobuf EditingSessionMessage`，不按 Channel DATA 的边界分帧。根 oneof：`#1 Request, #2 Response, #3 Metadata`；Request oneof：`#1 start, #2 end, #3 abort, #4 watchFaceScreenshot, #5 complicationScreenshot`；Response oneof：`#1 start, #2 ended, #3 screenshot`；Metadata：`#1 styleSchema, #2 complicationStates`，以不透明的 proto 保留。

| 请求 | 字段 |
| --- | --- |
| start | `#1 Int32Value favorite_id, #2 BoolValue abort_current, #3 Int32Value width, #4 Int32Value height`；手表要求 width/height 包装存在，客户端不另加正数校验 |
| end | `#1 StringValue token, #2 FavoriteInfo`；FavoriteInfo `#1 Int32Value id, #2 StringValue component, #3 style, #4 repeated complications, #5 bytes preview` |
| abort | `#1 StringValue token` |
| 表盘截图 | `#1 Int32Value request_id, #2 StringValue token, #3 RenderParameters, #4 Int32Value quality, #6 style, #7 repeated complications, #8 int compression`；**字段 5 未使用** |
| 复杂功能截图 | `#1 Int32Value request_id, #2 StringValue token, #3 complication, #4 RenderParameters, #5 Int32Value quality, #6 Int64Value calendar_time_ms, #7 style, #8 int compression` |

RenderParameters：`#1 draw_mode:int, #2 repeated LayerParameter{#1 layer:int, #2 layer_mode:int}, #3 selected_complication_id:int, #4 outline_tint:int, #5 repeated packed watch_face_layers:int, #6 不透明的 highlight_layer`。完整定义见引用的 proto。

startResponse：`#1 StringValue token, #2 status`，status：0 未指定、1 成功、2 未知失败、3 已有其他会话、4 参数无效、5 绑定失败、6 无头模式不可用。建立后先发 token，再发 metadata。ended 同样两个字段，status：0 未指定、1 已提交、2 未知错误、3 被抢占、5 手动中止、6 表盘崩溃；4 未见。screenshotResponse：`#1 Int32Value request_id, #2 status, #3 bytes image`，status：0 未指定、1 成功、2 未知、3 未激活、4 预览不可用、5 参数无效。

quality 包装缺省为 100；compression：0 未设置按 1（WEBP 无损）处理，2 为 WEBP 有损，结果是压缩后的图片字节。会话 token 失效时不得操作新会话；断线或超时时中止；end 会更新收藏，并可能切换当前表盘。本实现保留未知的样式和复杂功能，不声称重建了所有渲染或完整的可视化编辑界面。

### 9.3 Mobvoi 照片表盘

普通 Message，前缀 `/tic_watchface_market/`，内容为 JSON WatchFaceBean：`category:int（缺省 2）, class_name:string, pkg:string, name:string, component_name:object?, time_place:int?, show_battery:int?, show_date_and_week:int?`，不要求每次都有全部字段。component_name 是 Android ComponentName 的 JSON 投影，不能当作任意的类名字符串。

| 后缀 | 方向、内容与回执 |
| --- | --- |
| `set` | P→W WatchFaceBean JSON，切换表盘或引导到商店；没有同路径的结果。真机上照片表盘不在收藏里时，发送后手表没有反应，所以本实现不用它切换 |
| `get_image` | P→W 查询，内容忽略；W→P 同路径 `{class_name, image}`，image 是宽 300、质量 80 的 JPEG，以**有符号字节整数数组**表示，不是 base64 或 Asset |
| `get_image_watchface_info` | P→W 查询；W→P 同路径 WatchFaceBean，category 为 1 并附照片布局设置 |
| `set_back_image` | P→W `BitmapFactory` 可读的原始图片字节 |
| `set_image_watchface` | P→W `int32 BE JSON 长度 + UTF-8 WatchFaceBean JSON + 剩余的图片字节` |
| `set_back_image_confirm` | W→P 空；多个保存分支共用，没有 ID，只能在串行操作时大致对应 |
| `download_watchface` | W→P 空，引导到商店或安装；不是表盘文件下载通道 |
| `list_featured` | 手表接收后直接返回，无动作也无回复；不实现 |

照片保存的确认与表盘安装或目录状态无关。

**布局字段。** 照片表盘自己的设置只有这三项，手表在 `get_image_watchface_info` 里返回当前值：

| 字段 | 含义 |
| --- | --- |
| `time_place` | 大号时间的位置：`0` 顶部、`1` 底部、`2` 中部。手表读不到时按 `0`。日期和星期在时间上方，跟着时间一起移动 |
| `show_battery` | `1` 显示电池图标和电量百分比，位于底部附近，不随时间移动；其他值不显示 |
| `show_date_and_week` | `1` 显示日期和星期几（不是周数）；其他值不显示 |

原厂手机端预览的位置如下，`W` 为圆形屏幕的宽度，x 是文字的左端，y 是文字基线；文字是白色的默认 sans-serif 常规体，没有阴影或描边。

| 元素 | x | y：`time_place` 0 / 2 / 1 | 字号 |
| --- | --- | --- | --- |
| 时间 | `0.18311404W` | `0.42543858W` / `0.62938595W` / `0.82894737W` | `0.25877193W` |
| 日期 | `0.3002193W` | `0.17763157W` / `0.37719297W` / `0.5767544W` | `0.07236842W` |
| 星期 | `0.51578945W` | `0.17324561W` / `0.37280703W` / `0.57236844W` | `0.06140351W` |
| 电量百分比 | `0.49122807W` | `0.91885966W` | `0.06140351W` |
| 电池图标 | `0.42105263W` | `0.89912283W` | 37 × 22，按 `W / 456` 缩放 |

电池图标的坐标按图标中心理解：按左上角放，它会和百分比文字重叠。这一点是推断，没有在真机上核对。TicBox 用 Canvas 画一个同样大小的三格电池轮廓，不使用原厂的图片资源。

**本实现的照片。** 照片来自系统照片选择器。重新编辑时，先用这次打开页面后编辑过的照片；其次用手表上次接受的那张照片的原文件，以及当时的取景和算法；都没有时才用手表上现有的照片，即 `get_image` 返回的宽 300 的预览，比屏幕小。原文件在照片选择后复制到应用的 no-backup 目录，手表确认后才保留，最大 64 MiB。读入时按 EXIF 方向转正、转成 sRGB，长边超过 4096 像素的按 4096 读入。用户在圆形取景框里拖动和缩放，选出一个正方形区域，区域可以超出照片，超出的部分为黑色。

所选区域按支持的手表屏幕（Pro 5、Pro 5 Enduro、Atlas 都是 466 × 466）缩放：

- 比屏幕大时缩小，默认 Lanczos-3。
- 比屏幕小时放大，界面上显示警告，默认双三次。
- 两种情况都可以改选双线性、双三次或 Lanczos-3。缩小时滤波核按缩小倍数展宽，不会跳过像素。
- 缩放之后可以选择 USM 锐化（高斯半径 0.8，强度 0.4、0.8 或 1.3）。
- 结果编码为不透明的 PNG（RGB），和布局字段一起用 `set_image_watchface` 发送；读不到手表的设置时只用 `set_back_image` 发送图片。最坏情况约 640 KiB，在 1 MiB 的消息上限以内。

手表端的处理如下，都未在真机上核对：

- 图片用 `BitmapFactory` 解码，凡是它能解码的格式都可以，没有格式或尺寸校验。
- 解码后重新保存为质量 90 的 JPEG，透明通道丢失，尺寸不变。所以本实现发送无损的 PNG，只经过手表这一次有损压缩；发 JPEG 会被压缩两次。
- 绘制时把保存的 JPEG 载入为 `BitmapDrawable`，用它的尺寸和 400 的设计坐标算出“铺满并居中”的比例，再按 Canvas 的实际宽度设置 bounds，直接画到屏幕上，不经过 400 × 400 的中间位图。非正方形的图片会裁掉超出的两边。

所以 466 × 466 的正方形图片在 466 × 466 的屏幕上是一个像素对一个像素画出来的，手表不再缩放。本实现选择的缩放算法和锐化效果会原样保留，只剩手表那一次质量 90 的 JPEG 压缩。

**切换到照片表盘。** 照片表盘（手表上叫 Image）默认不在收藏里，只保存照片不会让手表显示它。本实现在 `set_image_watchface` 确认之后、以及用户点“使用这个表盘”时，用 9.1 的收藏 RPC 切换：

1. 按设置里的 `pkg` 和 `class_name` 在表盘目录和收藏中找到它的组件名；`class_name` 可以省略包名或以 `.` 开头；都对不上时，在同一个包里按 `name` 找。
2. 不在收藏里就先 `add_favorite`，等它出现在收藏里（最多 10 秒），再 `change_active_watch_face`。已经是当前表盘时不再发送。
3. 找不到或手表拒绝添加时，提示用户在“表盘 → 更多表盘”里手动添加。

发送时，编辑页面在按钮下方依次显示“处理照片、发送到手表、切换表盘”三步的进度，全部完成后才关闭；离开编辑页面时，照片表盘页面上会继续显示进度；离开照片表盘页面也不会中断发送和切换，结果显示在屏幕底部的消息里。

找到的组件名记在本地，供其他页面识别。手表给照片表盘的预览是它的默认图片，所以本实现改用手表上的照片加上时间、日期和电量来画：照片表盘是当前表盘时，用于主页和“当前表盘”快捷方式；表盘页面的收藏卡片则一直这样画。

### 9.4 TicYcx / MWF 独立协议

独立的包 `com.mobvoi.mwf.magicfaces`，对端能力为 `mwf_phone_connect`，不复用伴侣路由。以下都是普通 Message；证书相同不代表它的 AppKey 可以与伴侣互换。

| 路径 | 方向、内容与效果 |
| --- | --- |
| `/ycxbp/send_companion_info` | P→W JSON；token 非空时触发手表内的登录成功；无回复 |
| `/ycxbp/sync_wear_info` | P→W 查询；W→P（连接时也会发送）JSON `wearType, wearDeviceId, wearCapability, region, hasNfcFeature, sn, appVersionCode, maxProtocolVersion=2` |
| `/ycxbp/sync_wear_setting` | 双向 JSON `time_format_24:bool, unit_metric:bool, step_goal:int`；手表收到后保存时间和单位设置，不把 step_goal 写回目标 |
| `/ycxbp/check_companion_app` | P→W 不透明 bytes；按手表当前的 MagicWatchFace 状态检查 |
| `/ycxbp/check_companion_app_success` / `_fail` | W→P 原样回显请求 bytes，不是 bool JSON |
| `/ycxbp/subscription_success` | P→W 内容忽略，触发手表内的订阅成功广播；不是付费接口 |

它的 CompanionInfo 字段为 `birthday, capabilities, dateFormat, deviceId, email, headUrl, height, isVpa, model, nickName, phoneNumber, sex, systemVersion, temperature, timeFormat, token, unit, userId, versionCode, versionName, weight`，与主伴侣的 `sessionId / wwid / dateformat` 不同，不能互相代替。

表盘 ZIP 由手机打开 Channel 发送。手表按 URI 拆分路径，最后一段作为表盘目录名；去掉空段后多于 4 段时，原始拆分的倒数第二段作为可选的 userId。没有固定的前缀模板。Channel 内容是原始 ZIP，手表以覆盖方式保存为表盘目录下的 `<faceDir>.zip`；接收开始时报告 status 0，输入关闭后报告 status 1 并关闭，不检查关闭原因或应用错误。**status 1 不代表完整性或安装成功**。本实现可以在显式指定的路由和路径上流式发送 ZIP，但目录检查、最终完成确认、完整的压缩包格式和手机端身份未确认，不能向界面报告“安装完成”。

## 10. 截图导出：确认会删除截图

伴侣路由上的普通 Message，不是 Asset 或 Channel：

| 路径 | 方向、内容与行为 |
| --- | --- |
| `/screen_shot/load_photo` | P→W 空；请求截图数量 |
| `/screen_shot/photo_nums` | W→P 十进制截图数；大于 0 时手机发送 start_sync |
| `/screen_shot/start_sync` | P→W 空；手表发送排序后的第一张 |
| `/screen_shot/photo_data` | W→P 整张原始 PNG 字节，没有文件名、ID 或大小 |
| `/screen_shot/photo_received` | P→W 空；**手表删除排序后的第一张，再发送下一张** |

手表只选 PNG，按文件名排序，文件名为 `Screenshot_yyyyMMdd-HHmmss.png`。官方手机端解码后另存为 JPEG，约 1 秒后确认；全部发送完毕时没有独立的结束消息。本实现只有离线的格式识别。实现时必须在本地保存成功之后才确认；确认没有 ID，重复确认可能删掉下一张，不得超时自动重发。这不是无副作用的只读操作，也不能推断健康数据同样逐包确认删除。

## 11. SOS 设置与紧急动作

伴侣路由上的普通 Message，前缀 `/companion/ticsos`：

| 后缀 | 方向、内容与回执 |
| --- | --- |
| `/watch_settings` | P→W 查询，内容忽略；W→P 同路径 JSON `u_time:long, button_press:bool, e_num:string, tic_fall_type:int, share_loc:bool, cts:string`，退出设置页时也会主动发送 |
| `/edit_number` | P→W UTF-8 号码，手表界面也能反向发送；更新本地号码和时间，回复 sync_ok |
| `/edit_contacts` | P→W UTF-8 联系人字符串，原样保存；cts 的内部格式见[伴侣功能](../dev/features.md)；回复 sync_ok |
| `/button_trigger` | P→W 文本，按 `Boolean.parseBoolean` 解释；写入 `emergency_gesture_on`，回复 sync_ok |
| `/share_location` | P→W 同样的布尔文本；保存设置，回复 sync_ok |
| `/fall_detection` | P→W 十进制 int；保存设置，回复 sync_ok |
| `/sync_ok` | W→P 空，没有请求 ID 或结果码，多项设置共用，并发时无法准确对应 |
| `/start_sos` | W→P 号码文本；紧急求助路径，手表内相邻逻辑可能执行 `CALL_PRIVILEGED` |

不得把 start_sos 用作连通性探测，也不能把 sync_ok 当作紧急电话已经接通。本实现的 SOS 设置和手机端紧急联系人援助见[伴侣功能](../dev/features.md)。

## 12. 其他已知消息

### 12.1 RemoteEvents

P→W Data `/remote_events/supported_events`，protobuf `#1 repeated Info{#1 event_type:int, #2 path:string, #3 Security{#1 repeated Permission{#1 name:string}, #2 repeated Certificate{#1 hash_bytes:bytes}}}`。W→P 普通 Message 发往公告的路径，请求 `#1 event_type:int, #2 event:bytes, #3 event_id:string（UUID）`；P→W `/remote_events/rpc/remote_event_response`：`#1 event_id:string, #2 is_success:bool, #3 failure_code:int, #4 failure_message:string`。以 event_id 关联，没有持久的幂等保证。手表内的 Binder 调用有权限和证书检查，缺少附加条件时仍要求 `TRIGGER_REMOTE_EVENT`，不能通过线上消息绕过。

完整请求路径为 `/remote_events/rpc/remote_event_request` 加以下后缀：

| event_type | 后缀与手机行为 |
| ---: | --- |
| 1 | `/deeplink/account`，向宿主发布账号深链事件 |
| 2 | `/notification/ota`，向宿主发布 OTA 提醒，需要 `POST_NOTIFICATIONS` |
| 3 | `/deeplink/photo_picker`，已公告但手机未实现，返回不支持 |
| 4 / 5 | 充满电 / 离开充电器，手机有处理方，后缀未确认 |
| 6 | `/action/send_sms`；event 内为 proto `#1 destination_address:string, #2 body:string`，需要 `SEND_SMS` 且值非空，用 SmsManager 分段发送 |
| 7 | `/deeplink/locale_settings`，打开手机的语言设置后回复结果 |

本实现未实现 RemoteEvents。

### 12.2 完整电量 proto

P→W 普通 Message `/batterystate/rpc/batterystate_request`，内容为空；手表更新 DataItem `/batterystate/data/batterystate`，并发送 Message `/batterystate/rpc/batterystate_response` `#1 successfully_refreshed:bool`（回复中不含电量）。本实现使用 Mobvoi 的十进制电量路径，未接入这一组。

DataItem 内容为 protobuf：`#1 level:int, #2 charging_status:int, #3 power_source:int, #4 low_level:int, #5 critical_level:int, #6 saver_mode:int, #7 timestamp_ms:int64, #8 charge_remaining_ms:int64, #9 battery_remaining_ms:int64`。charging：0 未知、1 充电中、2 放电、3 未充电、4 已充满；power：0 无、1 交流、2 USB、3 底座、4 无线；saver：0 无、1 轻度、2 传统手表、3 仅时间、4 自定义；剩余时长未知为 -1。旧的 `/battery/refresh`、`/battery/blame/battery/sippers` 不使用这一结构。

### 12.3 未实现的入口

Google Dialer 的独立消息：P→W Message `/dialer/incall/spam_and_caller_id_info`（`#1 bool, #2 nested{#1 string, #2 string}, #3 string`）、`/dialer/show_notification`（`#1 enum, #2 string, #3 string`）、`/dialer/featureplatform/incall`（复杂嵌套，未确认）；Request `/dialer/ping`（空 → `#1 bool`）、`/dialer/tethered_auto_call_screen`（`#2 string, #3 string` → `#1 bool`）；W→P Request `/dialer/get_default_voicemail_number`（空 → `#1 string`）、`/dialer/is_synced_notification_available`（空 → `#1 bool`）。它们的手机端对应方、AppKey 和完整语义未确认，本实现未实现，也不改投到伴侣的电话接口。

`/dialershared/dataclient/experiment_sync/` 读取 DataMap `phone_experiment_ids:long[]`，不是健康数据。其他已知但未实现或未完整确认的有：Wi-Fi 的 `ap_list/result`、`wifi_report_setting_result`，tiles，日历和联系人，bugreport，OTA 安装，语音、eSIM、支付、商店。某个前缀或功能白名单出现，不足以证明有业务处理方。深链 `/deeplink/rpc/open_deeplink` 的 protobuf #1 kind（2 可启动 Assistant 激活）及手机扩展的 #2/#3 只确认了一部分，本实现不猜测完整的 Assistant 流程。

字段含义不明时以带类型的未知值保留并诊断。没有业务回执的命令不得报告成功；可能触发系统动作或云副作用的请求不得混入本地只读刷新。
