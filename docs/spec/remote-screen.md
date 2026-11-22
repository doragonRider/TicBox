# 远程屏幕

本文规定工具箱的投屏：Agent 如何使用 scrcpy、视频和声音在[工具箱协议](toolbox.md)中的封装，以及只用于无线 ADB 的 UDP 媒体通道（报文、加密、纠错、启动确认、关键帧恢复）和编码、显示的策略。被控端可以是手表，也可以是手机；手机官方支持 Android 13 及以上，更低的版本不保证，但不显式拒绝。

## 1. scrcpy 服务端

Agent 运行固定的 scrcpy 4.1 服务端：以官方 v4.1 提交 `2926c06c5dc3064ae6d8db706f1a98a37cfcf3f0` 为基线，加上经能力协商的私有扩展。源码、补丁、构建工具和 Apache-2.0 许可在 [android/thirdparty/scrcpy-server](../../android/thirdparty/scrcpy-server/README.md)；构建结果的 SHA-256 固定，Agent 启动前校验。

| 私有扩展 | 作用 |
|---|---|
| 控制类型 127（无正文） | 轻量关键帧请求：对仍然注册、没有在重置的编码器设置 `PARAMETER_KEY_REQUEST_SYNC_FRAME`；不发送 EOS，不重建采集或编码器，不中断声音 |
| 控制类型 126（4 字节大端码率） | 运行中通过 `PARAMETER_KEY_VIDEO_BITRATE` 调整码率，编码器重建后重新应用 |
| `video_tail_repeat_us` | UDP 会话中由 OpenGL 决定送入编码器的节奏，见第 6 节 |

### 1.1 视频与声音

scrcpy 使用 `send_stream_meta=true`：视频先发 4 字节 codec ID，再发 12 字节会话头（flags、width、height，各 4 字节），然后是带 PTS 和长度的编码帧；第 63、62、61 位分别表示会话头、编码配置和关键帧，尺寸变化时会再次发送会话头。Agent 在这一边界解析会话头，重新发送 `VIDEO_CONFIG`，并把配置和关键帧标记转换为工具协议自己的第 63、62 位，所以手机与 Agent 之间的格式不随 scrcpy 变化。

声音为 scrcpy 的 Opus（48 kHz、双声道）。服务端按视频、音频、控制的顺序接受 socket。音频 socket 先发 4 字节 codec ID：`opus` 表示有声音；0 表示设备采集不了，画面继续，手机提示；1 表示配置错误，服务端退出，会话结束。帧头与视频相同但没有会话头，首个配置包只含 OpusHead。不传原始 PCM（需要 192 kB/s，蓝牙带不动）。手机的解码器或 AudioTrack 满时丢弃声音，不阻塞画面。

视频会话使用 `power_on=true keep_active=true`：开始时点亮屏幕，运行期间每 4 秒报告一次用户活动，避免正常超时熄屏后触摸注入失效；纯控制会话不启用 keep_active。不修改全局的熄屏时间或充电常亮设置。

请求参数包括 `videoCodec`（H.264 或 H.265）、`audio`、`maxSize`（长边像素，64..2560）、`maxFps`（1..120）、码率（0.1 Mbps..1 Gbps）、可选的 `limitToEncoder` 和编码器参数（见第 6.4 节）。手机在首个非空媒体帧到达前最多等 30 秒（只有配置头、编码配置、空帧或不完整帧都不算）；之后不设读取超时，静止的画面可以长时间等待。

### 1.2 控制

触摸、按键、文字、滚动、屏幕电源和剪贴板通过 `CONTROL`（scrcpy 的控制消息）在可靠的工具流上发送。每条控制消息连同 5 字节的帧头一次写出。手机的 `ScreenControlQueue` 只合并尚未发送的连续 MOVE（每根手指保留最新坐标）；DOWN、UP、按键和粘贴是顺序边界，不会丢弃；最多积压 128 个边界，超过时结束会话并报告超时。手指移出画面的实际轮廓（圆形按圆周，矩形按圆角矩形）时，立即在最后的有效位置发送零压力的 UP；关闭触摸或离开页面时补齐所有未抬起的手指（scrcpy 只在 UP 时回收触点，不能只发 ACTION_CANCEL）。

## 2. UDP 媒体通道

UDP 只用于无线 ADB，默认关闭。开启后，建连失败、运行中断线或协议不匹配都会结束本次投屏并报错，**不退回可靠流或蓝牙**。

### 2.1 前提与协商

手机在发送请求前确认：后端是 ADB、有直接的 IP 端点、承载本操作的 ADB 通道实际完成了 STLS 加密、Agent 声明了 `SCREEN_UDP`、`SCREEN_UDP_FEC`、`SCREEN_KEYFRAME` 和 `SCREEN_UDP_STARTUP`，且包含视频。Agent 拒绝非 shell UID、没有持有 Wi-Fi 锁或纯控制的 UDP 请求。

请求声明 `udp=true`、`udpFec=rs_16_4`、`udpLightKeyframe=true`、`udpStartupGate=true`，关闭媒体加密时另加 `udpEncryption=false`（需要 `SCREEN_UDP_AUTH_ONLY`，否则返回 `screen_udp_encryption_mode_unavailable`）。Agent 以 `SCREEN_UDP_READY` 回复端口和本会话随机的 32 字节主密钥，并确认模式：

| 模式 | `mediaSecurity` | 视频版本（RS 16+4） | 音频版本（无 FEC） |
|---|---|---:|---:|
| 默认 AES-256-GCM | `aes_gcm` | 2 | 1 |
| 明文 + HMAC-SHA256-128 | `hmac_sha256_128` | 4 | 3 |

READY 还确认 `videoProtection=rs_16_4`、`lightKeyframe=true`、`startupGate=true`。模式不一致时连握手都无法完成，不会自动切换。

流程：

1. 已认证的 ADB 操作流启动 Agent 并核对设备身份。
2. Agent 绑定随机的视频和音频 UDP 端口（没有声音时只有视频），经 ADB 返回端口和主密钥。
3. 手机向 ADB 主机发起 UDP 握手（HELLO / ACK）。Agent 只有在验证 HELLO 之后才固定目的地址。握手期限 5 秒。
4. 所有请求的通道都握手成功后才启动 scrcpy。视频和音频各有独立的 socket、发送和接收线程以及缓冲。
5. 触摸、按键、剪贴板以及结束和错误仍走可靠的 ADB 操作流；视频和音频不会回落到这条流。

### 2.2 报文与密钥

每个 UDP 数据报最多 1200 字节：32 字节头 + 最多 1152 字节载荷 + 16 字节认证标签，不依赖 IP 分片。头部依次为：magic（4 字节）、版本、媒体类型、片种类、消息类型（各 1 字节）、包序号（8 字节）、消息序号（8 字节）、消息总长度（4 字节）、分片索引（2 字节）、总片数（2 字节）及保留字段。

每个媒体、每个方向使用独立派生的密钥：

```text
derivedKey = HMAC-SHA256(masterKey, ASCII("ticbox-screen-udp-v{version}:{stream}:{server|client}"))
AES-GCM：key = derivedKey，nonce 包含该密钥下单调递增的包序号
HMAC：  tag = first16(HMAC-SHA256(derivedKey, header || payload))
```

- 包序号每个派生密钥独立递增，耗尽时拒绝发送；重开会话必须使用新的主密钥。包序号与媒体的消息序号分开，所以心跳不会制造媒体缺口。
- 验证标签之后才更新重放窗口（16384 个报文）、分配重组缓冲或选择对端。认证失败的报文按丢失处理。
- 关闭媒体加密只取消画面和声音的保密性：链路上的观察者可以看到内容，但认证、防篡改、防重放、FEC 和设备绑定都保留，ADB 控制和密钥交换始终加密。不存在无认证的模式。

### 2.3 视频 FEC

视频使用系统化的 Cauchy Reed–Solomon 擦除码 `rs_16_4`：

- 域为 GF(256)，不可约多项式 `x⁸+x⁴+x³+x²+1`（`0x11d`）。
- 生成矩阵上部为单位矩阵，下部为 Cauchy 矩阵 `C[r,c] = inverse(r XOR (c+4))`，`r=0..3`，`c=0..15`。两组参数不相交，所以任意方形子矩阵可逆。
- 每块最多 16 个数据片和 4 个校验片；最后一块有 k 个数据片时生成 `ceil(k/4)` 个校验片。块内任意丢失不超过校验片数量都能恢复。
- 分片索引按完整块 20 片排列，数据片在前、校验片在后；末块为 `k + ceil(k/4)` 片。块号、片种类和数量都由已认证的总长度和索引推出。片长为 `min(总长度, 1152)`，最后一个数据片用 0 填充到片长，填充必须规范。零长度消息为一个空数据片加一个空校验片。
- 每帧最大 8 MiB，最多约 9103 个报文，仍在重放窗口内。
- 每个校验片也单独认证；FEC 只恢复已认证的分片，不为持有会话密钥却故意发送矛盾校验的一方提供额外的真实性保证。

数学依据参考 [RFC 5510](https://datatracker.ietf.org/doc/html/rfc5510)（域与擦除恢复，本协议不是它的线格式）和 Plank 与 Xu 的 [Optimizing Cauchy Reed-Solomon Codes](https://library.eecs.utk.edu/files/ut-cs-05-569.pdf)。参考向量生成器在 `android/core/protocol/src/test/resources/screen-fec-reference.mjs`。发送端的校验编码有原生 SIMD 实现（ARM64 NEON、ARMv7 NEON、x86 SSSE3，另有标量实现），接收端的恢复使用 Kotlin 实现。音频没有 FEC。

### 2.4 重组与丢失

| 参数 | 视频 | 音频 |
|---|---|---|
| 单条消息上限 | 8 MiB | 64 KiB |
| 重组槽位 | 20 / 60 / 120 fps 时为 7 / 13 / 22 条（解码队列批次上限减 1） | 4 条 |
| 重组字节上限 | 16 MiB | 16 MiB |
| 无进展期限 | 150 ms | 80 ms |
| 大帧最长保留 | 2 秒 | |

后面的消息已经完整到达时，前面的缺口 30 ms 没有进展即判为丢失：每条消息都在下一条之前发完且不重传，缺的数据不会再来。过期或超出窗口的消息被丢弃，后续交付显式标记不连续；容量不足时优先保留较早的连续消息。FEC 修复成功的帧保持参考链连续。

一个已认证、完整收齐、带编码配置和同步帧标记的新关键帧，可以越过旧的缺口直接交付（手机核对 codec、尺寸、generation、解码器状态和递增的 PTS）；普通依赖帧、不完整的消息或重复、过期的 PTS 仍按顺序规则处理。

### 2.5 心跳与生命周期

每 500 ms 一次心跳，静止画面也能判断连通；连续 5 秒没有已认证的回包就结束。PONG 的消息序号字段带回所答 PING 的包序号（其余控制报文为 0），开启帧统计时手机据此计算往返时间。

停止、取消、离开页面、ADB 关闭或任一 UDP 通道故障都关闭本次 UDP 资源和控制流，并沿用 scrcpy 的 socket 和 SIGHUP 收尾。手机检测到本地 UDP 失败后，最多再等 300 ms 接收 ADB 上的具体终止原因，没有时保留本地的 UDP 错误。UDP 端口只存在于本次投屏。

| 错误码 | 含义 |
|---|---|
| `screen_udp_unavailable` | 后端、会话类型、端点或 Agent 能力不支持 UDP |
| `screen_udp_encryption_mode_unavailable` | Agent 不支持所选的明文认证模式 |
| `screen_udp_tls_required` | 当前是明文 ADB，需要用六位码配对的加密无线调试 |
| `screen_udp_connect_failed` | UDP 握手或视频启动未完成 |
| `screen_video_no_frames` | UDP 已握手，但首帧期限内没有任何画面，通常是编码器不接受当前参数 |
| `screen_udp_disconnected` | 已建立的 UDP 或 ADB 会话中断 |
| `screen_udp_protocol_mismatch` | 协议或媒体边界不匹配，包括意外收到可靠流的媒体 |
| `screen_udp_stream_failed` | 媒体或控制接收失败 |
| `screen_udp_recovery_failed` | 首关键帧确认重试耗尽、运行中请求同步帧后仍未恢复，或确认、恢复指令发送超时 |
| `screen_udp_frame_too_large` | 编码数据超过消息或配置的上限 |

两端的局域网必须能直接互通随机的 UDP 端口；AP 隔离或网络策略阻断时会明确失败。

## 3. 启动确认

1. Agent 取得尺寸和 codec 后，经 ADB 以 `PROGRESS {kind:"screen_video_start", version:1}` 发出配置和递增的 `generation`，等待解码器就绪，最长 10 秒。
2. 手机启动解码器，等到第一个可用的输入缓冲后，发送 `PROGRESS {kind:"screen_video_ack", version:1, phase:"decoder", generation}`。界面必须把收到的完整 Configuration 交回 `videoDecoderReady`，不能只用尺寸和 codec 重建而丢掉 generation。
3. Agent 随后才经 UDP 发送配置、CSD 和首个关键帧。首关键帧入队后，手机确认 `phase:"key", generation, ptsMicros`（表示接收器接受了参考链，不是显示确认）。在此之前 Agent 不发送依赖它的增量帧。
4. 首关键帧未被确认时最多发送 5 次，每次后等待 500 ms；重发使用相同的图像和 PTS，但使用新的消息序号、包序号和 nonce，并附带完整的 codec 初始化数据。接收方对重复的首帧只再次确认。

旋转会分配新的 generation，旧代的回执不能释放新代，旧代的 UDP 包在影响参考链之前被过滤。

## 4. 关键帧恢复

参考链断开后，手机经 ADB 发送轻量关键帧请求（控制类型 127）。同一次丢失的重试间隔至少 500 ms，最多 3 次；上一次请求的关键帧收到之后再次丢失算新的一次，距上次请求 150 ms 即可再请求。恢复任务由状态变化唤醒，不轮询。完整的新关键帧可以直接越过旧缺口。

三次轻量请求都失败时，已经接受过参考帧、协商了启动确认的 UDP 会话允许**一次**仅视频的重建（scrcpy 原版的 `TYPE_RESET_VIDEO=17`）：ADB 连接、UDP 端口和加密上下文、声音都保留；新配置必须有更高的 generation，解码器重新就绪并接受该代的新关键帧才算恢复，预算 10 秒。一次投屏最多重建一次。UDP 握手失败和从未接受过首个参考帧的失败不会触发重建。

同时启用 FEC 和轻量关键帧时，周期性的 `i-frame-interval` 提示为 5 秒（编码器常把它换算为帧数，不保证实际间隔）；`frame-rate` 与请求的帧率一致。

## 5. 发送

视频没有发送预算：Agent 拿到一帧就尽快发出，每次调用发出一个完整 FEC 块的数据片和校验片（最多 20 个报文，约 24 KB），心跳在两批之间发送。原生实现使用非阻塞的 `sendmmsg`，内核支持时对等长报文使用 `UDP_SEGMENT`；内核拒绝 GSO 时在该 socket 上记住并改回 `sendmmsg`。部分成功只继续未发的部分；EAGAIN / ENOBUFS 有界等待（最长 1 秒）并监视关闭事件，超时才算发送失败。socket 返回只表示内核接受，不代表对端收到。原生句柄登记在加锁的表中，发送期间持有租约，并发关闭时不会把报文发到被复用的 fd。

声音保持独立的限速。原生库无法加载时投屏报错，不悄悄改用其他传输。

## 6. 编码

### 6.1 码率模式

UDP 会话请求 `video_prefer_cbr=true`：编码器支持 `BITRATE_MODE_CBR` 时在 configure 前请求 CBR；显式的 bitrate-mode 参数优先，不支持或读不到能力时保留原模式。CBR 不保证每帧的大小。

### 6.2 动态码率

Agent 用每帧从采集到从 scrcpy 读出的时间估计本机排队：每 200 ms 窗口取最新的一帧（至少 3 帧），减去最近 8 秒内的最小值。

- **下调**：排队超过 80 ms 时把目标降到当前的 70%，下限为原目标的 1/5 与 200 kbps 中较高者；会话开始 2 秒后才下调，两次下调至少间隔 400 ms。
- **回升**：排队连续 1.5 秒低于 25 ms 后，每秒回升原目标的 10%，直到原目标。

新目标通过控制类型 126 下发。可靠传输不启用动态码率。

### 6.3 MediaTek 编码器

MediaTek 的 H.265 编码器（名称以 `c2.mtk.` 或 `OMX.MTK.` 开头）有两个已知行为：

1. 码率控制会连续十几秒饿在 1–5 Mbps（无论目标多少，CBR 和 VBR 都会），静止时也模糊。UDP 会话中 Agent 默认加上 `video-qp-max=N,bitrate-mode=1`：以 40 Mbps、1072×2400、120 fps 为 N=35，每像素每帧分到的码率减半加 6，限制在 30–45（例如同尺寸 120 fps 时 20 Mbps 为 41，8 Mbps 及以下为 45）。代价是快速滑动时可能超出目标码率。H.264、其他厂商的编码器，以及蓝牙和普通 ADB 传输不加这一默认值。
2. 要等后面再来几个输入才交出前面的画面（`KEY_LATENCY=1` 无效），所以画面停下时最后一次变化要等下一次变化才送出。见下一节的末帧补发。

### 6.4 送入编码器的节奏

UDP 会话中，采集经 OpenGL 画到编码器的输入 Surface，由 OpenGL 决定每幅画面何时送入编码器（无法启动 OpenGL 时改走直接路径，并以框架的帧率限制重启一次编码器）。

- **限帧**：帧率上限由 OpenGL 执行，不设 `max-fps-to-encoder`（框架的限制会丢掉来得太早的画面）。来早的画面等到它的时间槽（最多一个帧间隔，容许提前 2 ms）。
- **末帧补发**：新画面之后 1.5 个帧间隔内没有下一幅，就按帧间隔补画副本，时间戳取当前时间且至少比上一帧晚 1 ms；编码器里已没有真实画面时停止，最多 12 个。副本也计入帧率。不扣帧的编码器基本收不到副本。
- **积压**：编码器中至少有 6 个输入、最老的已等了 100 ms 以上、且 100 ms 内仍有输出时视为积压：新画面先不送，纹理中只留最新一张，积压结束时再送，最多扣住 200 ms；积压期间推迟补发的副本，不占名额。代价是重负载时帧率下降，换来延迟不再累积。
- **关键帧请求**：每次请求成功后立即补画一张当前画面，让扣帧的编码器也能交出同步帧。

### 6.5 其他编码参数

- 普通会话由 scrcpy 设置 `repeat-previous-frame-after=100000` 微秒；UDP 会话覆盖为两个请求帧周期，限制在 16667–100000 微秒。
- Agent 请求 `max-bframes=0`。H.264 的 SPS 缺少 `bitstream_restriction` 时，解码器只能假定可能有重排序，会扣住已解码的画面；手机在配置包和关键帧中改写 SPS，只补上或改写 `bitstream_restriction`，令 `max_num_reorder_frames=0`、`max_dec_frame_buffering=max(1, max_num_ref_frames)`。如果编码器仍产生 B 片，手机停止改写，并在下一个关键帧前恢复原始参数集。
- **按编码器能力限制**（`limitToEncoder`，需要 `SCREEN_ENCODER_LIMITS`）：Agent 在启动 scrcpy 前，用与 scrcpy 相同的 `createEncoderByType` 选出默认编码器，按显示尺寸、`maxSize` 和宽高对齐算出实际尺寸，把帧率限制为 `getSupportedFrameRatesFor` 的上限（最多 120），码率限制为 `bitrateRange` 的上限；没有 H.265 编码器时改用 H.264。`screen_encoders` 操作返回同样的计算结果供界面显示。声明值不是实测能力。
- **编码器参数**（需要 `SCREEN_CODEC_OPTIONS`）：格式为 `键[:类型]=值`，逗号分隔，最多 16 项，只允许字母、数字和 `._-`，手机和 Agent 都校验；追加在 Agent 默认参数之后，同名时以用户填写的为准。

## 7. 手机端的接收与显示

视频解码队列按请求帧率计算批次上限：`ceil(maxFps × 0.15) + 5`，限制在 8–32 批（20 fps 为 8，60 fps 为 14，120 fps 为 23）；待消费的编码数据最多 16 MiB；新帧入队时，最旧的批次达到 150 ms 即超龄。批次数只在最旧的帧超过年龄预算的一半，或达到上限的 4 倍（最多 128 批）时拒收，避免一串新鲜的追帧被误判为积压。超出预算时整组丢弃并等待关键帧；完整的新关键帧可以替换旧积压，增量帧不单独丢弃。150 ms 不是端到端延迟上限；没有新帧时最后一幅画面保留。声音最多 12 批，溢出时丢弃最旧的，视频不等声音。

解码输入上限为宽 × 高 × 1.5，不低于 1 MiB，不高于 8 MiB。

显示使用 `SurfaceView`（由系统直接合成，不经过应用主线程），圆形和圆角由上层的 Canvas 遮住。解码后最多保留 2 个输出缓冲，由独立于解码回调的 Choreographer 线程呈现：每次回调最多呈现一帧，抑制明显早于目标帧周期的呈现；积压时释放最旧的已解码缓冲（不丢弃编码参考帧）。最近一次解码输出后的 250 ms 内持续订阅 VSync；连续 100 ms 没有 VSync 时以本地时间呈现最新输出。“流畅优先”模式最多保留 4 帧：一起到达的画面依次显示；刷新后仍有 2 帧以上在等并持续 250 ms 时丢弃最早的一帧。

只有目标帧率不低于当前显示模式最快的无缝刷新率时，Surface 才请求刷新率。Android 14 起系统可能按 UID 把整个应用的绘制限制到它的 Surface 请求的帧率，较低的请求会拖慢投屏下方的页面。

## 8. Wi-Fi 锁与 ADB 吞吐

只在无线 ADB 的会话中持有 Wi-Fi 锁：

| 端 | 锁 | 说明 |
|---|---|---|
| 手机 | `WIFI_MODE_FULL_LOW_LATENCY` | 亮屏且应用在前台时生效，投屏只在这时运行 |
| 被控端（Android 13 及以下） | `WIFI_MODE_FULL_HIGH_PERF` | Agent 是 shell 进程，不算前台 |
| 被控端（Android 14 及以上） | 同上，申请前暂时修改 `device_config` | 系统默认把它转为要求前台的低延迟锁，由 `wifi/high_perf_lock_deprecated` 控制（只在申请时读取）：Agent 记下原值，改为 false，等 500 ms，申请后立即恢复 |

无线调试连接声明 ADB 的 `delayed_ack`：接收窗口 1 MiB（不用上游 Kadb 的 32 MiB，窗口也是读不过来时的排队长度），每读完一条消息回一个 OKAY，写之前收走已到的 OKAY。adbd 不支持时自动退回逐条确认。

## 9. 诊断

帧统计默认关闭，由 `ToolboxScreenOptions.collectDiagnostics` 控制；关闭时不创建统计对象、不启动定时任务，也不做逐帧计时，切换开关会重建投屏会话。开启后手机记录 `TicBoxProtocol` 日志中 `component=toolbox_screen` 的事件（队列预算、编码器能力与配置、发送端样本、重组缺口、等待关键帧、恢复、呈现间隔的 P50/P95/P99 和 1% low、显示调度等），被控端的发送诊断经 ADB 以 `PROGRESS {kind:"screen_udp_sender"}` 传回。所有字段都是计数、时长和 PTS，不含画面、声音、IP、触摸坐标或密钥。两台设备的时钟不能直接相减；不同窗口的最大值也不能相减。

## 10. 待验证

真实设备上的端到端延迟、编码器的关键帧行为、真实丢包率、GSO 的内核支持、动态码率对各厂商编码器的效果，以及耗电。
