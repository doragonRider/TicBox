# 开发文档

| 文档 | 内容 |
|---|---|
| [构建与检查](building.md) | 工具链、检查命令、产物与签名、版本与发布、数据库兼容规则、代码风格、隐私与测试数据 |
| [架构](architecture.md) | 模块、服务与生命周期、`WatchClient` 和 `CompanionServices` 的契约、界面实现与设计系统、测试方法 |
| [连接](connection.md) | 配对、首次设置、会话与 Channel 引擎、后台恢复、断线排查 |
| [功能](features.md) | 通知、电话、媒体、查找、设备设置、表盘、SOS、勿扰、工具箱、云与 Google 服务、DataApi |
| [健康数据](health.md) | 本地健康同步、查询接口与语义、解析、存储、手表端导出、排查 |
| [手表组件](watch.md) | Local Bridge、工具箱 Agent、进程内模块的构建与安装 |
| [数据迁移](data-migration.md) | 导出与导入的内容和格式、合并规则、格式变更的要求、从 TicWatch 换到 TicBox |
| [能力差距](capability-gaps.md) | 尚未实现、受限制和待实机验收的功能 |

协议规范在 [../spec](../spec/README.md)。开发文档描述本应用怎么做，规范描述线上是什么格式、官方是什么行为；两者冲突时以代码和测试为准，并修正文档。
