# 健康数据：同步、存储与查询

本文说明手机端如何同步、保存和查询手表的健康数据。手表端的读取与传输见[本地桥接协议](../spec/local-bridge.md)，字段含义见[健康数据](../spec/health.md)、[私有运动数据库](../spec/fitness-database.md)和[健康数据展示参考](../spec/health-display.md)。

分工：**后端**负责读取手表字段、解析、入库、来源隔离和查询；**界面**决定展示、日期归属、额外统计和图表。

## 1. 同步：`services.localHealth`

```kotlin
services.localHealth.sync()                        // 默认 INCREMENTAL
services.localHealth.sync(LocalHealthSyncMode.FULL) // 重新读取全部启用的来源，不删除归档
services.localHealth.cancel()                      // 保留已提交的进度
```

- `sync()` 启动或合并服务持有的任务，不等待整轮完成；没有就绪的主连接时不发请求。主连接就绪和手机 Activity 重新打开都会自动触发。页面退出不取消任务。
- `FULL` 替换进行中的增量任务，重复的全量请求合并。“设置 → 诊断 → 立即全量同步数据库”调用它。
- 首次同步只读最近 3 天（`LocalHealthSyncClient.FIRST_SYNC_WINDOW_DAYS`）的 POINT 和训练明细，SESSION、训练摘要和 SPORT 摘要照常全部读取。手表上有更早的数据时，状态的 `deferredBeforeMillis` 给出边界，健康页显示横幅和“全部同步”（即 `FULL`）；全量完成后它变为 null。规则见[本地桥接协议 4.6](../spec/local-bridge.md#46-首次同步的时间窗)。
- 来源：普通模式为 POINT、SESSION 和 SPORT 摘要；手表开启 root 运动采集后，以 `FITNESS_RECORD` / `FITNESS_POINT` 代替 SPORT 摘要。每个来源有独立的可用状态和续传位置。

| phase | 含义 |
|---|---|
| `IDLE` / `DISCONNECTED` | 等待同步，或没有主连接 |
| `CONNECTING` / `READING` / `RETRYING` | 唤醒、分页读取或断点重试 |
| `COMPLETED` | 这一轮有界扫描完成，所有页已提交 |
| `PARTIAL` | 部分表完成，但有不可读的单元格、停滞的页或不可用的表；`reason` 标明表名，例如 `fitness_summary:provider_denied` |
| `UNAVAILABLE` | 手表未响应、Provider 缺失或拒绝；已有缓存仍可读 |
| `FAILED` / `CLOSED` | 本地操作失败 / 服务生命周期结束 |

状态还包括 `mode`、`source`、`sessionId`、`pagesCommitted`、`recordsCommitted`（本次提交的行数，包括更新，不是新增数或总行数）、`lastSuccessAtMillis`（连接时从数据库各表的 `last_success` 恢复）、`deferredBeforeMillis`（首次同步留在手表上的数据的边界，只在内存中）、`lastRun`（上一轮按表和阶段 checkpoint / open / wake / wait / connect / handshake / read / save / ack / retry_wait 汇总的耗时；手表报告自己的处理时间时，read 再拆成 watch / transfer，并带上收到的字节数）和 `advertisedTables`。完成后界面从 offset=0 重读缓存，不要等待某个可能一闪而过的中间阶段。

### 1.1 变更日志

```kotlin
services.localHealth.journalStatus(LocalHealthJournalSource.HEALTH)
services.localHealth.setJournalEnabled(LocalHealthJournalSource.HEALTH, true)
services.localHealth.setJournalEnabled(LocalHealthJournalSource.FITNESS, true)
```

需要手表允许健康读取和 root。页面为 [HealthJournalScreen](../../android/ui/src/main/kotlin/dev/ticbox/companion/ui/bridge/HealthJournalScreen.kt)，在本地桥接页就绪后进入。

- 同步进行中返回 `journal_sync_in_progress`；旧版 Bridge 返回 `journal_bridge_update_required`。操作绑定当前手表和连接代次。
- `installed=null` 表示未能观察，不是未安装。手机接口 90 秒截止时抛 `LocalHealthJournalException("journal_operation_unconfirmed")`，不能据此判断写入未提交；写回包丢失时不自动重试，先查询状态。
- 安装状态可以幂等查询，但不会静默补装缺失的触发器（可能已经漏过写入），需要移除、重新启用并重建基线。

日志的结构、准入条件和恢复规则见[本地桥接协议 §6](../spec/local-bridge.md#6-变更日志可选需要-root)。

## 2. 查询：`services.healthRecords`

只读手机本地数据库，没有登录、HTTP、同步或清除操作，不会隐式连接手表或云。类型见 [HealthRecords.kt](../../android/core/api/src/main/kotlin/dev/ticbox/companion/api/HealthRecords.kt)、[HealthPoints.kt](../../android/core/api/src/main/kotlin/dev/ticbox/companion/api/HealthPoints.kt)、[HealthSleep.kt](../../android/core/api/src/main/kotlin/dev/ticbox/companion/api/HealthSleep.kt) 和 [HealthFitness.kt](../../android/core/api/src/main/kotlin/dev/ticbox/companion/api/HealthFitness.kt)。

### 2.1 来源

| `HealthRecordSource` | 含义 |
|---|---|
| `LocalDevice(localUserId, peer)` | 本应用的本地档案 ID（与界面共用 `frontend-profile/id`）加手表，不需要云账号 |
| `MobvoiCloud(owner)` | 下载到本机的 Mobvoi 账号缓存，按 region / wwid / accountId 隔离；读取它也只访问本地 |

两种来源即使 ID 相同也不合并或去重。`sourceName` 是记录内部的来源索引（例如 Provider authority 加表名），与存储来源无关。`sources()` 返回本地存储已知的来源。

### 2.2 接口

| 方法 | 返回 |
|---|---|
| `points(HealthPointQuery)` | 结构化的 POINT：`typeCode` / `type`、源时间、设备和用户标识、同步和删除标记、`values`、`motion`、`raw`、`issues` |
| `sleepRecords(HealthSleepQuery)` | 睡眠会话：原始 sessionId、时间、`motionIntervals`、关联的测量点 `relatedPoints` 和 `relatedPointsStatus` |
| `fitnessRecords(HealthFitnessRecordQuery)` | 完整训练：29 列的解析结果、GROUPS / HIIT、恢复时间 |
| `fitnessPoints(HealthFitnessPointQuery(source, recordId, …))` | 按父训练类型解释的逐点样本和圈、游泳投影；父键为 `RECORD._id`，不能传 HASH |
| `records(kind, HealthRecordQuery)` | 原始行：`DATA_SOURCE`、`POINT`、`SESSION`、`SPORT`、`PROFILE`、`SETTING`、`FITNESS_RECORD`、`FITNESS_POINT` |

```kotlin
val source = HealthRecordSource.LocalDevice(localProfileId, peer)
val heart = services.healthRecords.points(HealthPointQuery(
    source, fromMillis = dayStart, toMillis = dayEnd,
    typeCode = HealthPointType.HEART_RATE.code, limit = 100,
))
val workouts = services.healthRecords.fitnessRecords(
    HealthFitnessRecordQuery(source, fromMillis = start, toMillis = end, limit = 100))
val samples = workouts.items.firstOrNull()?.id?.let { id ->
    services.healthRecords.fitnessPoints(HealthFitnessPointQuery(source, recordId = id, limit = 200))
}
// nextOffset 非空时继续同一查询；不要只读第一页就绘制“完整路线”。
```

### 2.3 共同语义

- **状态**：`SOURCE_UNAVAILABLE` 表示该来源或表还没有成功采集过（未接入、Provider 拒绝等，原因看 `localHealth.state.reason`），不能显示为“手表没有数据”，也不能引导用户必须登录。`AVAILABLE` 的空页只表示本地没有匹配的记录。
- **数值**：`decimal` 是保精度的十进制字符串；只有确认了语义和单位的值才非空，未知布局在 `issues` 中标明，绝不补零。`raw` 保留整行。
- **时间过滤**：闭区间重叠（`end >= from` 且 `start <= to`），缺失时间的记录也可能返回；本地 SESSION 的非正终点按开放区间处理。相邻日期可能重复返回同一条，界面按 source + kind + key 去重并决定日期归属。`updatedAtMillis` 是手机写缓存的时间，不是采样时间。
- **分页**：`offset` 非负，`limit` 1–500，按开始时间、key 升序（缺开始时间的在前）；`nextOffset == null` 表示没有下一页。分页不是数据库快照，缓存变化后从 0 重读。
- **删除**：默认隐藏删除标记，`includeDeleted=true` 可查已采集的墓碑。`fitnessPoints()` 指定父 ID，可以查已删除训练的点。手表上物理消失的行标记为 `sourceAbsent`，手机保留旧原行和解析值，不当作用户删除。
- **记录 key**：POINT 为 `_id` 的十进制字符串；SESSION 为原始 `columns.id`；SPORT 为 `["<HASH>",<sourceOccurrence>]` 形式的 JSON 文本（保留可能重复的 HASH）。界面原样传回 `recordKey`，不自行拼接。
- **父记录晚到**：父训练尚未入库时，`fitnessPoints()` 仍返回点、原始列和 `PARENT_UNAVAILABLE`；父行到达后读取时用当前父行重新投影。
- **不要记录**：`rawJson`、记录 key、账号资料和测量值不能写进日志。

### 2.4 原始行的 JSON

```json
{"id":"123","columns":{"_id":123,"values":"原始字符串","type":999,"time_from":1000,"time_to":2000,"deleted":0,"future_column":null},"storageTypes":{"_id":"integer","values":"string","future_column":"null"}}
```

（合成数据。）`columns` 保留 Provider 返回的所有列；`storageTypes` 为 Cursor 的 null / integer / float / string / blob-base64 / unknown。整数保留 int64 精度，blob 为不换行的 Base64，非有限浮点保留为字符串。单元格读取失败时保留其他字段并记录 `partial` / `unavailableColumns`。这是 Cursor 数据的表示，不是 SQLite 文件或最初的 HTTP 响应。

本地 SPORT 摘要的列为 `id, wwid, device_id, time_from, time_to, type, synced, during, calorie, heart`：`during` 为毫秒，`heart` 为平均心率（没有有效样本时可能为 0），`calorie` 为 kcal。它没有轨迹、步数或距离，不能套用云端 SPORT JSON 的格式。

## 3. 解析

- [HealthPointParser](../../android/core/engine/src/main/kotlin/dev/ticbox/companion/engine/health/HealthPointParser.kt)：41 种 POINT 类型的字段、单位和枚举，依据 [health-types.json](../spec/health-types.json)。
- `HealthSportParser`：SPORT 摘要的元数据和问题标记。
- [HealthFitnessParser](../../android/core/engine/src/main/kotlin/dev/ticbox/companion/engine/health/HealthFitnessParser.kt)：`parseRecord(raw)`、`parsePoint(raw, parent)`。ID、计数和时间用 Long，小数用十进制字符串，不经过 Double。`startMillis` / `endMillis` / `timestampMillis` 是墙钟时间，`durationMillis` / `elapsedMillis` 是有效训练时间（暂停时两者不同）。GROUPS 按原索引的奇偶生成训练和组间休息，单个坏边界不会让后面的错位。`sentinelColumns` 只注记已知的占位值，不删除源值。未知运动不拒收。
- 原始 `points` / `track_points`、未知扩展和旧云的 `string_val` 都保留在原始行中。

## 4. 存储

`SQLiteLocalHealthStore`（`no_backup/data-sync.sqlite` 中的健康表）：

- 每页的原行、解析结果（`parsed_payload`）、索引和断点在同一事务中提交，然后才向手表 ACK；断线重放按 epoch / revision 幂等。
- 运动私有表的 `sourceName` 为 `sqlite://com.mobvoi.wear.fitness.at/fitness.db/RECORD` 和 `/POINT`，`parent_key` 索引支持按父训练查询点。
- 健康数据来源和云缓存按来源身份隔离，共享数据库 128 MiB 的预算。
- 结构改动的规则见[构建与检查 §5](building.md#5-数据库与格式的兼容)。

`services.health`（Mobvoi 云）负责可选的云同步和账号资料；退出账号或关闭云同步不影响只读的 `healthRecords`，清除云缓存只影响那个账号来源。

## 5. 手表端的手动导出

手表 Local Bridge 的 `HealthExportActivity`（桥设置页的“导出健康 JSON”）由用户选择范围后导出，文件写入手表的 `Download/TicBox/`（`MediaStore.Downloads`，不需要存储权限或 root）。打开页面不会查询；不接受 Intent 传入的 SQL、URI 或自动开始指令。格式和默认的匿名化见[本地桥接协议 §7](../spec/local-bridge.md#7-手动-json-导出)。

```bash
adb -s <watch> shell am start -W -n dev.ticbox.companion/dev.ticbox.companion.healthbridge.HealthExportActivity
adb -s <watch> shell ls -lt /sdcard/Download/TicBox/
adb -s <watch> pull "/sdcard/Download/TicBox/<文件名>.json" health-export.json
```

导出文件包含健康值和时间，完整运动的 POINT 还包含 GPS，匿名化选项不会移除这些内容。**不要把导出文件提交到仓库**，测试数据必须是独立构造的合成数据。

debug 构建的手表 APK 另有 `HealthSampleActivity` 采样检查页（只输出元数据，结果写应用私有目录），release 不包含。

## 6. 排查

```bash
adb -s <phone> logcat -d -t 500 -s "TicBoxProtocol:I" "*:S"
adb -s <watch> logcat -d -t 300 -s "TicBoxHealthBridge:D" "*:S"
```

一轮同步的完整耗时（每张表一行，另有一行总耗时）。“诊断 → 上次同步耗时”只保留最近一轮，所以全量同步结束后尽快读取：

```bash
adb -s <phone> logcat -d -s "TicBoxProtocol:I" | grep run_timing        # Windows 用 findstr run_timing
```

手机 `component=local_health` 的 `page_committed`、`scan_completed`、`watch_read_unavailable`、`bridge_timeout`、`bridge_connection_failed`，以及手表的 `Local Bluetooth read window opened`、`Local page sent`（带错误码），可以区分主连接、唤醒、专用 RFCOMM、Provider 和入库各阶段。日志只有阶段、页和行的计数、异常类和稳定的原因码，不含 nonce、健康值、wwid 或数据库内容。

- Provider 查询可能冷启动官方进程并触发它的初始化和联网任务；需要确保离线时，先关闭手表的 Wi-Fi，用 USB ADB 测试。
- 系统强行停止、禁用应用、撤销蓝牙权限，或 GMS 不派送唤醒事件时，手机显示不可用并保留已入库的记录。
- 变更日志页显示“数据源不在手表上”（`journal_database_missing`），或开了 root 运动采集却始终没有训练：root helper 没有在全局挂载空间运行，看不到官方应用的数据目录（见[本地桥接协议 §5](../spec/local-bridge.md#5-root-运动采集)）。1.0.0 及更早的 Local Bridge 都是这样，更新到 1.0.1 后重新开启日志。在手表上对比 `su -c 'ls <dataDir>/databases'` 和 `su -c 'nsenter -t $(pidof dev.ticbox.companion) -m ls <dataDir>/databases'` 可以确认。
- 变更日志页显示“没能从手表读到日志的状态”，或开启/关闭后显示“没能确认结果，已重新读取状态”：手机等满 90 秒没有回复，或手表的 root 辅助进程超时、遇到了非预期的异常。复现后读取两边的日志：手机 `adb logcat -d -s TicBoxProtocol:I | grep journal_result`（`outcome` 为 `phone_timeout` 时是手机等待超时），手表 `adb logcat -d -s TicBoxHealthBridge:*`，其中 `Journal helper failed` 的 `step`、`exception` 和 `at` 说明在哪一步、因什么异常、在哪段代码失败，`Journal helper timed out` 表示辅助进程超时。1.0.3 及更早在真机上都会以 `step=open exception=SecurityException` 失败：辅助进程降到官方应用的 UID 后，SQLite 第一次打开数据库时要经系统 context 读取一项全局设置，这个 Binder 调用因包名与 UID 不符被拒绝；1.0.4 改为在降权前先打开一个内存数据库，完成这次读取。
- 增量同步比预期慢：手表日志里每次采集的 `Capture planned` 列出读取的范围，`Recent range widened` 说明有多少会话把近期范围向前扩展，`Local changes sent` 的 `scanned` 和 `Read-ahead` 给出实际读了多少行。

## 7. 待验证

首次同步时间窗在真机上的效果（判断旧数据的那一页、横幅、全部同步后横幅消失）、多页 SESSION 的无线入库、非空的 root 运动采集（跑步的 GPS、暂停和圈，游泳，HIIT）、变更日志在真实 Magisk 下的安装与恢复、Local Bridge 进程被回收后的冷启动唤醒和长期耗电、未来的固件版本。
