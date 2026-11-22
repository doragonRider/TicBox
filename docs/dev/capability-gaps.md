# 能力差距与验收状态

与官方 Mobvoi Health 相比，下面分三类：确实没有实现的业务、受来源或权限限制的功能、已实现但还需要真实设备验收的功能。判断是否已接线以 [CompanionServices](../../android/core/api/src/main/kotlin/dev/ticbox/companion/api/CompanionServices.kt) 和 [AndroidCompanionRuntime](../../android/core/platform/src/main/kotlin/dev/ticbox/companion/platform/AndroidCompanionRuntime.kt) 为准；规范里出现某个路径不等于该业务可用。

## 1. 尚未实现

| 功能 | 说明 |
|---|---|
| 截图批量取回 | 官方有 load / count / start / data / received 五类消息（[伴侣业务消息](../spec/companion.md)），`photo_received` 还会让手表删除已传的文件；本应用没有截图服务、保存和逐张确认 |
| Health Connect 输出 | 官方把心率、血氧、睡眠、运动、步数、热量转换写入 Health Connect；本应用没有 Health Connect 客户端。这是独立的本地系统集成，不需要云 |
| 充满电提醒 | 有电量刷新，但没有处理 `/companion/battery_full` |
| 首次设置的附加步骤 | 恢复、day-zero 更新、Assistant、锁屏和应用安装的自动编排返回 unavailable（锁屏的独立入口已实现） |
| Mobvoi 账号的完整生命周期和上传 | 有密码 / 验证码登录、资料、时区、退出、云健康下载和运动详情；没有注册、重设密码、第三方授权绑定、头像上传，以及普通 data set / session 的上传。都是可选功能，默认不联网 |
| 剧院模式、就寝模式的同步 | 只实现了单向的勿扰跟随；官方的双向模式同步未实现 |

## 2. 受来源或权限限制

| 项目 | 状态 |
|---|---|
| SPORT 训练摘要 | 读取、传输和保存已实现，但 Provider 需要厂商的 signature 权限 `FITNESS_DATA`，普通应用被拒绝（`provider_denied`），运行时授权无法解决 |
| 完整训练、GPS 路线、圈和分段 | 解析、手机存储和查询已实现；数据来源是可选的 root 运动采集（见[健康数据](health.md)）。每公里分段等派生统计由界面按[健康数据展示参考](../spec/health-display.md)计算 |
| Google 账号复制、Google 云同步 | Channel、PFD 和 Binder 适配已实现；障碍是 GMS 私有服务的签名名单，本应用不在默认名单中。本地健康不依赖这些服务 |
| MWF 表盘安装和表盘商店 | ZIP 传输已实现；包内的安装语义和远端的安装确认未知，没有商店目录、购买和下载 |
| 个别 POINT 字段 | 类型 3（位置）和 31（AI 运动评级）缺少专门的写入和读取方；类型 35 的第 0 槽未知；类型 36 同一数据的界面标签互相冲突；告警的附加字段和噪声单样本的时间缺少依据。原值保留，不判为废弃，见[健康数据展示参考 §7](../spec/health-display.md#7-未确认字段的推测) |
| 监测设置 | 34 项：18 项走 Provider / 类型 16，16 项需要进程内模块。噪声配置缺少对应的官方版本，不声称覆盖所有版本 |

## 3. 已实现，待真实设备验收

| 功能 | 主要待验收内容 |
|---|---|
| 首次设置、配对、CDM | 系统回调、厂商的 WATCH profile 策略、Android 14+ 权限传输的端到端结果、新配对清除旧绑定（API 34/35 的系统页、API 36+ 的公开接口） |
| 后台恢复 | 长期日用、进程回收与重启的组合 |
| 保留数据的解除配对、迁移确认 | 只确认了一个 WCS 接收版本，未知版本不开放；ACK 不证明远端清理结束；无 Google 账号的迁移交换和随后的普通连接只有合成测试 |
| 通知、媒体、电话 | 通知动作、文字回复和删除；媒体 v2；InCallService 绑定、HFP 选路、第三方 VoIP 的可见性；任意 RemoteViews 不支持 |
| 查找 | 手机找手表的停止和超时等组合 |
| SOS | 真实的求助事件、短信和定位、与各固件的组合 |
| 本地健康 | 多页 SESSION 的无线入库、长历史的增量同步、root helper 和耗电 |
| root 运动采集、变更日志 | 真实 Magisk 下的启动，非空的跑步（GPS、暂停、圈）、游泳和 HIIT |
| 进程内模块、SystemUI 补丁 | 见[手表组件 §4](watch.md#4-待验证) |
| 勿扰同步 | 真实手表上的规则启停 |
| 工具箱与远程屏幕 | 见[功能 §12](features.md#12-待验证)和[远程屏幕 §10](../spec/remote-screen.md#10-待验证) |
| DataItem / Asset、Flow15 代理 | 真实对端和所选业务；代理默认关闭 |
| 表盘、设备设置、锁屏、卸载 | 打开远端界面不表示用户最终完成了安装、锁屏或卸载 |
| Mobvoi 登录和云下载 | 真实账号和服务端的兼容性 |

## 4. 不在范围内

付费 AI 功能；eSIM、支付、语音助手和应用商店（只有零星线索，不能断定官方客户端必有而本应用缺失）；“官方全量历史蓝牙导出”（没有实现迹象）。图表分桶、楼层折算、目标环和额外的睡眠统计属于界面展示。

优先顺序：先验收 root 运动来源和其他实机项目，再按需要增加截图、Health Connect 等独立功能。扩大可选的云能力不能补齐本地数据来源。
