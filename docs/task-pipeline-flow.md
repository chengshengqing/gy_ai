# 定时任务流转说明

本文档只描述当前已落地的任务编排链路，供代码审查时逐步对照。

当前链路分为两条并行主线：

- 预警主链路：`collect -> infection_event_task(EVENT_EXTRACT) -> infection_event_task(CASE_RECOMPUTE)`
- 展示增强链路：`collect -> patient_raw_data_change_task -> struct`

## 1. 涉及的定时任务

当前启用并已接入主链路的定时任务：

- `InfectionMonitorScheduler.enqueuePendingPatients()`
- `InfectionMonitorScheduler.processPendingCollectTasks()`
- `SummaryWarningScheduler.processPendingEventTasks()`
- `SummaryWarningScheduler.processPendingCaseTasks()`
- `StructDataFormatScheduler.formatPendingStructData()`

说明：

- `executeClassify()` 仍是独立链路，不在本文档范围内。
- `processPendingCaseTasks()` 当前已落地任务表、调度框架和最小执行链。
- 第二层基础骨架已新增：
  - `infection_case_snapshot`
  - `InfectionEvidencePacketBuilder`
  - `InfectionJudgeService`
- 当前 `CASE_RECOMPUTE` 已接入：
  - 事件池版本检查
  - 患者级防抖延后
  - `InfectionEvidencePacket` 构造
  - `InfectionJudgeService`
  - `infection_case_snapshot` 回写
  - `infection_alert_result` 最小结果落库
- 当前法官节点已切换为 LLM 调用，并保留确定性 fallback。
- 但结果分层、diff 计算、nosocomial 细化裁决仍未完成。

## 2. 任务表

### 2.1 `patient_raw_data_collect_task`

作用：

- 承载患者级原始采集任务
- 记录本轮批次时间和上一批次时间

关键字段：

- `reqno`
- `previous_source_last_time`
- `source_last_time`
- `change_types`
- `status`

### 2.2 `patient_raw_data_change_task`

作用：

- 承载结构化链路任务
- 一条任务对应一条 `patient_raw_data` 的具体版本

关键字段：

- `patient_raw_data_id`
- `reqno`
- `data_date`
- `raw_data_last_time`
- `source_batch_time`
- `status`

### 2.3 `infection_event_task`

作用：

- 承载预警主链路任务

当前 `task_type`：

- `EVENT_EXTRACT`
- `CASE_RECOMPUTE`

关键字段：

- `task_type`
- `reqno`
- `patient_raw_data_id`
- `data_date`
- `raw_data_last_time`
- `source_batch_time`
- `changed_types`
- `trigger_reason_codes`
- `merge_key`
- `status`

## 3. 主流程

### 3.1 批次扫描入队

入口：

- `InfectionMonitorScheduler.enqueuePendingPatients()`

流程：

1. 读取上游 `source_batch_time`
2. 读取已入队的最近批次时间 `latestEnqueuedBatchTime`
3. 若 `source_batch_time <= latestEnqueuedBatchTime`，则直接跳过
4. 若批次推进，则扫描活跃患者
5. 为每个患者写入或重置 `patient_raw_data_collect_task`

这一步的目标不是立刻采集，而是把本批次的患者采集任务完整入队。

### 3.2 原始采集与按天落库

入口：

- `InfectionMonitorScheduler.processPendingCollectTasks()`

流程：

1. claim `patient_raw_data_collect_task`
2. 对每个 `reqno` 调用：
   - `collectAndSaveRawDataResult(reqno, previousSourceLastTime, sourceBatchTime)`
3. 识别本轮 `changedTypes`
4. 按天聚合业务源数据
5. 写入或更新 `patient_raw_data`
6. 为每条变更快照同时路由下游任务

### 3.3 路由下游任务

采集成功后，每条有效的 `patient_raw_data` 新版本都会做两件事：

1. 总是写入 `patient_raw_data_change_task`
2. 若命中事件来源规则，则写入 `infection_event_task(EVENT_EXTRACT)`

当前事件来源规则：

- `ILLNESS_COURSE`
- `LAB_TEST`
- `MICROBE`
- `VIDEO_RESULT`
- `USE_MEDICINE`
- `OPERATION`
- `TRANSFER`
- `BODY_SURFACE`

当前 `trigger_reason_codes` 映射：

- `ILLNESS_COURSE -> ILLNESS_COURSE_CHANGED`
- `LAB_TEST -> LAB_RESULT_CHANGED`
- `MICROBE -> MICROBE_CHANGED`
- `VIDEO_RESULT -> IMAGING_CHANGED`
- `USE_MEDICINE / DOCTOR_ADVICE -> ANTIBIOTIC_OR_ORDER_CHANGED`
- `OPERATION -> OPERATION_CHANGED`
- `TRANSFER -> TRANSFER_CHANGED`
- `BODY_SURFACE -> VITAL_SIGN_CHANGED`

重要规则：

- 非结构化文本不再额外用 LLM 做候选判断
- 只要本轮存在新增的 `ILLNESS_COURSE`，就直接进入事件抽取任务

### 3.4 事件抽取

入口：

- `SummaryWarningScheduler.processPendingEventTasks()`

流程：

1. claim `infection_event_task` 中 `task_type=EVENT_EXTRACT`
2. 读取 `patient_raw_data_id`
3. 校验任务版本：
   - 如果任务里的 `raw_data_last_time != patient_raw_data.last_time`
   - 说明该任务已过期
   - 标记 `SKIPPED`
4. 生成最近 7 天窗口上下文
5. 构建证据块
6. 调用事件抽取器
7. 事件写入 `infection_event_pool`
8. 若本轮有新增事件，则写入 `infection_event_task(CASE_RECOMPUTE)`
9. 当前事件任务标记 `SUCCESS`

### 3.5 病例重算

入口：

- `SummaryWarningScheduler.processPendingCaseTasks()`

流程：

1. claim `infection_event_task` 中 `task_type=CASE_RECOMPUTE`
2. 当前版本已具备最小病例级重算执行链
3. 下一阶段将在此处接入：
   - 真正的 LLM 法官 prompt
   - 更细的 trigger/debounce 策略
   - 结果 diff 计算
   - 更完整的 nosocomial 裁决

目标链路：

1. 读取 `infection_case_snapshot`
2. 比较 `last_event_pool_version`
3. 若仍在防抖窗口内，则延后
4. 构造 `InfectionEvidencePacket`
   - 包含确定性 `precomputed`
   - 当前已预计算：
     - `newOnsetFlag`
     - `after48hFlag`
     - `procedureRelatedFlag`
     - `deviceRelatedFlag`
5. 调用法官节点（当前为单节点 LLM 裁决，失败时 fallback）
   - 法官只负责：
     - `infectionPolarity`
     - `decisionStatus`
     - `warningLevel`
     - `primarySite`
     - `nosocomialLikelihood`
6. 更新 `infection_case_snapshot`
7. 写 `infection_alert_result`
8. 标记 `CASE_RECOMPUTE` 成功

### 3.6 结构化

入口：

- `StructDataFormatScheduler.formatPendingStructData()`

流程：

1. 调度前巡检最近窗口的 `patient_raw_data`
2. 补建遗漏的 `patient_raw_data_change_task`
3. claim `patient_raw_data_change_task`
4. 校验任务版本，只保留当前最新版本
5. 调用 `SummaryAgent.extractDailyIllness()`
6. 回写：
   - `struct_data_json`
   - `event_json`
7. 任务标记 `SUCCESS`

说明：

- 结构化链与事件链已经并行
- 结构化不再作为事件抽取的前置阻塞条件

## 4. 示例

### 4.1 示例背景

假设上游在 `2026-04-03 03:00:00` 完成一批数据入库。  
患者 `REQ-9001` 本轮发生了两类变化：

- 新增一条 `ILLNESS_COURSE`
- 新增一条 `LAB_TEST`

### 4.2 步骤 1：扫描入队

`enqueuePendingPatients()` 读取：

- `source_batch_time = 2026-04-03 03:00:00`
- `latestEnqueuedBatchTime = 2026-04-03 00:00:00`

因为批次推进，所以把 `REQ-9001` 写入 `patient_raw_data_collect_task`：

```text
reqno=REQ-9001
previous_source_last_time=2026-04-03 00:00:00
source_last_time=2026-04-03 03:00:00
status=PENDING
```

### 4.3 步骤 2：采集成功并双写任务

`processPendingCollectTasks()` 消费这条采集任务后：

1. 更新一条 `patient_raw_data`
2. 识别 `changedTypes=ILLNESS_COURSE,LAB_TEST`
3. 写入一条 `patient_raw_data_change_task`
4. 写入一条 `infection_event_task(EVENT_EXTRACT)`

示意：

```text
patient_raw_data_change_task
  patient_raw_data_id=501
  reqno=REQ-9001
  data_date=2026-04-03
  raw_data_last_time=2026-04-03 03:12:10
  source_batch_time=2026-04-03 03:00:00
  status=PENDING

infection_event_task
  task_type=EVENT_EXTRACT
  reqno=REQ-9001
  patient_raw_data_id=501
  data_date=2026-04-03
  raw_data_last_time=2026-04-03 03:12:10
  source_batch_time=2026-04-03 03:00:00
  changed_types=ILLNESS_COURSE,LAB_TEST
  trigger_reason_codes=ILLNESS_COURSE_CHANGED,LAB_RESULT_CHANGED
  merge_key=raw:501:2026-04-03T03:12:10
  status=PENDING
```

### 4.4 步骤 3：事件抽取先跑

`processPendingEventTasks()` claim 到该事件任务：

1. 读取 `patient_raw_data_id=501`
2. 校验 `raw_data_last_time` 仍是当前版本
3. 构建时间窗口和证据块
4. 调用 LLM 事件抽取
5. 写入 `infection_event_pool`
6. 若有新增事件，则追加 `CASE_RECOMPUTE`

示意：

```text
infection_event_task
  task_type=CASE_RECOMPUTE
  reqno=REQ-9001
  patient_raw_data_id=501
  source_batch_time=2026-04-03 03:00:00
  merge_key=case:REQ-9001:2026-04-03T03:00
  status=PENDING
```

原 `EVENT_EXTRACT` 任务更新为：

```text
status=SUCCESS
last_error_message=事件抽取成功，已创建caseTask=1
```

### 4.5 步骤 4：结构化稍后跑

`formatPendingStructData()` 之后消费 `patient_raw_data_change_task`：

1. 校验这条 change 仍对应当前版本
2. 生成 `struct_data_json`
3. 更新 `event_json`
4. 任务标记 `SUCCESS`

这说明：

- 同一条 `patient_raw_data` 版本，会同时进入事件链和结构化链
- 两条链互不阻塞
- 预警链优先，结构化链负责补强与展示

### 4.6 步骤 5：若版本过期

如果在事件任务尚未执行前，该患者同一天的数据又更新了一次，导致：

- 任务里的 `raw_data_last_time = 03:12:10`
- 但 `patient_raw_data.last_time = 04:01:05`

则 `EVENT_EXTRACT` 任务会被视为旧版本任务，直接：

```text
status=SKIPPED
last_error_message=事件任务版本已过期，跳过
```

结构化链路同样会做版本过滤，只处理仍然是当前版本的快照。

## 5. 审查建议

你后续审代码时，可以按下面顺序逐步核对：

1. `enqueuePendingPatients()` 是否真的只在批次推进时入队
2. `collectAndSaveRawDataResult()` 是否同时写了两张任务表
3. `infection_event_task` 是否只由来源规则路由，不额外做候选层 LLM
4. `processPendingEventTasks()` 是否只消费 `infection_event_task(EVENT_EXTRACT)`
5. `processPendingCaseTasks()` 是否只消费 `infection_event_task(CASE_RECOMPUTE)`
6. `formatPendingStructData()` 是否只消费 `patient_raw_data_change_task`
7. 事件链和结构化链是否都做了版本过滤
