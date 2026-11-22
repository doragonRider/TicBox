# 工具箱协议

本文规定本应用手机端与手表（或任意 Android 设备）上的工具箱 Agent 之间的私有协议：两种连接方式、帧格式、握手、操作、取消语义，以及勿扰同步消息。投屏的媒体部分见[远程屏幕](remote-screen.md)。常量定义在 [ToolboxProtocol.kt](../../android/core/protocol/src/main/kotlin/dev/ticbox/companion/protocol/ToolboxProtocol.kt)，Agent 实现在 [toolboxagent](../../android/watch/toolboxagent/src/main/kotlin/dev/ticbox/companion/toolboxagent/)。

## 1. 两种连接

两种连接运行同一个 Agent（`dev.ticbox.companion.toolboxagent.ToolboxAgentMain`，由 `app_process` 启动），协议相同，实际权限以 READY 报告的 UID 为准。

| 项目 | 无线 ADB | root Local Bridge |
|---|---|---|
| 传输 | Kadb 直连目标的 adbd（无线调试配对码、TLS、mDNS 发现），每个操作一条 shell-v2 流 | 伴侣主连接发送唤醒消息，再建立专用的已配对蓝牙 RFCOMM |
| 权限 | 通常为 shell（UID 2000） | `su -c app_process`，UID 必须为 0 |
| 前提 | 目标开启无线调试；手机 Android 17 起需要本地网络权限 | 手机与手表安装同一签名的本应用，已有伴侣连接和蓝牙绑定，手表授予蓝牙和 Magisk root |
| Agent 载荷 | 手机内嵌的手表 APK 部署到 `/data/local/tmp`，作为 classpath；目标上不需要安装 Local Bridge | 已安装的 Local Bridge APK 本身 |
| 身份 | ADB 私钥保存在手机 no_backup，更新和重连沿用 | 手机私有的 256 位安装密钥，经签名的主路由交给手表 |

### 1.1 root 唤醒与握手

1. 手机在伴侣主连接上用本应用的 AppKey 发送 Message `/ticbox_toolbox/wake`，内容为 `{"token":"<64 个小写十六进制字符>"}`。
2. 手表 Local Bridge 校验格式，同一时间只服务一个窗口；以服务名 `TicBox Toolbox`、UUID `d7808c72-8f9b-4e2c-bf43-9661d55ca405` 监听 RFCOMM。没有已认证的客户端且空闲超过 25 秒时退出监听。
3. 只接受 `BOND_BONDED` 的对端。连接后 10 秒内必须收到一帧 `REQUEST`：`{"op":"hello","args":{"token":"<同一 token>"}}`，手表以常量时间比较；通过后回复 `RESULT {"authenticated":true}`。
4. 之后手表执行 `su -c "CLASSPATH=<APK> exec app_process / dev.ticbox.companion.toolboxagent.ToolboxAgentMain <scrcpy jar> --bridge-host"`，在 socket 与 Agent 的 stdin/stdout 之间**逐次读写并立即 flush** 地双向转发，不修改帧内容。（如果用带缓冲的拷贝，小的探测帧会停在缓冲里，三方互相等待。）

一个手表窗口最多同时服务 4 个连接，每个操作独占一个 Agent。

## 2. 帧

每帧为 **1 字节类型 + 4 字节大端有符号长度 N + N 字节内容**，N 在 0 到 8 MiB（8388608）之间。JSON 内容为 UTF-8 对象，未知字段忽略。大块数据按 64 KiB 分段。写入时整帧一次写出（最多使用 8 KiB 的临时缓冲），避免 shell-v2 把一帧拆成多个 ADB 包，每个包都要一次往返。

| 类型 | 名称 | 内容 |
|---:|---|---|
| 1 | REQUEST | `{"op": <名称>, "args": {...}}` |
| 2 | READY | Agent 启动后的第一帧，见第 3 节 |
| 3 | RESULT | 操作结果 JSON |
| 4 | ERROR | `{"code": <稳定错误码>, "type": <异常类名>}` |
| 5 | DATA | 二进制数据段 |
| 6 | END | 数据结束或确认 |
| 7 / 8 | STDOUT / STDERR | 命令输出 |
| 9 | EXIT | 命令或终端退出 |
| 10 | STDIN | 命令或终端输入 |
| 11 | CANCEL | 请求 SIGINT |
| 12 | PROGRESS | 进度 |
| 13 / 14 | VIDEO_CONFIG / VIDEO_FRAME | 投屏视频 |
| 15 | CONTROL | 投屏控制（scrcpy 控制消息） |
| 16 / 17 | CLIPBOARD / CLIPBOARD_ACK | 投屏中的剪贴板 |
| 18 | FORCE_STOP | 请求 SIGKILL |
| 19 / 20 | AUDIO_CONFIG / AUDIO_FRAME | 投屏声音 |
| 21 | TERMINAL_RESIZE | 终端窗口尺寸 |
| 22 | TERMINAL_ACK | 终端输入确认 |
| 23 | SCREEN_UDP_READY | UDP 投屏就绪 |

类型超出 1..23 或长度越界时，连接以错误结束。

## 3. READY 与版本

```json
{"version":5,"uid":2000,"sdk":34,"deviceId":"<SHA-256 十六进制>",
 "protectedPackages":["dev.ticbox.companion"],
 "features":["INSTALL_APK","INSTALL_SPLITS","APPS","CLIPBOARD","COMMAND","SCREEN","FILES","FILE_RESUME","TERMINAL", "..."]}
```

- `version`：当前为 5。手机要求与自己的版本一致，否则拒绝工具操作（`agent_version_mismatch`）。v3 加入设备身份，v4 加入投屏的编码选择和声音，v5 加入 PTY 终端。
- `deviceId`：硬件序列号属性的 SHA-256；原始序列号不发送、不记录。ADB 和 root 读取同一个属性，所以端口、IP、蓝牙连接或 APK 更新都不改变它。取不到时拒绝执行（`device_identity_required`），不能用端点代替设备身份。
- `protectedPackages`：当前通道的宿主。root Bridge 以 `--bridge-host` 声明，手机和 Agent 都拒绝对它强停、清数据、清缓存、禁用、卸载、挂起或私有备份；独立的 ADB Agent 不声明。
- `features`：始终包含 `INSTALL_APK, INSTALL_SPLITS, APPS, CLIPBOARD, COMMAND, SCREEN, FILES, FILE_RESUME`，以及 `SCREEN_KEYFRAME, SCREEN_ENCODER_LIMITS, SCREEN_CODEC_OPTIONS`；原生 PTY 可用时加 `TERMINAL`；UID 2000 时加 `SCREEN_UDP, SCREEN_UDP_FEC, SCREEN_UDP_AUTH_ONLY, SCREEN_UDP_STARTUP`；UID 0 时加 `PRIVATE_APP_DATA, CLEAR_APP_CACHE, FILE_OWNERSHIP` 以及 Magisk 模块和健康监测的能力。手机只使用声明过的能力。

手机记住已接受的设备身份；身份变化时阻止工具操作，直到用户确认新设备。每次打开操作通道都重新核对 READY 的身份和连接代次，旧的操作上下文不能跟随重连到另一台设备。

### 3.1 冻结的更新通道

为了让已安装的 Bridge 始终能被手机更新，以下部分**不能改变**：帧格式；READY 的 `version / uid / deviceId`；`install` 请求以及 DATA / END / PROGRESS / RESULT / ERROR 的用法。必须改动时，同时提高 `FIRST_UPDATABLE_VERSION`（当前为 3）。

版本不一致时手机另开一条通道，只检查 READY 的 UID 为 0、设备身份有效且与已接受的设备一致，然后只发送 `install`。比下限更旧的 Bridge 返回 `bridge_update_needs_adb`（需要先用无线调试安装一次），比手机新的返回 `phone_app_outdated`。`install-commit` 时系统会停止 Bridge，承载通道的 RFCOMM 随之断开：全部数据已发出后断开返回 `install_result_unknown`，未发完就断开返回 `bridge_update_interrupted`（此时没有安装）。传输完成不等于已安装，手机重新连接并列出应用，版本一致且 APK 路径发生变化才算更新成功。

## 4. 操作

REQUEST 之后，各操作按自己的方式使用后续帧。

| op | 参数与行为 |
|---|---|
| `probe` | 返回 `{"success":true}`；连接时用于确认整条链路 |
| `files` / `stat` / `file` | 列目录（含隐藏文件、Unix mode、uid/gid、符号链接）、查询属性、复制 / 移动 / 删除 / chmod / chown |
| `pull` / `push` | 流式下载和上传；`pull` 可带 `offset`；默认覆盖使用同目录的临时文件再替换 |
| `file_snapshot` / `pull_resume` / `push_resume` / `discard_upload` | 可续传的传输，见第 5 节 |
| `exec` | 执行 `command`，返回分开的二进制 stdout / stderr、exitCode 和超时、截断标记；`timeoutMillis` 默认 60000 |
| `terminal` | PTY 终端，见第 6 节 |
| `install` | 一个 PackageInstaller 会话写入 base 和所有分包后一次提交；提交前失败、短读或取消时 abandon |
| `apps` / `app` | 列出应用（包名、名称、版本、UID、禁用 / 系统标志、base / split 路径、数据目录）；启动、强停、启用 / 禁用、卸载、清数据、挂起，以及权限和 AppOps |
| `backup` | root：强停后以 tar 流式备份应用的 CE / DE 私有数据 |
| `clipboard_get` / `clipboard_set` | 在独立进程中使用系统 shell 的剪贴板身份读写，不依赖前台输入框；空字符串是明确的结果 |
| `screen` / `screen_encoders` | 投屏和编码器能力查询（`maxSize` 64..4096），见[远程屏幕](remote-screen.md) |
| Magisk 模块、健康监测 | 安装和检查[进程内模块](monitoring-settings.md#52-进程内模块)、读写[监测设置](monitoring-settings.md) |

root 另外支持 `CLEAR_CACHE`（强停后只删除 CE / DE 的 cache 和 code_cache 内容）和修改文件的 uid / gid；shell 对这些操作返回 `permission_denied`。root 可以管理权限允许的任意绝对路径，没有“只能操作共享存储”的白名单。不自动恢复私有数据，也不重新挂载系统分区。

错误码：`invalid_request`、`unknown_operation`、`permission_denied`、`not_found`、`already_exists`、`connection_closed`、`operation_cancelled`、`operation_failed`、`install_result_unknown`、`device_identity_required`、`transport_host_protected`、`terminal_unavailable`、`terminal_start_failed`，以及各模块自己的原因码（文件传输、投屏、模块、监测）。安装被提前拒绝时，Agent 在写出 RESULT 或 ERROR 后，继续丢弃本请求声明范围内仍在途的 DATA / END（最多 5 秒），避免明确的拒绝被连接重置掩盖。

### 4.1 取消与断线

断线时 root 转发器关闭 Agent 的 stdin，并继续读取、丢弃剩余的输出，让操作完成收尾；不设统一的 TERM / KILL 倒计时，也不强杀 root Agent。

| 操作 | 连接丢失后 |
|---|---|
| 终端 | 关闭本会话的 PTY master，由内核挂断终端；不强杀，不重放输入 |
| 命令 | 关闭管道，向本会话仍存活的子进程组发送 SIGHUP（停止的进程另发 SIGCONT） |
| 文件 | 上传在 EOF 后清理临时文件；递归复制或删除在文件边界检查取消；已完成的部分不回滚 |
| 安装 | 提交前取消会 abandon；已开始提交则继续等待其结果，缺少回执时报告 `install_result_unknown`，不 abandon，也不重试 |
| 投屏 | 关闭本会话的 scrcpy socket，向仍存活的 scrcpy 进程组发送 SIGHUP |

命令会话中，`STDIN` 的 EOF 只表示输入结束；`CANCEL` 发送 SIGINT，通道保留；只有 `FORCE_STOP` 才发送 SIGKILL，并且只发给本会话创建的子进程：核对存活状态和 `/proc` 中的启动时间，只有进程组和会话 ID 都等于该 PID 时才向整组发送。命令超时先发 SIGINT，短暂等待后报告 timeout / 124，不升级为 SIGKILL。忽略 HUP 或自行脱离会话的任务可能继续运行。

无线 ADB 下，只有在业务 REQUEST 尚未发送、OPEN 或 READY 遇到 IOException 且确认底层连接已失效时，才允许重新打开 Agent 一次；发送 REQUEST 之后的任何失败都不重发，包括只读操作。

## 5. 可续传的文件传输

需要 `FILE_RESUME` 能力，否则返回 `file_resume_unavailable`。

**下载**：`file_snapshot` 返回路径、总大小、完整 SHA-256 和设备身份；手机写入自己的暂存文件。重试时 `pull_resume` 带上当前暂存的长度和前缀 SHA-256，Agent 先验证前缀再发送后续字节；结束前再次验证源文件的完整 SHA-256、大小和属性没有变化。完成回执包含完整摘要和本轮后缀的摘要。Agent 发出最后的完成帧后等待手机的 END 确认或连接关闭，避免 root 中继在尾帧仍在途时就关闭蓝牙。

**上传**：手机先暂存不可变的源文件，计算大小和 SHA-256，生成随机的 32 位小写十六进制 `transferId`。同一次上传的目标、路径、摘要、大小、覆盖选项和权限保持不变。Agent 在目标目录中使用 `.ticbox-transfer-<transferId>/`，其中有固定的意图清单、数据和排他锁；每次续传都重新哈希已保留的字节，由手机复核。传输期间目标文件保持原样，完整摘要通过后才在同一目录中提交；暂停期间目标发生变化时拒绝覆盖。提交前持久化 `committing` 状态，如果提交完成而回复丢失，重试时验证目标内容并返回确认，不会再次覆盖。`discard_upload` 只删除匹配意图的暂存，不删除目标。

只有收到完整的完成回执且计数和摘要一致，才算传输完成，不能根据进度到达 100% 提前显示成功。断线后只有用户明确重试，才能把记录绑定到同一 `deviceId`、同一后端种类的新连接。

## 6. PTY 终端

需要 `TERMINAL` 能力。Agent 用随 APK 分发的小型 JNI 库（ARM32、ARM64、x86、x86_64，放在 APK 的 `assets/toolbox/pty/` 而不是 `lib/`）创建真正的 PTY，启动带控制终端的 `/system/bin/sh -i`。外层的 ADB 或蓝牙仍传输二进制帧，只有内部的 shell 使用 PTY。

- 参数：字符行列 1..1000（默认 24 × 80），可选的像素宽高 0..65535（0 表示未知），`TERM` 不超过 64 个字符（默认 `xterm-256color`）。
- 实际创建 PTY 并 exec 之后才返回成功，不等待提示符。
- 输出合并 stdout、stderr 和回显，是原始字节，可能在 UTF-8 字符或控制序列的中间断开。
- 输入原样发送，不追加换行，每段最多 64 KiB，收到 `TERMINAL_ACK` 后才发下一段。确认只表示字节已写入 PTY。
- `TERMINAL_RESIZE` 更新窗口尺寸。
- 退出码为 shell 的退出码，信号结束为 `128 + signal`。shell 退出后，后台进程可能仍持有 PTY：Agent 在静默 750 ms 后结束读取，并且从 shell 退出起最多再读 2 秒；达到上限时以 `outputTruncated=true` 报告，退出码不变。

库提取到 root 的 `/data/local` 或 shell 的 `/data/local/tmp` 下一个 0700 的子目录；创建前用 `lstat` 检查整条路径，拒绝符号链接、非目录、不可信的所有者或写权限。库文件校验长度和 CRC、设为只读后加载，用完即删除。

## 7. 勿扰同步

手机的勿扰状态通过本应用 AppKey 的普通 Message 单向同步到手表：

```text
P→W /ticbox_dnd/state   {"requestId":"<1..128 个字符>","revision":<正整数>,"active":<bool>}
W→P /ticbox_dnd/result  {"requestId":"...","revision":...,"active":<实际状态>}  或  {"requestId":"...","revision":...,"error":"<原因>"}
```

手表 Local Bridge 为本应用建立独立的 `AutomaticZenRule`（PRIORITY），只开关这条规则；用户手动开启的勿扰、其他应用和日程规则不受影响。不复制手机的联系人例外或通知策略。revision 在等待 Magisk 授权之前就保留，较旧的命令不能覆盖较新的命令。手表没有通知策略权限时，通过 root 执行 `cmd notification allow_dnd dev.ticbox.companion` 请求授权，拒绝时返回权限错误。

手机从已授权的 `NotificationListenerService.currentInterruptionFilter` 和变化回调读取状态，启动和重连时发送最新状态；监听器不可用时状态为未知（`notification_listener_unavailable`），不当作“关闭勿扰”。偏好、版本号和可能已激活规则的手表列表保存在手机的 `no_backup/companion/dnd-v1.json`；发送前先持久化归属，所以断线、丢失回执或切换手表后，回到原手表时仍能释放自己的规则。回执必须匹配 requestId、revision、对端和物理会话。

## 8. 待验证

以下没有在真实手表上验证：通过 root 更新 Bridge 的完整过程、Magisk 授权弹窗、无线 ADB 的 TLS 配对、厂商的剪贴板和 NotificationManager 行为、PTY 在 SELinux 下的行为、各种文件系统上的断点续传。
