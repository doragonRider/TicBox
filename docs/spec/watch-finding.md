# 手机找手表

官方协议里没有可用的“手机找手表”消息。手机 SDK 中的 `/companion/find_watch`、`/companion/find_watch_end` 只是未被使用的常量，手表上没有接收方；手表 GMS 的 MDM 只处理能力事件，不接收反向响铃。因此本应用用自己的手机 App 和手表 Local Bridge 实现这一功能：复用 WCS 的 Remote Intent 打开手表上的响铃页，再用两端共享签名的 Wear Message 停止并回报执行情况。

它不依赖 Mobvoi 或 Google 云、Google 账号、健康读取开关或 root。手机和手表必须安装同一版本、同一签名的应用；不需要事先在手表上打开 Local Bridge，第一次使用时手表会自动显示可停止的响铃页。

## 1. 启动：Remote Intent

手机在当前伴侣路由上发送普通 Message `/remote_intent/rpc`，内容为 DataMap：

| 字段 | 类型 | 值 |
|---|---|---|
| `action` | string | `dev.ticbox.companion.action.FIND_WATCH` |
| `package_name` | string | 手机和手表应用的包名 `dev.ticbox.companion` |
| `start_mode` | int | `0`（Activity） |
| `com.google.android.clockwork.actions.RpcWithCallback.invocation_id` | string | 本次随机生成的请求 ID |
| `extras` | DataMap | 以下三个 Intent extra |
| `extras.request_id` | string | 与 invocation_id 相同 |
| `extras.source_node` | string | 当前会话中手机的本地节点 ID，手表据此回复 |
| `extras.duration_ms` | long | 最长响铃时长，默认 300000 毫秒 |

手表的 Activity 为 singleTop，显示在锁屏之上并点亮屏幕，但不解除锁屏。界面到前台后才请求音频焦点、异步准备闹钟提示音并尝试振动。用户点击停止、离开页面或到达时限都会结束响铃；失去音频焦点时释放播放器，振动可以继续。时限由手表独立计时，手机断线也不会一直响下去。

WCS 返回 `/remote_intent/result` 的 `successful=true` 只表示它处理了启动请求，不能当作 `RINGING`：找不到 Activity 时 WCS 只记录日志，也可能返回 true。返回 false 时报告 `UNAVAILABLE`；一直没有执行反馈则报告 `TIMED_OUT`。两种情况都提示用户检查手表上的应用和系统启动条件。Remote Intent 的编码见[伴侣业务消息](companion.md#33-remote-intent)。

## 2. 停止与执行反馈

下面两个路径使用**本应用自己的 AppKey**，即实际包名加安装证书的 SHA-1，与伴侣路由和 GMS 路由都不同。签名摘要在运行时从安装信息计算，不在代码中硬编码。

| 路径 | 方向 | 内容 |
|---|---|---|
| `/ticbox_finding/stop` | 手机 → 手表 WearableListenerService | UTF-8 JSON `{"version":1,"requestId":"<id>"}` |
| `/ticbox_finding/state` | 手表 → 手机 | 下面的 UTF-8 JSON |

```json
{
  "version": 1,
  "requestId": "example-request-id",
  "state": "ringing",
  "audioPlaybackStarted": true,
  "vibrationRequested": true
}
```

`state` 为 `ringing`、`stopped` 或 `failed`，可以附带原因代码 `reason`；手机也接受将来可能出现的 `starting`。只有 `MediaPlayer.start()` 成功或振动调用成功时，手表才报告相应的标志。振动先开始、音频稍后才开始时，手表可以连续发送两次反馈，发送队列保持顺序。播放器开始不代表已经测到可听的声音，振动标志也不测量实际振幅。

手表以 `(sourceNode, requestId)` 标识一次操作，无关的 STOP 不会停止当前操作。最近收到的 STOP 在进程内有界缓存，避免手机已取消、但还在启动队列里的旧 Activity 再次响铃。手表会临时调高闹钟音量，结束时只在音量仍是自己设定的值时才恢复，保留用户中途的调整。

手机只接受当前对端、当前物理会话、本应用 AppKey 和当前 requestId 的反馈。未知字段和更高版本只取能理解的标志；内容异常时只记录处理元数据并继续等待，不因一条坏消息重置主连接。停止过程中收到迟到的 `ringing` 时，重新发送同一个 STOP，不把状态改回响铃。

## 3. API 与界面

`services.finding.ringWatch()` 提交启动后立即返回，之后的进度通过 StateFlow 更新；`stopWatch()` 同理。15 秒是手机等待反馈的预算，可以在测试中注入，不是协议字段。

| FindWatchStatus | 含义 |
|---|---|
| `IDLE` | 当前连接尚未发出请求 |
| `STARTING` | 等待本次请求的执行反馈 |
| `RINGING` | 手表报告音频播放或振动已开始 |
| `STOPPING` | 已请求停止，等待确认；保留最后已知的效果标志 |
| `STOPPED` | 手表报告已停止，或用户在手表上结束 |
| `UNAVAILABLE` | 本应用的路由不可用，或 WCS 明确拒绝启动 Activity |
| `TIMED_OUT` | 没有收到启动或停止反馈；启动超时还会发送 STOP，取消可能迟到的启动 |
| `DISCONNECTED`、`FAILED` | 连接已结束，或本地/手表明确报告失败 |

`watchAudioPlaybackStarted`、`watchVibrationRequested` 分别反映手表的回报；`watchRingingConfirmed` 表示两者至少有一个成功。`watchStopAvailable` 单独表示是否仍有可以停止的请求，不能因为播放标志为 false 就推断手表没有启动。反馈超时后仍保留停止的能力，之后可以接收停止反馈或重试。界面在 STARTING 和超时后提供取消，在 STOPPING 时等待；手表应用版本过旧时提示检查。

“手表找手机”沿用官方的 GMS MDM 流程，见[伴侣业务消息](companion.md#7-找手机)。手表应用不申请 INTERNET 权限。

## 4. 待验证

手机找手表和手表找手机都已在设备上可用。音频和振动标志在各种组合下的回报、两端停止、离开响铃页和断线后的自动时限，仍需进一步验证。
