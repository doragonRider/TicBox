# 私有运动数据库

本文规定 TicFitness（`com.mobvoi.wear.fitness.at`）私有数据库 `fitness.db` 的结构和字段含义，配合[机器可读列结构](fitness-database-schema.json)、[健康数据](health.md#23-sportfitness_summary-的-10-列)和[健康数据展示参考](health-display.md)使用。普通 UID 无法读取这个数据库，本应用通过[可选的 root 采集](local-bridge.md#5-root-运动采集)只读获取。

适用版本：TicFitness 5.5.0-2536.2932（数据库 `user_version=1021`）。读取器按实际的 schema 读取已识别的字段并保留未知列，不把 1021 作为白名单。

## 1. 表结构概览

数据库有 5 张表：`RECORD`、`POINT`、`android_metadata`、`sqlite_sequence`、`room_master_table`，没有视图或触发器（本应用的[变更日志](local-bridge.md#6-变更日志可选需要-root)启用后会增加自有对象）。没有单独的跑步表、游泳表或分段表。

`RECORD` 是一场训练的汇总；`TYPE` 是 SportType 编号，完整的 152 项见[健康类型目录](health-types.json)的 `enums.sport_type`。例如 0 户外跑、1 户外走、2 室内跑、3 户外骑行、4 自由训练、9 泳池游泳、19 跳绳、24 HIIT、55 开放水域游泳、135 徒步。编号不等于枚举序数，也不能套用健康 POINT 或 SESSION 中同一编号的含义。

`POINT` 是一场训练中按运动时间合并的稀疏观测、事件和部分分段数据，**没有 TYPE 列，必须关联父 RECORD 才能选择解释方式**。它不是 TicHealth 的 `data_point`，也没有 `type/values` 结构。一行不保证同时有心率、距离、速度和 GPS。

`POINT.RECORD_ID → RECORD._id` 是整数关联；`RECORD.HASH` 是官方的业务训练 ID 文本。不能把 HASH 转成 Long，也不能用 Provider 摘要中的 `id` 直接与 `POINT.RECORD_ID` 比较。官方按 RECORD 的主键查询 POINT，数据库没有声明外键。HASH 只有非唯一索引 `IDX_RECORD_HASH`；POINT 没有其他索引，也没有 `(RECORD_ID, TIME)` 唯一约束。

官方写入时按同一训练、相同 TIME 查找并更新点，不同指标可能合并到同一行，因此一行不是一个独立的传感器事件；官方还会清理重复点（本应用的只读采集不执行这一逻辑）。

这些表保存的是官方处理后的数据，不是全部原始采样。例如平均速度为正时，超过当时均速 5 倍的速度会被限制为 5 倍；距离写入 POINT 时取整到米；VO2max 汇总会四舍五入。读到的值无法还原被限制之前的数据，本应用也不应再做同样的裁剪。

训练开始、采样和结束都会更新同一条摘要，临近的观测会更新旧的 POINT；删除既有软删除也有物理删除。POINT 没有 AUTOINCREMENT，不能用最大 ID 保证完整增量，增量策略见[本地桥接协议](local-bridge.md#4-增量同步)。

## 2. RECORD

INTEGER 字段可能是 64 位值；保留 NULL、原始整数和文本，不因官方模型使用 int 或 float 就提前截断。

| 列 | 声明 | 含义 |
|---|---|---|
| `_id` | INTEGER PRIMARY KEY AUTOINCREMENT | 训练行 ID，POINT 关联的目标 |
| `HASH` | TEXT | 不透明的训练 ID，通常是本地生成的 UUID，但不限定格式，也不保证唯一 |
| `TYPE` | INTEGER NOT NULL | SportType 编号 |
| `TARGET_TYPE` | INTEGER | 目标指标类别，见第 5.5 节；保留未知值 |
| `TARGET_VALUE` | REAL | 目标值，单位取决于 TARGET_TYPE |
| `ACCOUNT_ID` | TEXT | 账号标识，可能是匿名的；本地读取不要求登录 |
| `SYNCED` | INTEGER NOT NULL | 官方同步记账，保留原值；不是本应用的采集水位 |
| `DELETED` | INTEGER NOT NULL | 官方删除记账，读取时保留，不修改原库 |
| `START_TIME` | INTEGER | 训练开始的墙钟时间，epoch 毫秒 |
| `END_TIME` | INTEGER | 训练结束的墙钟时间，epoch 毫秒；进行中的训练可能还没有正常值 |
| `TIMESTAMP` | INTEGER | 保留列；官方的转换中没有赋值，不要据列名当作更新时间 |
| `DURATION` | INTEGER NOT NULL | 有效运动时间（毫秒），不一定等于 END_TIME − START_TIME |
| `DISTANCE` | INTEGER | 总距离（米）；显示时换算为 km 或 mi |
| `CALORIE` | REAL | 累计运动热量（千卡），保留小数 |
| `STEP` | INTEGER | 累计步数 |
| `HEART` | INTEGER | 平均心率 bpm；官方在结束时对点中的正心率取整数平均 |
| `GROUPS` | TEXT | 逗号分隔的 Long 列表，见第 5.3 节 |
| `SWIM_TRIPS` | REAL | 随运动方式复用，见第 5.1 节 |
| `SWIM_DISTANCE` | INTEGER | 随运动方式复用 |
| `SWIM_POOL_LENGTH` | INTEGER | 随运动方式复用 |
| `SWIM_STROKE` | INTEGER | 随运动方式复用 |
| `DEVICE_ID` | TEXT | 设备标识；与本地来源归属分别保留 |
| `SESSION_MODE` | INTEGER | 记录模式：−1 未知、0 用户启动、1 自动识别；保留未知值 |
| `MAX_ELEVATION` | REAL | 最大海拔（米），需要识别占位值 |
| `MIN_ELEVATION` | REAL | 最小海拔（米），负值不一定无效 |
| `CUMULATIVE_UP` | REAL | 累计爬升（米） |
| `CUMULATIVE_DOWN` | REAL | 累计下降（米） |
| `SCORE` | REAL | 随运动方式复用（评分或动作次数），见第 5.2 节 |
| `EXTRA` | TEXT | 随运动方式复用的扩展，可能有不同的 JSON 形态，见第 5.4 节；保留原文 |

官方上传到云时会对部分指标取整；这种传输损失不是本地数据库的精度要求。

## 3. POINT

| 列 | 声明 | 含义 |
|---|---|---|
| `_id` | INTEGER PRIMARY KEY | 点的行 ID，保留库内身份和顺序 |
| `TIME` | INTEGER NOT NULL | 训练有效时间轴上的毫秒，不是 epoch 时间 |
| `HEART` | INTEGER | 当时的心率 bpm，可能是占位或缺失 |
| `STEP` | INTEGER | 训练内的累计步数，不是该点新增的步数 |
| `LAT` | REAL NOT NULL | 原始纬度 |
| `LON` | REAL NOT NULL | 原始经度 |
| `ACC` | REAL NOT NULL | 定位辅助量；当前版本实际写入的是方位角（bearing），不能统一标为精度米数 |
| `DISTANCE` | INTEGER | 训练内的累计距离（米），不是逐点相加 |
| `SPEED` | REAL | 速度（m/s） |
| `CALORIE` | REAL | 训练内的累计热量（千卡） |
| `RECORD_ID` | INTEGER | 对应 RECORD._id |
| `RESUME` | INTEGER | 恢复运动的标记，按布尔解释并保留原整数 |
| `SWIM_TRIPS` | REAL | 随运动方式复用（游泳或陆地圈） |
| `SWIM_DISTANCE` | INTEGER | 保留列；不能仅凭名称当作有效的点距离 |
| `SWIM_STROKE` | INTEGER | 随运动方式复用（划水次数或陆地圈耗时） |
| `SWIM_TYPE` | INTEGER | 随运动方式复用（泳姿或陆地圈距离） |
| `TIMESTAMP` | INTEGER NOT NULL | 点的墙钟时间，epoch 毫秒，与 TIME 分别保留 |
| `ELEVATION` | REAL | 海拔（米）；有极值占位，负值不一律丢弃 |
| `STRIDE_FREQ` | INTEGER | 步频（步/分钟），来自 Health Services 的 `Step per minute`；官方在停止运动时也会写 0，不能把所有 0 都当作缺失 |

GPS：纬度和经度来自 `LocationData.getLatitude/getLongitude`，而 ACC 来自 `getBearing()`；官方把 ACC ≥ 0 当作“可用”的提示，但这不是定位精度。

时间：官方用 Health Services 的 ActiveDurationCheckpoint 把采样的 epoch 时刻换算为有效运动时长（TIME），同时保存采样的墙钟时间（TIMESTAMP），或从最近的锚点估算。暂停会让两条时间轴分离，不能总用 `START_TIME + TIME` 代替 TIMESTAMP。

官方大量使用 −1、极小或极大的 float 以及默认的 0 表示缺失或事件占位。NOT NULL 不代表指标有效；保留原行，解析时附带“已知/未知”和解释依据。不能因为经纬度恰好为 0 就删除整行，这一行仍可能包含心率、累计距离或圈事件。

## 4. 距离、速度、配速与地图

距离单位为米，速度为 m/s；换算为 `km = m × 0.001`，`mi = m × 0.621371 / 1000`。热量单位为千卡（手表和手机都直接显示，没有千倍缩放）。

- 速度 km/h：`SPEED × 3.6`
- 瞬时配速（秒/km）：`1000 / SPEED`
- 平均速度：`DISTANCE / (DURATION / 1000)`
- 平均配速（秒/km）：`DURATION / DISTANCE`（DURATION 为毫秒，DISTANCE 为米）

速度或距离不为正时无法得到有限的配速，应显示为缺失并保留原值。平均配速不是瞬时配速的算术平均。

官方的公里/英里配速分段从按 TIME 排序的累计距离点生成，以进入新整数距离区间的那个实际点计算，末尾还可能有不足一个单位的尾段；数据库中没有预存的配速表。算法：维护最大累计距离 d、已输出的单位编号 k 和上次输出时间 t；当 `floor(d / 单位距离) > k` 时，用实际的时间差除以实际跨越的单位距离得到分段配速，再更新 k 和 t。它不精确插值到 1000 米边界；一次跳过多个单位时，不能说每个单位都有独立的实测分段。官方 0.42 m/s 的显示阈值不是入库条件。

另一种是圈（lap）：圈号递增时输出圈距离、圈耗时、累计圈耗时和圈配速；圈配速（秒/km）= `圈耗时 ms / 圈距离 m`；累计圈耗时是已记录各圈耗时之和，不一定等于当前 TIME。界面可以同时提供“每公里配速”和“计圈明细”，两者不能混为一类。

官方上传时另有 `gps_type`，以及 GPS 与百度坐标之间的转换，这个类型不存在于这两张表中，仅凭 LAT/LON 不能确定应做哪种坐标转换。保留原始坐标，展示层根据底图确认的坐标系转换，不能重复套用偏移。

## 5. 随运动方式变化的字段

### 5.1 SWIM 列

游泳运动为 `Swimming=9 / AutoSwimming=11 / OpenWaterSwimming=55`。

| RECORD 列 | 游泳 | 非游泳 |
|---|---|---|
| `SWIM_TRIPS` | 累计趟数（float，保留小数） | VO2max 值；不能显示为趟数 |
| `SWIM_DISTANCE` | 当前版本未写入，保留原值 | 已记录的圈数 |
| `SWIM_POOL_LENGTH` | 池长（米）；开放水域也可能用作分段长度 | 最近一圈的距离（米） |
| `SWIM_STROKE` | 累计划水次数 | 最近一圈的耗时（毫秒） |

官方只在户外跑、户外走、户外骑行和室内跑中展示 VO2max 和恢复时间；这是展示上的选择，其他运动的原值不删除。上表也不代表每条非游泳记录都有有效的 VO2max 或圈数据。

POINT 中，普通的游泳点把累计趟数、累计划水和泳姿写入 `SWIM_TRIPS / SWIM_STROKE / SWIM_TYPE`。**陆地的圈事件**用同样三列保存 `圈号 / 本圈耗时（毫秒）/ 本圈距离（米）`，并把 `STEP` 和 `DISTANCE` 写成 −1。POINT 本身无法判断运动方式，所以不能把 `SWIM_TYPE=1000` 一律当作错误的泳姿，也不能因为 DISTANCE=−1 丢掉整行。

POINT 的 `SWIM_DISTANCE` 虽然在表中存在，当前版本并未写入。泳池距离由 `累计趟数 × 池长` 得到，写入普通的 DISTANCE。每趟的时长只存在于运行时事件中，没有单独的列；历史详情可以用池长和该趟速度推算时长，但不能说是直接读到的。

泳姿编码：1 自由泳、2 蛙泳、3 仰泳、4 蝶泳；0 和其他值在官方界面显示为“其他泳姿”。保留原码，不把未知值改为 0。

### 5.2 SCORE 与动作次数

`Burpee=25 / SquatJumps=28 / JumpingJack=30 / RopeSkipping=19 / AbdominalCurl=63` 中，SCORE 保存的是动作次数，官方读回为整数，不能显示为评分。`OutdoorRunning` 有 AI 评分的展示路径，但其算法不在存储中。其他运动保留原 SCORE，含义未知；不能仅凭名称或同一分组就扩大计次的范围，例如 Crunch 不在上述计次运动中。

### 5.3 GROUPS：训练与组间休息的边界

GROUPS 是 Long 列表的逗号连接，空列表可以是 NULL。它记录累计有效训练时间（毫秒）上训练与休息的切换边界，**不是 UNIX 时间，也不是暂停区间或泳姿列表**。初始为训练，按边界交替训练和休息；边界数为奇数表示当前处于组间休息，当前组号为 `floor(边界数 / 2) + 1`。组间休息仍在训练会话中，与暂停冻结训练时钟不同。

只有运行中才记录边界；重复相同状态的操作会更新最后一个边界，不一定新增一组。手机从 0 开始依次切分区间，最后一段延续到摘要的 DURATION。这条组间时间轴无法还原暂停的墙钟位置。

使用 GROUPS 的运动主要有：FreeWorkout、Crossfit，以及 Football、AmericanFootball、AustralianFootball、Rugby、Surfing、Tennis、Basketball。

### 5.4 EXTRA

已确认的两种形态（示例为人工构造）：

```json
{"type":"OutdoorRunning","recoveryTime":7200000}
```

恢复时间对象：type 是 SportType 的名称文本，recoveryTime 为毫秒；手机显示为小时和分钟，并可用 `END_TIME + recoveryTime` 计算预计恢复的时间。手表只在恢复时间至少 1800000 毫秒时才保存该对象；这是写入条件，读取时不能据此拒绝更小的值。

```json
[{"type":25,"start_time":0,"target_duration":30000,"duration":29000,"times":12}]
```

HIIT 阶段数组：type 是 SportType 的整数编号；start_time 是训练有效时间轴上的毫秒（按整秒量化，但不是秒，也不是 epoch）；target_duration 和 duration 为毫秒；times 为动作次数。新阶段尚未结算时 duration 可能为 −1；最后一段结束时以总 DURATION − start_time 结算。`Rest=35 / GroupRest=36` 也是有效的阶段，采集时不能删除。

官方手机按动作类型合并目标时长、实际时长和次数，动作汇总中排除 Rest 和 GroupRest，以 GroupRest 次数 + 1 作为组数；原始的阶段列表仍应提供给界面。POINT 的 19 列中没有 HIIT 的类型、阶段、开始时间或动作次数，不能要求从 POINT 重建本来保存在 EXTRA 中的阶段。

未知的 JSON 形态、额外的键和非 JSON 字符串原样保留；单个扩展字段解析失败不影响整条训练。

### 5.5 目标类型

TARGET_TYPE 使用独立的编码：1 时长、2 距离、3 热量、4 步数、6 速度、7 配速、8 GPS 状态、9 位置、10 组数、11 趟数、12 游泳距离；0 未知。写入时另有 5 心率、13 泳池配速，但官方读回时不映射这两项。不能把枚举序数存进这一列，也不应复制官方读回时的语义丢失；保留原码，并标记读写不对称。目标值使用相应的原始量，无法确定的类型保留未知单位。

## 6. 本地采集与展示

本应用按实际 schema 读取已识别的字段并保留未知列，不修改原库，也不修改 SYNCED/DELETED，不执行官方的升级、清理或删点逻辑。手机完整保存 RECORD 及其 POINT 的原始行和来源，按当前父记录的 TYPE 投影已确认的含义；父记录晚到时重新关联。未知运动也返回基本概要和已有的样本，不要求命中已知枚举才能入库。

每公里配速、步幅、心率区间和 SWOLF 等带有算法选择的派生指标由展示层计算，不覆盖原始值；日期、时区、单位偏好和图表抽样也由界面决定。官方的展示方式见[健康数据展示参考](health-display.md)，手机端的解析与查询 API 见[健康数据开发说明](../dev/health.md)。

## 7. 待验证

以下需要真实训练数据确认：

- 跑步和步行：实际 GPS、采样间隔、速度与距离的一致性、暂停后两条时间轴的关系、圈事件。
- 游泳：趟数、泳姿、划水、池长和开放水域的具体数据。
- HIIT 和计次运动：EXTRA 的形态、组间休息、SCORE 的含义。
- 多场训练、重复的 HASH、进行中的记录、删除记账，以及新固件的列扩展。
