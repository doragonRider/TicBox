# 协议规范

本目录规定 TicBox 与 TicWatch 手表之间使用的全部协议：官方的 Wear OS 伴侣协议（本应用作为替代的手机端实现），以及本应用自己的手机–手表私有协议。正文、[Schema](../../schemas)、生产源码和合成测试向量共同构成可实现的约定；实现和测试不需要任何官方 APK 或固件。

## 索引

### 官方协议

| 规范 | 内容 |
|---|---|
| [主传输](transport.md) | RFCOMM 角色与 UUID、CONNECT、分片与 RPC、Channel 状态机、DataItem / DataMap / Asset、能力公告 |
| [身份与权限](identity.md) | 线上 AppKey、厂商路由、安装签名、节点门控、Android 14+ 的 CDM |
| [配对与首次设置](pairing-setup.md) | Android 关联与绑定、持久身份、Setup 的报文、命令、步骤、恢复和完成判据 |
| [保留数据解除配对](pairing-unlink.md) | WCS 版本确认、`skipWipe`、回执和本机清理 |
| [转移到新手机](phone-transfer.md) | 官方的手机转移流程与转移后的重连 |
| [伴侣业务消息](companion.md) | 通知与动作、电话、媒体、找手机、Remote Intent、设置、锁屏、表盘、SOS、截图 |
| [健康数据](health.md) | Provider 的 POINT / SESSION / SPORT、41 种 POINT 类型、睡眠关联、官方健康消息；[类型目录](health-types.json) |
| [私有运动数据库](fitness-database.md) | RECORD 29 列 / POINT 19 列、字段复用、两条时间轴、GPS、圈、GROUPS / HIIT；[列结构](fitness-database-schema.json) |
| [健康数据展示参考](health-display.md) | 官方 App 的统计和展示算法、未确认字段的推测、本应用的图表投影 |
| [监测与提醒设置](monitoring-settings.md) | 官方的设置键、取值和副作用，本应用的读写方式与进程内模块；[设置目录](monitoring-settings-catalog.json) |
| [通知的声音与振动](notification-alerts.md) | 官方 SystemUI 的提醒行为、手机端的提醒策略、可选的 SystemUI 补丁 |
| [账号、云与 Google 服务](cloud.md) | Mobvoi 账号与健康云 REST、手表资料桥、Google 账号复制、私有服务权限、官方的云同步算法 |
| [Flow15 与旧 BLE](flow15-legacy.md) | 手机网络代理的握手与帧；旧 Mobvoi GATT 帧 |

### 本应用的私有协议

这些协议只在手机和手表都安装本应用（同一 applicationId `dev.ticbox.companion`、同一签名）时可用。

| 规范 | 内容 |
|---|---|
| [本地桥接协议](local-bridge.md) | 健康数据的唤醒、专用 RFCOMM、分页传输、增量同步、root 运动采集、变更日志、手动 JSON 导出 |
| [手机找手表](watch-finding.md) | Remote Intent 启动响铃页、停止与执行反馈 |
| [工具箱协议](toolbox.md) | 无线 ADB 与 root 两种连接、帧格式、操作、断点续传、PTY 终端、勿扰同步 |
| [远程屏幕](remote-screen.md) | scrcpy 的使用与扩展、UDP 媒体通道（加密、FEC、启动确认、关键帧恢复）、编码与显示策略 |

## 适用版本

规范依据以下公开发布的软件版本。其他版本可能不同；实现不以这些版本作为白名单，除非正文明确写明（例如需要精确匹配 APK 的进程内模块和变更日志）。

| 软件 | 包名 | 版本 |
|---|---|---|
| Mobvoi Health（官方手机 App） | `com.mobvoi.companion.at` | 5.3.0-2997.1962 |
| 手表伴侣 | `com.mobvoi.companion.aw` | 5.5.0-51.2904 |
| TicHealth | `com.mobvoi.wear.health.at` | 5.5.0-3292.2931、5.6.0-3234.2686 |
| TicFitness | `com.mobvoi.wear.fitness.at` | 5.5.0-2536.2932 |
| TicWatch SystemUI | `com.mobvoi.wear.refsysui` | 1.5.0 / 150 |
| Google Play 服务 | `com.google.android.gms` | 24.28.35（手机和手表）；Wear 云同步的权限链另参照 23.25.18 |
| 手表系统 | | Wear OS 3.5（API 30）、Wear OS 4（API 33） |

适用的手表为 TicWatch Pro 5、TicWatch Pro 5 Enduro 和 TicWatch Atlas。手机端要求 Android 14（API 34）及以上；手表上的本应用组件要求 API 30 及以上。

## 术语

| 术语 | 含义 |
|---|---|
| P→W / W→P | 手机到手表 / 手表到手机 |
| 伴侣路由（companionRoute） | 以官方伴侣 AppKey 收发的 Wear 消息路由，见[身份与权限](identity.md) |
| 本应用的 AppKey | 本应用的实际包名加安装证书（DER）的 SHA-1，用于私有协议的消息 |
| WCS | Wear Companion Service，手表上的 `com.google.android.wearable.app` |
| Local Bridge（本地桥接） | 本应用安装在手表上的组件，负责健康读取、找手表、勿扰同步和 root 工具 |
| Agent | 工具箱在目标设备上以 `app_process` 运行的进程 |
| 进程内模块 | 可选的 Magisk / Zygisk 模块 `ticwatch_health_settings`，见[监测与提醒设置](monitoring-settings.md#52-进程内模块) |
| 未确认 | 没有足够依据确定含义；原值保留，不猜测 |
| 本实现 | 本应用自己的选择，不是官方协议的要求 |

## 约定

- 整数精度、字节序、包名、摘要、路径的前导斜杠、角色和序号以各章节为准。字段表与 `.proto` 不一致时，以当前代码和测试为准。
- 未知字段保留；异常值尽量解释，并记录处理决定。官方的默认值和本应用的资源预算都不是所有固件的硬性限制。
- 健康数据的原始行必须完整保留，不能因为某个值无法识别而丢弃整批数据。
- 合成测试向量位于 [tests](../../tests) 和各 Android 模块的测试中，不需要协议生成器或官方二进制。运行方法见[构建与检查](../dev/building.md)。
- 只读、离线是默认行为。会联网、修改官方数据或需要 root 的功能都需要用户明确开启，并在正文中注明。
