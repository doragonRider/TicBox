# 数据迁移（导出与导入）

入口：设置 → 数据 → 数据迁移。导出写到用户在系统文件选择器中选择的位置；导入时选择本应用（或 TicWatch）导出的文件。用途：

- 换手机：带走健康数据、设置、SOS 联系人和无线调试密钥。
- 同一台手机换用另一个包名的应用（例如从 TicWatch 换到 TicBox）：再加上手表配对身份，新应用沿用原来的身份连接手表。

实现为 [AndroidDataMigration](../../android/core/platform/src/main/kotlin/dev/ticbox/companion/platform/migration/AndroidDataMigration.kt)，由后台服务持有，界面经 [DataMigration](../../android/core/api/src/main/kotlin/dev/ticbox/companion/api/DataMigration.kt) 调用。

## 1. 内容

导出时逐类选择，导入时可以从文件已有的类别中再选。

| 类别 | 包含 | 导入方式 |
|---|---|---|
| 健康数据（HEALTH） | `local_health_sources`、`local_health_records`、`cloud_cache`、`cloud_sync_windows`、`observation_samples` | 合并 |
| 应用设置（SETTINGS） | `frontend-appearance` 偏好（外观、远程屏幕预设、步数目标等）、应用语言、通知 / 媒体 / 通话 / 勿扰 / 显示 / 查找设置文件 | 文件中的值覆盖本机同名项 |
| SOS 联系人（SOS） | `companion/sos-watch/*.json`（按手表）、`companion/sos-assistance-v1.json` | 覆盖 |
| 无线调试密钥（ADB） | `toolbox-adb/host-key.pem`、`toolbox-adb-endpoint` 偏好（上次连接的设备、主动断开的选择） | 覆盖 |
| 手表配对身份（PAIRING） | `companion/identity.bin`、`companion/toolbox-bridge-key.bin`、通知 revision 计数、连接目标（手表地址和自动重连开关），以及全部 DataApi 同步表 | 替换 |

配对身份默认不勾选。同一个身份不能同时在两台手机或两个应用上连接同一块手表，导出后要在原应用中断开连接（见第 5 节）。

始终不导出：

- Mobvoi 账号凭据：由 AndroidKeyStore 中不可导出的密钥加密，新应用需要重新登录。
- 云端授权（`cloud-privacy.settings`）：不能被另一个安装默默继承。
- CDM 关联映射：associationId 只属于创建它的应用。
- 正在进行的 SOS 求助、诊断日志、文件传输和缓存目录。

## 2. 文件格式

不设密码时是普通 ZIP（建议文件名 `TicBox-<日期>.zip`），可以在电脑上解开查看。设了密码时整个 ZIP 被封装为 `.tbbackup`（TicWatch 导出的 `.twbackup` 格式相同）：

```text
"TWMIGENC" | 版本 u8 = 1 | PBKDF2 迭代次数 u32 | 盐 16 字节 | nonce 前缀 7 字节
然后重复：密文长度 u32 | 密文（明文 64 KiB 一块，AES-256-GCM，含 16 字节标签）
```

密钥由 PBKDF2-HMAC-SHA256（默认 600000 次）从密码派生。每块的 nonce 由前缀、块序号和“最后一块”标记组成，所有块都认证文件头，所以调换、删减或截断都无法解开。第一块解不开报“密码不对”；之后的块解不开或文件被截断，报“文件已损坏或不完整”。魔数 `TWMIGENC` 为兼容保留，不随应用改名。

| ZIP 条目 | 内容 |
|---|---|
| `manifest.json`（第一个条目） | `format`、`schema`（手机数据库版本）、`app`、`versionCode`、`createdAt`、`sections` |
| `tables/<表名>.jsonl` | 每行一个 JSON 对象，键为列名 |
| `settings/…`、`sos/…`、`adb/…`、`pairing/…` | 设置和身份文件的原始字节；SharedPreferences 和连接目标为 JSON |

表格行按 SQLite 的实际存储类型编码：`null`、整数、字符串，BLOB 为 `{"blob":"<Base64>"}`，REAL 为 `{"real":数值}`。SPORT 的分页边界以 BLOB 存在 INTEGER 列中，按此规则原样往返。`observation_samples` 不导出 `sample_id`，导入时重新编号。

导入只检查 `format` 和 `schema`，不检查 `app`，所以不同包名的应用可以互相导入。

## 3. 导入过程

1. 选择文件后只读 `manifest.json`，显示导出时间、应用版本和包含的类别；加密文件先要密码。
2. 点“导入并重启”后先完整读一遍：每行都解析，每个设置文件都用各自的解码器检查。这一步失败不会停止任何东西。
3. 停止后台服务（手表断开），等正在进行的数据库操作结束并关闭数据库。
4. 在一个事务中合并所有表；外键检查推迟到提交时，失败则全部回滚。
5. 事务提交后再写设置、SOS、密钥和身份文件。
6. 应用重新启动，启动后提示“数据已导入”或失败原因。第 3 步之后的失败同样重启，因为服务已经停止。

合并规则：

- 健康记录按 `(local_user, peer, kind, record_key)` 合并，保留 `updated_at` 较新的一份；文件中的 `local_user` 一律改为本应用的本地档案 ID。
- 扫描进度（`local_health_sources`）保留 `last_success` 较新的一行，用 UPDATE 原地更新（整行替换会经外键级联删掉它的记录）。
- 云端缓存按主键保留 `updated_at` 较新的；同步窗口取并集。
- 电量和步数历史去重后按接收时间重排，仍只保留最近 100000 条。
- 通知 revision 计数和勿扰设置中的 revision 取本机和文件中较大者，因为手表只接受递增的 revision。
- 配对身份：先删除本机所有 DataApi scope（级联删除其余同步表），再写入文件中的同步表。连接目标不带 associationId，服务在没有关联时直接连接已绑定的手表，之后有了本应用自己的关联再采用。

## 4. 版本与以后修改数据格式

最低支持：手机数据库 schema 1，容器 `format` 1，加密版本 1。文件的 `schema` 或 `format` 高于本机时拒绝并提示先升级应用；低于最低支持时拒绝。

以后修改任何持久化数据，都要同时让导出和导入跟上：

1. **改 [DataSyncSqlSchema](../../android/core/platform/src/main/kotlin/dev/ticbox/companion/platform/data/DataSyncSqlSchema.kt) 的表或列**：除了提高版本并写 `onUpgrade`（[构建与检查 §5](building.md#5-数据库与格式的兼容)），还要把 [MigrationTables.SCHEMA](../../android/core/platform/src/main/kotlin/dev/ticbox/companion/platform/migration/MigrationTables.kt) 提到同一版本、更新列清单，并在 `decodeRow` 中把 `MIN_SCHEMA` 以来每个旧版本的行升级为当前形状（补默认值或转换）。新增的表要归入某一类，或写进 `notCarried` 并注明理由；不迁移的列写进 `omitted`。版本或列不一致时 `MigrationTablesTest` 失败。只改索引不影响文件。
2. **改设置、身份等文件或 SharedPreferences 键的格式**：导入写入的是原始字节，所以新代码必须仍能读旧格式，或在 `AndroidDataMigration.writeFiles` 中转换。新增需要迁移的持久化文件时加入 `FILES` 并决定所属类别。这些文件里不能出现随包名变化的标识，否则旧应用的导出将无法导入。
3. **改容器布局**（条目名、manifest 字段含义、加密头）：提高 `FORMAT` 或加密版本，并保留读取旧布局的代码。
4. 只有确实无法再读某个旧版本时才提高 `MIN_SCHEMA`，并在发布说明中写明从哪个版本导出的文件不能再导入。
5. 每次修改都用旧版本导出的文件做一次导入测试，并像数据库升级测试一样保留在测试中。

## 5. 换包名（从 TicWatch 换到 TicBox）

同一台手机换包名时，导入配对身份后再补建系统关联，**不要**走“转移到新手机”流程：系统蓝牙配对按手表地址记录、两个应用共用；转移流程会删除这份配对，手表完成迁移后也会删除旧手机的配置，而新旧“手机”是同一个地址（见[转移到新手机](../spec/phone-transfer.md)）。Wear OS 3.5 的手表也不能转移。

1. 在旧应用导出，勾选“手表配对身份”。导出包含“是否自动重连”，所以要在断开之前导出。
2. 在旧应用的连接页点“断开连接”。自动重连随即暂停，之后系统唤醒旧应用也不会再连。同一身份不能由两个应用同时使用，而 Android 不能停用用户安装的应用，所以用断开代替。先不要卸载旧应用。
3. 安装新应用，导入同一个文件。应用重启后按导入的连接目标直接连接已配对的手表。
4. 手表连上后，连接页提示“系统还没有把这块手表关联到本应用”，点“建立关联”。系统对话框只列出这块手表；这一步使用 `PairingClient.associate`（`replaceExistingBond=false`，不传权限），不删除蓝牙配对。结束后服务立即让恢复逻辑记下关联号并开始在场观察。
5. 在新应用中重新授予权限：通知访问、电话、短信、位置、电池不受限制等都按包名记录。需要 Android 14+ 的权限同步时，到配对页点“向手表同步手机权限”，它复用这个关联。
6. 重新登录 Mobvoi 账号、重新确认云端授权。
7. 手表：卸载旧应用的 Local Bridge（旧包名），安装新应用内嵌的 Local Bridge（同包名、同签名才能路由私有消息），在手表上重新允许读取和 root。进程内模块的 ID 不变，在新应用的手表模块页原地升级即可。
8. 确认新应用的连接、通话控制和健康同步都正常后，再卸载旧应用。卸载会让系统删除旧应用的关联；蓝牙配对是否保留尚未在真机确认，卸载后先检查连接。

不要在新应用里点“配对新手表”：默认流程会先清除这块手表的旧蓝牙绑定。

没有关联时：手表回到身边时系统不会唤醒被杀掉的应用；后台启动前台服务没有伴侣设备豁免，蓝牙开启触发的唤醒可能被拒；没有 `MANAGE_ONGOING_CALLS`，手表不能接听或挂断电话；Android 14+ 权限同步不可用。连接与数据同步本身不受影响。

## 6. 测试与待验证

`MigrationCryptoTest`、`MigrationTablesTest` 和 `AndroidDataMigrationTest` 覆盖加密往返、错误密码与截断、表结构一致性、合并规则、身份替换和计数取大值。

尚未在真机验证：大数据量导出导入的耗时、手机上 PBKDF2 的耗时、导入后进程重启在各厂商系统上的表现、换包名后不经 CDM 直接重连手表，以及补建关联后的在场唤醒和通话控制。
