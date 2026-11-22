# 健康数据

本文规定手表上官方健康应用的数据来源、原始行格式、字段解释和睡眠关联。手机与手表之间如何传输这些数据见[本地桥接协议](local-bridge.md)；完整私有运动库见[私有运动数据库](fitness-database.md)；官方手机 App 如何统计和展示见[健康数据展示参考](health-display.md)。

配套的[机器可读类型目录](health-types.json)包含全部 41 个有编号的 POINT 类型、每个已确认的槽位、152 个 SportType 枚举项及其他码表。它是规范的一部分，不是另一套运行时 DTO。

手机最低 API 34，手表组件最低 API 30。

## 1. 数据路径

```text
TicHealth / TicFitness ContentProvider，或可选的 root 有界只读查询
    → 手表 Local Bridge（后台）
    → 专用 secure RFCOMM
    → 手机后端解析并原子入库
    → healthRecords 本地查询
    → 界面展示和派生统计
```

健康数据不经过 HTTP、Flow15 或 GMS 的健康存储服务。GMS 只负责向手表组件投递一条本地唤醒消息，数据本身走专用的蓝牙 socket。手表组件不申请 INTERNET 权限。手机上的云缓存是另一个需要明确开启的来源，默认不登录、不上传、不回填，查询健康页面也不会联网。

普通 UID 可以读取 TicHealth 的 POINT 和 SESSION。SPORT 摘要 Provider 受 signature 权限保护，普通 UID 被拒绝（`provider_denied`），这不是“没有运动记录”。完整的运动明细可以通过可选的 root 采集读取，见[本地桥接协议](local-bridge.md#5-root-运动采集)。

## 2. Provider 表与原始行

POINT、SESSION、SPORT 三个来源使用 `ContentResolver.query()`；私有运动表 RECORD/POINT 由 root 有界查询读取。Local Bridge 不调用 Provider 的 insert、update、delete 或 call，也不修改官方的 `synced` 标记。但 Provider 启动或查询时内部仍可能写库，不能把“只调用 query”说成“官方数据库绝不会发生写入”。

### 2.1 POINT：9 列

入口：`content://com.mobvoi.heartrate.db.provider/data_point`。TicHealth 的 HeartRateContentProvider 已导出且启用，没有声明 readPermission。

| 列 | 存储类型 | 含义 |
|---|---|---|
| `_id` | integer | 数据库行 ID；手机上 POINT 的 key 是它的十进制文本 |
| `values` | string | 逗号分隔的数值，见第 4 节 |
| `device_id` | string | 官方的设备 ID，不能代替本地档案或蓝牙来源 |
| `wwid` | string | 官方账号元数据，可以为空；本地读取不要求登录 |
| `type` | integer | POINT 类型编号 |
| `time_to` | integer | Unix epoch 毫秒，结束或测量时间 |
| `time_from` | integer | Unix epoch 毫秒，开始或测量时间；瞬时值可以与 time_to 相同 |
| `synced` | integer | 官方的云同步记账，0=false、1=true，其他值保留为未知 |
| `deleted` | integer | 0=false、1=true，其他值保留；默认查询隐藏所有非零值 |

分页：首次取 `_id DESC LIMIT 1` 得到上界；之后固定使用 `_id<=?` 或 `_id>? AND _id<=?`，参数绑定，`_id ASC LIMIT limit+1`。不附加 wwid 过滤。官方应用会修改已有行（例如当天心情、睡眠结束和合并），所以最大 ID 不是只增不改的水位，增量策略见[本地桥接协议](local-bridge.md#4-增量同步)。

### 2.2 SESSION：8 列

逻辑来源名为 `content://com.mobvoi.heartrate.db.provider/data_session`，但读取时**实际查询的是 data_point 的 URI**（见下文的 rowid 载体），不能把逻辑来源名当作可直接查询的 URI。

| 原始列 | 存储类型 | 含义 |
|---|---|---|
| `id` | string | 不透明的逻辑会话 ID；手机 SESSION 的 key 使用它，不要求是 UUID |
| `device_id` | string | 关联测量点时要求完全一致 |
| `wwid` | string | 官方账号记账，空字符串合法 |
| `type` | integer | 独立的 ActivityType 码表；AutoSleep=100 |
| `time_to` | integer | epoch 毫秒；0 表示尚未结束 |
| `time_from` | integer | epoch 毫秒 |
| `synced` | integer | 官方以 bit0 判断已同步，其他位保留 |
| `deleted` | integer | 删除标志，保留原值 |

查询方法：先用 data_point 的 `selection="0"` 取得列顺序，必须找到 `_id` 和 `values` 两个载体列。通过同一载体查询固定的 `pragma_table_info`，得到 data_session 的实际列名；再使用 `0 UNION ALL SELECT <本地构造的表达式> FROM data_session`，按 POINT 的列顺序，`_id → rowid`、`values → id`，其余七个同名元数据列对应原列，未知的载体列填 NULL。首次 `_id DESC LIMIT 1` 得到 session 的 rowid 上界；分页条件为 `rowid>? AND rowid<=?`，排序仍为 `_id ASC LIMIT limit+1`。所有表达式都由本地读取器固定生成，不向蓝牙对端开放 SQL。

该 Cursor 的 `_id` 是合成的 rowid，只用于外层的 id 和分页；`values` 还原为 `columns.id`，合成的 `_id` 和未知的 NULL 占位不作为原始 SESSION 列发送。有列但未读到值时标记 partial 并列入 unavailableColumns；schema 中确实不存在的列保持缺失。新增的 SESSION 列通过逐列的有界投影合并到原始行，标识符用双引号转义。schema 探测失败时只保留已知字段，并标记 partial。**外层分页用的 rowid 和 `columns.id` 是两种身份**：睡眠以 UPDATE 补写同一个 UUID，rowid 不变；物理删除后 rowid 还可能被重用，不能作为跨轮次的永久变更序号。

ActivityType：`-1 Unknown, 0 IndoorRunning, 1 OutdoorWalk, 2 OutdoorRunning, 3 OutdoorBike, 4 FreeWorkout, 10 AutoRunning, 11 AutoWalking, 12 Swimming, 13 AutoCycling, 14 AutoSwimming, 20 IndoorCycling, 21 Basketball, 22 Football, 23 Gymnastics, 24 RopeSkipping, 25 Spinning, 26 BodyMechanics, 27 Climbing, 28 EllipticalMachine, 29 RowingMachine, 30 OffRoad, 31 Yoga, 100 AutoSleep`。不能套用 POINT 或 SportType 中同一编号的含义。

### 2.3 SPORT：fitness_summary 的 10 列

入口：`content://com.mobvoi.fitness.db.provider/fitness_summary`。TicFitness 声明了 signature 级的 `com.mobvoi.ticwear.permission.FITNESS_DATA`，普通应用即使在 manifest 中声明使用、或请求运行时权限，也得不到厂商签名权限。被拒绝时不得报告“运动同步成功 0 条”。

Provider 查询 RECORD 后生成固定的 MatrixCursor，projection 不能扩展返回的列：

| 返回列 | 来源 | 类型与含义 |
|---|---|---|
| `id` | RECORD.HASH | string，不透明的训练 ID，不是私有表的 INTEGER `_id` |
| `wwid` | Provider 当前账号 | string，不是逐行的 ACCOUNT_ID |
| `device_id` | RECORD.DEVICE_ID | string |
| `time_from` | RECORD.START_TIME | integer，epoch 毫秒 |
| `time_to` | RECORD.END_TIME | integer，epoch 毫秒 |
| `type` | RECORD.TYPE | integer，SportType 编号 |
| `synced` | RECORD.SYNCED | integer，官方记账 |
| `during` | RECORD.DURATION | integer，有效训练时长（毫秒）；保留官方拼写 |
| `calorie` | RECORD.CALORIE | float，千卡，保留原始小数 |
| `heart` | RECORD.HEART | integer，平均心率 bpm；0 可能是占位 |

Provider 固定排除 `DELETED!=0`，摘要中没有 deleted 列，也没有删除墓碑；HASH 为空、`START_TIME<=0`、`END_TIME<START_TIME`、`TYPE=-1` 的记录也被过滤。读取器把同样的有效记录条件放在 LIMIT 之前，避免 Provider 在 LIMIT 之后过滤导致误判为结束。该 Provider 不支持 selectionArgs，并且会替换 selection 中所有的 `time_from`/`time_to`、检查用反引号括起的 wwid；因此合法的 HASH 必须编码为 `CAST(X'<UTF-8 十六进制>' AS TEXT)`，只转义单引号是不够的。

分页规则：

1. 首次在可见记录中按 `HASH COLLATE BINARY DESC, _id DESC LIMIT 1` 取得上界 HASH。
2. 每页按 `HASH COLLATE BINARY ASC, _id ASC` 排序，上界为 `HASH<=上界`。这是 SQLite 文本排序，不是时间或插入顺序。
3. 之后的页保留 `HASH>afterKey`，或 `HASH=afterKey` 且在同一 HASH 的子查询中按 `_id ASC` 跳过已读条数的行。OFFSET **只作用于这个子查询**；上一组消失时不能跳过下一组。
4. 外层 `id` 和 `columns.id` 都是 HASH；`sourceOccurrence` 是该 HASH 下从 0 开始的重复序号。`nextKey` 为最后一个 HASH，`nextId` 为该组已读条数；SPORT 没有数值上界。
5. HASH 没有 UNIQUE 约束，手机上的 key 是 `[HASH, sourceOccurrence]` 的 JSON 文本，例如 `["synthetic-training",0]`。调用方原样传回 recordKey，不自行拆分或转换。

HASH 不要求是 UUID，官方会接受其他来源的不透明 ID。同一 HASH 的记录在两页之间增删时，occurrence 可能漂移；跨查询没有数据库快照。每轮重新比较可见摘要的内容指纹，只传变化或未确认的行，不保存永久的最大 HASH 水位。

摘要不含距离、步数、GPS、配速分段、圈数和轨迹点。这些字段存在于私有 RECORD/POINT 中，但 Provider 不返回。Provider 的 `data_point` 分支只从训练汇总派生正的步数、热量、距离和运动时长增量，**不是 GPS 轨迹的来源**。完整字段见[私有运动数据库](fitness-database.md)。

### 2.4 原始行的封装

所有表都按 Cursor 实际返回的内容保留 `columns` 和 `storageTypes`。类型标签为 `null / integer / float / string / blob-base64 / unknown:<code>`；整数按 64 位保存，BLOB 为无换行的 Base64，非有限的 float 以字符串保存、类型仍标 float。需要精度的使用方不能先把所有 JSON 数字转成双精度浮点数。

读取单个字段失败时，保留其他字段，把列名放入 `unavailableColumns` 并设置 `partial=true`，不补 0。一行的 JSON 超过 960 KiB、一帧放不下时，从最大的单元格开始去掉，直到放得下，列名放入 `oversizedColumns`；`_id`、`id` 和 `RECORD_ID` 不会去掉。这种行不设 `partial`，因为下次读到的还是一样，重读没有用；手机把它记为部分源行。整行超过 CursorWindow（约 2 MB）、连读都读不出来时，先用 `length()` 量出各单元格的大小，再不带最大的那些重读，结果相同。缺少可用的逻辑 ID 时，缓存用 cursor 前缀或原始行内容的 SHA-256 作为后备 key，以免丢行；后备 key 不冒充官方 ID。

## 3. 官方健康消息

以下是伴侣路由上的普通 Message，与本应用的蓝牙桥无关，详见[伴侣业务消息](companion.md#2-设备信息资料与健康提示)。

| 路径 | 方向与行为 |
|---|---|
| `/health/cmd_new_wear_push_to_phone` | W→P，JSON 只有 `current_step:int, goal:int`；没有 messageId、历史数组或分块序号 |
| 同一路径的回复 | 官方手机端回复 `{"messageId":<long>,"status":0}`，然后更新当前步数；手表发送的对象没有 messageId，所以回复中为 0。手表不依赖这一回复，没有去重或事务确认 |
| `/health/cmd_wear_push_to_phone` | P→W，空内容；官方健康和运动应用会安排**云同步**，健康应用另外发送当前步数。不是本地历史读取请求 |
| `/health/cmd_wear_pull_from_phone` | P→W，JSON `goal:int`（可带 current_step）；手表只在 goal>0 时写入步数目标，随后可能进入强制云同步 |
| `/health/sync_step_to_wechat` | W→P，十进制整数文本，交给微信步数桥接；不含历史 |
| `/health/sleep/share` | W→P，sleepId 文本，手机打开分享页；不返回会话数据 |
| `/health/open_wechat_qr_ticket` | P→W，qr_ticket 文本，手表打开相应页面 |
| `/health/cmd_change_heart_health_switch` | P→W，文本恰为 `1` 时显示入口，其他值隐藏并关闭监测；显示不会自动开启监测，见[监测设置](monitoring-settings.md) |

手表在连接的节点非空时也会主动发送当前步数。`/health/data_set/...`、`/health/session/...` 的六个常量没有对应的发送和接收；本机 HDS 的 Parcelable/Binder 布局也不是线上格式。完整的本地读取依靠[本地桥接协议](local-bridge.md)。

由于上述云同步的副作用，本应用的 `WatchClient.refreshHealth()` 只有在用户开启 Mobvoi 云（MOBVOI_CLOUD）后才会发送；`setStepGoal()` 有同样的副作用，不受该开关限制，但界面会在发送前提醒并请用户确认。默认的本地采集调用 `localHealth.sync()`。被动收到的当前步数可以作为最近的观察值，其接收时间不是历史采样时间，不能与 POINT 历史相加。

## 4. POINT 的解析与 41 个类型

`values` 按逗号分隔，没有带引号或转义逗号的约定。rawValues 和 rawToken 原样保留，包括空槽、空白和 BOM；只在做数值解释时才 trim 并去掉一个前导 BOM。数值用任意精度的十进制字符串表示；整数元数据允许等值的十进制或科学计数法，但必须精确落在有符号 64 位范围内，不截断小数。

每个槽的 `status` 为 `NUMBER / EMPTY / NON_FINITE / TEXT`；空值、NaN、Inf 和其他文字都不补 0。已确认的文本槽（VO2max 的 1 和 3）即使全是数字，也保持 TEXT，decimal 为 null。每个槽的 semantic 和 unit 只是对原值的注释，不做换算；末尾的未知槽保留，注释为空。unit 为 null 也可能是没有物理单位的有效值。

下表的槽位从 0 开始；`N` 表示数值，`T` 表示固定文本。**39 个类型有已确认的字段，不等于这 39 个类型的每个槽都已完整解释**。只有 2、30、36、37、41 观察到的向量长度分别为 2、2、2、2、4，长度不符时只添加 issue，不拒绝；其他类型只约定首槽，不限制尾部长度。

| 编号 / 官方名称 | 已确认的槽：semantic，unit，类型 |
|---|---|
| 1 HeartRate | 0 heart_rate，bpm，N |
| 2 Motion | 0 interval_state，motion_code，N；1 next_state，motion_code，N |
| 3 Location | 全部未确认，不借用 SPORT 的经纬度 |
| 4 Calorie | 0 calorie，kcal，N；旧的累计快照 |
| 5 Distance | 0 distance，m，N；旧的累计快照 |
| 6 Step | 0 step_count，steps，N |
| 7 Exercise | 0 exercise，s，N |
| 8 DeltaCalorie | 0 delta_calorie，kcal，N |
| 9 DeltaDistance | 0 delta_distance，m，N |
| 10 DeltaStep | 0 delta_step，steps，N |
| 11 DeltaExercise | 0 delta_exercise，s，N |
| 12 PeriodEvent | 0 period_event，period_event_code，N |
| 13 PeriodCycleLength | 0 period_cycle_length，days，N |
| 14 PeriodMenstruationLength | 0 period_menstruation_length，days，N |
| 15 HeartRateWarning | 0 heart_rate_warning，heart_rate_warning_code，N |
| 16 HeartRateWarningFitnessUpperLimit | 0 heart_rate_warning_fitness_upper_limit，bpm，N |
| 17 HeartRateWarningRestUpperLimit | 0 heart_rate_warning_rest_upper_limit，bpm，N |
| 18 HeartRateWarningRestLowerLimit | 0 heart_rate_warning_rest_lower_limit，bpm，N |
| 19 GoalTodayStep | 0 goal_today_step，steps，N |
| 20 GoalTodayActive | 0 goal_today_active，h，N |
| 21 GoalTodayExercise | 0 goal_today_exercise，s，N |
| 22 TodayMood | 0 today_mood，mood_code，N |
| 23 InfoGender | 0 info_gender，gender_code，N；见第 4.1 节 |
| 24 InfoBirthday | 0 info_birthday，epoch_s，N |
| 25 InfoHeight | 0 info_height，cm，N |
| 26 InfoWeight | 0 info_weight，kg，N |
| 27 NOISE_DECIBEL | 每个槽 noise_decibel，dB，N；每个槽的时间和加权方式未确认 |
| 28 BloodOxygen | 0 blood_oxygen，%，N；不乘除 100 |
| 29 Pressure | 0 pressure，单位未确认，N；压力指数，不是血压 |
| 30 HighStrengthTraining | 0 sport_type，sport_type_code，N；1 high_intensity_duration，ms，N；高强度心率区间的累计有效时长 |
| 31 AISportGrade | 全部未确认，不猜测分数或百分比 |
| 32 Temperature | 0 temperature，°C，N；与 42 是不同的使用链 |
| 33 ExpectedSleepTime | 0 expected_sleep_time，ms_since_midnight，N |
| 34 ExpectedGetUpTime | 0 expected_get_up_time，ms_since_midnight，N |
| 35 ArtyData | 0 未确认；1 arty_score，score/100，N；2 tru_hr，bpm，N；3 ecap，%，N；4 hsx，%，N；5 arty_age_lower，years，N；6 arty_age_upper，years，N |
| 36 HeartHealth | 0 heart_health_type，heart_health_code，N；1 heart_rate，bpm，N |
| 37 BodyAndMindState | 0 body_mind_type，body_mind_code，N；1 level，ordinal，N |
| 38 RespirationRate | 0 respiration_rate，breaths/min，N；写入前已换算，不要再除以 4 |
| 39 ClimbingStairs | 0 climbing_stairs，m，N；上升高度，不是楼层数 |
| 41 Vo2Max | 0 vo2max，ml/kg/min，N；1 sport_type，无单位，T；2 duration，ms，N；3 sport_id，无单位，T |
| 42 SkinTemperature | 0 skin_temperature，°C，N；没有私有基线 |

没有编号 40。官方的 Unknown/Speed 默认编号为 -1，不能按枚举顺序推出 Speed=40。POINT、SESSION、SPORT 的编号相互独立，例如 SESSION 0 与 SPORT 0 的室内/室外跑步含义不同。完整的 152 项 SportType 在[类型目录](health-types.json)的 `enums.sport_type` 中，包括 -2 More、-1 Unknown、0..37 和 50..161；VO2max 中的文本 SportType 按**名称**解释（旧别名 hiit → Hiit），不当作数字或序数。

### 4.1 枚举、冲突与不做的推断

- **Motion**：`-1 Unknown, 0 Static, 1 Walk, 2 FastWalk, 3 Run, 4 FastRun, 5 Bike, 6 OffWrist, 7 Awake, 8 REM, 9 LightSleep, 10 DeepSleep, 11 Swimming, 99 Undefined, 100 FreeTrain`。两个槽分别是区间内的状态和边界之后的状态，第二个槽不是第二个采样。
- **PeriodEvent**：`-1 Unknown, 0 Start, 1 End`。13、14 为天数；官方在缺失时回退到 28 天和 5 天，这些回退值不是采集到的记录。
- **HeartRateWarning 15**：16 为运动上限、17 为静息上限、18 为静息下限；16、17、18 各自记录的首值才是 bpm 阈值。较早的版本写过 11 个槽的告警，当前只确认了首槽，不为额外的十个槽猜测单位。
- **Mood 22**：`-1 Unknown, 0 Excited, 1 Smile, 2 Normal, 3 Sad, 4 Angry`。
- **Gender 23**：官方手机包 `com.mobvoi.companion.at` 使用 `0 UNKNOWN, 1 MALE, 2 FEMALE, 3 NON_BINARY`（按包名选择映射，不按手表或 Android 版本）。同一手机应用中另一个包的旧 POINT 为 `0 FEMALE, 1 MALE`，账号的 Sex 又为 `0 MALE, 1 FEMALE`，之间有显式转换。保留编码和原始值。
- **Birthday 24** 是 Unix 秒，官方写入前先转成 float，可能已经损失精度，不据此修正原值。**ExpectedSleep/GetUp 33/34** 是本地时间距零点的毫秒数；官方的 23:00 / 08:00 默认值不是原始值。
- **Arty 35** 的槽 0 未确认。1 至 6 的位置由官方手机端的使用方式确认；年龄中点 `lower + (upper - lower) / 2` 和跨度 `upper - lower` 是界面派生值。不能把槽 0 称为版本或状态。
- **HeartHealth 36**：历史详情显示 `1 房颤 / 2 心动过速 / 3 心动过缓 / 4 早搏`，一键测量显示 `0 正常 / 1 早搏 / 2、3 心律不齐 / 4 房颤`。写入方把算法结果 1/2/3/4 分别存为 4/1/2/3，第二个槽可能写 0 占位；两条界面之间没有确认的转换可以消除矛盾。因此不输出统一的医学诊断，也不把 0 bpm 的占位当作实测心率。一键测量会先把 0 交给界面，再跳过这条路线的入库；本应用保留收到的 0。
- **Noise 27** 的所有槽都按 dB 使用，但官方手机端只按行的 time_from 分桶，不为每个槽插值时间。
- **BodyMind 37**：类别 1 为疲劳，level 1 清醒到 5 疲惫；类别 2 为能量，level 1 耗尽到 5 充沛。1 至 5 只是序数，不能把图形进度乘以 10 当作百分比。
- **BloodOxygen 28** 保留百分数；**Pressure 29** 是没有确认物理单位的压力指数；**Temperature 32** 与 **SkinTemperature 42** 都是摄氏度，但基线不能混用。没有统一的 0、-1 或 255 缺失哨兵，不能只凭数值删除行。

`HealthParseIssue` 的全部取值：`MISSING_FIELD, INVALID_INTEGER, NONSTANDARD_FLAG, UNKNOWN_TYPE, VALUE_NOT_NUMERIC, NON_FINITE_VALUE, UNCONFIRMED_VALUE_LAYOUT, UNEXPECTED_VALUE_COUNT, UNKNOWN_MOTION_STATE, INVALID_TIME_RANGE, PARTIAL_SOURCE_ROW, MALFORMED_ROW, OPEN_SESSION`。issue 是解释用的元数据，不代表整页失败或拒绝入库。诊断只记录类型、数量、处理决定和异常类，不记录原始健康数据、身份或 SQL 参数。

## 5. 睡眠

`records(SESSION)` 返回所有 ActivityType；`sleepRecords()` 只选择 AutoSleep（100）。睡眠记录包含原始会话、逻辑 sessionId、deviceId / vendorUserId、activityTypeCode、起止时间、官方标志、issues、relatedPoints 和独立的 relatedPointsStatus。end=0 时标记 OPEN_SESSION，不自动补结束时间，也不生成睡眠评分。

关联只在相同的 localUserId、peer，且**非 null 的 device_id 完全相同**时进行。空字符串是实际读到的值；缺失或 null 时不猜测关联。只关联类型 1 心率、2 Motion、28 血氧、38 呼吸率、42 皮温，时间使用 point.time_to：

| 点的类型 | 已结束的会话 | 未结束的会话 |
|---|---|---|
| Motion 2 | session.start < time_to <= session.end | time_to > start |
| 1 / 28 / 38 / 42 | session.start <= time_to < session.end | time_to >= start |

会话查询的 limit 只对会话分页，每个会话的关联点完整读取，不受普通查询 500 条的上限截断。POINT 来源不可用时 relatedPointsStatus 为 SOURCE_UNAVAILABLE，不能画成“读取成功但没有分期”；有来源但没有匹配点时为 AVAILABLE 加空数组。重叠、离腕、异常状态、尾部和缺口都保留。会话和测量点都没有时区字段，界面应明确展示所用的时区。

## 6. 未确认

- Location（3）和 AISportGrade（31）的全部槽；Arty（35）的槽 0；告警（15）的扩展槽；噪声（27）每个样本的时间。
- 官方的原生算法和皮温基线。
- HeartHealth（36）两套界面编码之间的冲突。

这些字段都没有被判定为废弃。可能的解释和推测见[健康数据展示参考](health-display.md#7-未确认字段的推测)。

手动的健康 JSON 导出是手表上的调试功能，见[本地桥接协议](local-bridge.md#7-手动-json-导出)。仓库只保存合成样例和规范，不保存用户的导出数据。
