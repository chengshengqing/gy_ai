# 定时任务调度方案

## 1. 文档定位

本文档只说明当前定时任务如何进入共享线程池、如何 claim 任务、如何做阶段资源倾斜，以及调度层与业务链路的边界。

本文档不重复展开具体业务方法链路。需要从 Handler 继续追到 Service / Mapper / AI 调用时，查看：

- `docs/code-paths/code-chain-index.md`

系统总览查看：

- `docs/overview/architecture.md`

## 2. 调度入口与阶段映射

当前调度主链路统一为：

```text
@Scheduled
  -> InfectionPipelineFacade
  -> StageDispatcher
  -> StageCoordinator
  -> WorkUnitExecutor
  -> Handler
```

五个共享线程池阶段：

| 定时任务入口 | Facade 触发方法 | Stage | 业务目标 |
| --- | --- | --- | --- |
| `InfectionMonitorScheduler.enqueuePendingPatients()` | `triggerLoadEnqueue()` | `LOAD_ENQUEUE` | 扫描上游批次并将患者采集任务入队 |
| `InfectionMonitorScheduler.processPendingCollectTasks()` | `triggerLoadProcess()` | `LOAD_PROCESS` | 执行原始数据采集并写入 `patient_raw_data` |
| `StructDataFormatScheduler.formatPendingStructData()` | `triggerNormalize()` | `NORMALIZE` | 对原始数据做结构化整理并写回 `struct_data_json / event_json` |
| `SummaryWarningScheduler.processPendingEventTasks()` | `triggerEventExtract()` | `EVENT_EXTRACT` | 从证据块抽取标准化事件并写入事件池 |
| `SummaryWarningScheduler.processPendingCaseTasks()` | `triggerCaseRecompute()` | `CASE_RECOMPUTE` | 基于事件池和快照做病例重算与裁决 |

阶段枚举：

- `LOAD_ENQUEUE`
- `LOAD_PROCESS`
- `NORMALIZE`
- `EVENT_EXTRACT`
- `CASE_RECOMPUTE`

边界：

- `@Scheduled` 只触发阶段。
- `InfectionPipelineFacade` 只转发阶段触发。
- `StageDispatcher` 只负责调度 coordinator。
- `StageCoordinator` 只负责 claim 和投递 work unit。
- `Handler` 才进入具体业务执行。

## 3. 任务表职责

### 3.1 `patient_raw_data_collect_task`

作用：

- 承载患者级原始采集任务。
- 解耦批次扫描和采集执行。
- 记录本轮批次时间、上一批次时间、变更类型和任务状态。

主要消费阶段：

- `LOAD_PROCESS`

### 3.2 `patient_raw_data_change_task`

作用：

- 承载结构化链路任务。
- 一条任务对应一条 `patient_raw_data` 的具体版本。
- 以 `patient_raw_data_id + raw_data_last_time` 形式驱动 `NORMALIZE`。

主要消费阶段：

- `NORMALIZE`

说明：

- 事件抽取链路已经从这张表剥离。
- 结构化链路会做版本过滤，只处理仍然是当前版本的快照。

### 3.3 `infection_event_task`

作用：

- 承载院感预警主链路任务。

当前 `task_type`：

- `EVENT_EXTRACT`
- `CASE_RECOMPUTE`

主要消费阶段：

- `EVENT_EXTRACT`
- `CASE_RECOMPUTE`

关键调度字段：

- `task_type`
- `reqno`
- `patient_raw_data_id`
- `data_date`
- `raw_data_last_time`
- `source_batch_time`
- `changed_types`
- `trigger_reason_codes`
- `merge_key`
- `first_triggered_at`
- `last_event_at`
- `debounce_until`
- `trigger_priority`
- `event_pool_version_at_enqueue`
- `status`

## 4. 共享线程池模型

共享线程池 `WorkUnitExecutor` 中会运行两类 work unit：

1. `coordinator work unit`
   - 由 `StageDispatcher.tryScheduleCoordinator(...)` 提交。
   - 负责执行某阶段的一次 `dispatchOnce()`。
   - 不直接执行业务，只负责 claim 和投递。

2. `business work unit`
   - 由各 `StageCoordinator` 在 `dispatchOnce()` 中提交。
   - 负责调用对应 `Handler` 执行业务。

共享线程池使用 `PriorityBlockingQueue`。

排序规则：

1. 先按 `StagePolicy.priority` 倒序执行。
2. 同优先级下按提交先后顺序执行。

线程数上限：

- 基础配置来自 `infection.pipeline.shared-threads`。
- 如果使用 Hikari，最终共享线程数不会超过 `maximumPoolSize - 1`。

这样做是为了避免调度线程把数据库连接耗尽。

## 5. StagePolicy

`StagePolicy` 当前包含四个核心字段：

| 参数 | 作用 |
| --- | --- |
| `stage` | 策略归属阶段 |
| `priority` | 该阶段 work unit 在共享线程池中的优先级 |
| `maxInFlight` | 该阶段同时运行中的 work unit 上限 |
| `batchDispatchSize` | 单次 `dispatchOnce()` 最多 claim 的任务数上限 |

注意：

- `priority` 控执行顺序。
- `maxInFlight` 控阶段并发。
- `batchDispatchSize` 控单次 claim 批量。
- `batchDispatchSize` 不是线程数，也不是全局并发上限。

对 `LOAD_PROCESS / NORMALIZE / EVENT_EXTRACT / CASE_RECOMPUTE` 这类 claim 型阶段，单次 claim 数由三层共同约束：

```text
availableSlots = maxInFlight - currentInFlight
configuredBatchSize = min(stageConfigBatchSize, policy.batchDispatchSize)
claimLimit = min(availableSlots, configuredBatchSize)
```

其中：

- `maxInFlight` 决定当前阶段还能同时跑多少。
- `StagePolicy.batchDispatchSize` 决定本轮最多从库里 claim 多少。
- 业务配置 `batchSize` 决定这个阶段自身愿意一次处理多大批量。

`LOAD_ENQUEUE` 不走任务表 claim，它直接提交一个固定扫描 work unit。

## 6. 资源倾斜策略

### 6.1 普通时段

| Stage | priority | maxInFlight | batchDispatchSize | 说明 |
| --- | ---: | ---: | ---: | --- |
| `LOAD_ENQUEUE` | 100 | 1 | 1 | 入队扫描保持单实例，避免重复扫描和重复入队 |
| `LOAD_PROCESS` | 90 | 3 | 3 | 原始采集是上游入口，优先级高于后续链路 |
| `NORMALIZE` | 80 | 2 | 4 | 结构化默认稳态推进，不压满资源 |
| `EVENT_EXTRACT` | 70 | 8 | 8 | 事件抽取在普通时段吞吐放得最开 |
| `CASE_RECOMPUTE` | 60 | 3 | 4 | 病例重算在主链路末端，优先级最低 |

普通时段倾向：

- 保证采集入口优先推进。
- 让事件抽取承担较高吞吐。
- 让病例重算在后端持续收敛。

### 6.2 `NORMALIZE` 优先窗口

| Stage | priority | maxInFlight | batchDispatchSize | 说明 |
| --- | ---: | ---: | ---: | --- |
| `LOAD_ENQUEUE` | 100 | 1 | 1 | 批次扫描仍保持单实例 |
| `NORMALIZE` | 95 | 8 | 8 | 结构化成为当前窗口的最高优先阶段 |
| `LOAD_PROCESS` | 80 | 1 | 1 | 采集降速，给结构化让路 |
| `EVENT_EXTRACT` | 60 | 2 | 2 | 事件抽取压缩配额，避免抢占资源 |
| `CASE_RECOMPUTE` | 50 | 1 | 2 | 病例重算进一步降速，保证上游先清空积压 |

窗口期倾向：

- 先把 `patient_raw_data` 的结构化上下文补齐。
- 再让事件抽取和病例重算跟上。
- 避免后置阶段长期挤占结构化阶段资源。

相关配置：

- `infection.pipeline.normalize-window-start-hours`
- `infection.pipeline.normalize-window-duration-hours`
- `infection.pipeline.shared-threads`
- `infection.pipeline.model-permits`

## 7. 五阶段调度流程

本节只写到 Handler 边界。Handler 之后的业务方法链路见 `docs/code-paths/code-chain-index.md`。

### 7.1 `LOAD_ENQUEUE`

```text
InfectionMonitorScheduler.enqueuePendingPatients()
  -> InfectionPipelineFacade.triggerLoadEnqueue()
  -> StageDispatcher.trigger(LOAD_ENQUEUE)
  -> coordinator:LOAD_ENQUEUE
  -> LoadEnqueueCoordinator.dispatchOnce()
  -> business work unit: load-enqueue
  -> LoadEnqueueHandler.handle()
```

特点：

- 不 claim 任务表。
- 不分批。
- 永远只跑一个扫描任务。

### 7.2 `LOAD_PROCESS`

```text
InfectionMonitorScheduler.processPendingCollectTasks()
  -> InfectionPipelineFacade.triggerLoadProcess()
  -> StageDispatcher.trigger(LOAD_PROCESS)
  -> coordinator:LOAD_PROCESS
  -> LoadProcessCoordinator.dispatchOnce()
  -> claim patient_raw_data_collect_task
  -> business work unit per task
  -> LoadProcessHandler.handle(task)
```

特点：

- claim 患者级采集任务。
- 采集成功后会产生结构化任务和事件抽取任务。

### 7.3 `NORMALIZE`

```text
StructDataFormatScheduler.formatPendingStructData()
  -> InfectionPipelineFacade.triggerNormalize()
  -> StageDispatcher.trigger(NORMALIZE)
  -> coordinator:NORMALIZE
  -> NormalizeCoordinator.dispatchOnce()
  -> claim patient_raw_data_change_task
  -> business work unit per reqno group
  -> NormalizeHandler.handle(taskGroup)
```

特点：

- claim 的是多条变更任务。
- 真正投递的 work item 是按 `reqno` 聚合后的任务组。
- `batchDispatchSize=8` 不等于一定产生 8 个并发 work unit。

### 7.4 `EVENT_EXTRACT`

```text
SummaryWarningScheduler.processPendingEventTasks()
  -> InfectionPipelineFacade.triggerEventExtract()
  -> StageDispatcher.trigger(EVENT_EXTRACT)
  -> coordinator:EVENT_EXTRACT
  -> EventExtractCoordinator.dispatchOnce()
  -> claim infection_event_task(EVENT_EXTRACT)
  -> business work unit per task
  -> EventExtractHandler.handle(task)
```

特点：

- 单任务单 work unit。
- 普通时段吞吐最高。
- 在 `NORMALIZE` 优先窗口会主动让出资源。

### 7.5 `CASE_RECOMPUTE`

```text
SummaryWarningScheduler.processPendingCaseTasks()
  -> InfectionPipelineFacade.triggerCaseRecompute()
  -> StageDispatcher.trigger(CASE_RECOMPUTE)
  -> coordinator:CASE_RECOMPUTE
  -> CaseRecomputeCoordinator.dispatchOnce()
  -> claim infection_event_task(CASE_RECOMPUTE)
  -> business work unit per task
  -> CaseRecomputeHandler.handle(task)
```

特点：

- 单任务单 work unit。
- 位于院感预警主链路最后一段。
- 在两套策略里都低于 `LOAD_PROCESS` 和 `NORMALIZE`。

## 8. 持续推进机制

共享线程池不是每次触发只跑一轮，它有补投机制。

### 8.1 trigger

`StageDispatcher.trigger(stage)` 会：

1. 将该 stage 标记为 `triggerPending=true`。
2. 尝试投递一个 coordinator。

### 8.2 dispatch

coordinator 在共享线程池中执行 `dispatchOnce()`：

1. 读取当前 `StagePolicy`。
2. 检查 `availableSlots`。
3. 计算 `claimLimit`。
4. claim 小批任务。
5. 构造 business work unit。
6. 提交 business work unit。

### 8.3 completion

business work unit 执行完后，`WorkUnitExecutor` 回调：

```text
StageDispatcher.onWorkUnitCompleted(stage)
```

此时会：

1. `inFlight - 1`。
2. 如果该阶段仍有 `triggerPending=true` 且还有空闲槽位，则再次投递 coordinator。

因此当前机制是：

```text
小批 claim -> 执行 -> 完成后补投 -> 继续小批 claim
```

而不是一次性把大量任务全部塞进线程池。

## 9. 调度规则与边界

### 9.1 下游任务路由

采集成功后，每条有效 `patient_raw_data` 新版本都会：

1. 写入 `patient_raw_data_change_task`。
2. 若命中事件来源规则，则写入 `infection_event_task(EVENT_EXTRACT)`。

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

### 9.2 版本过滤

事件抽取和结构化都会校验任务版本：

```text
task.raw_data_last_time == patient_raw_data.last_time
```

如果不一致：

- 当前任务视为旧版本任务。
- 标记为 `SKIPPED` 或失败上下文中的跳过结果。
- 不再继续执行当前任务对应的业务处理。

### 9.3 结构化链与事件链并行

采集后同时路由：

```text
collect
  -> patient_raw_data_change_task -> NORMALIZE
  -> infection_event_task(EVENT_EXTRACT) -> EVENT_EXTRACT
```

说明：

- 结构化链与事件链并行推进。
- 事件链不再等待结构化链完成后才启动。
- `patient_raw_data.event_json` 作为窗口上下文来源，结构化更新会持续补强后续处理。

### 9.4 `CASE_RECOMPUTE` 合并与防抖

`CASE_RECOMPUTE` 当前按患者级合并：

```text
CASE_RECOMPUTE:{reqno}
```

相关字段：

- `first_triggered_at`
- `last_event_at`
- `debounce_until`
- `trigger_priority`
- `event_pool_version_at_enqueue`

调度语义：

- 多个事件抽取结果可以合并为同一个患者级重算任务。
- 防抖窗口内的任务会延后执行。
- 执行前根据事件池版本判断是否需要重算或跳过。

## 10. 为什么能实现资源倾斜

原因：

1. 所有 stage 共享一个优先级线程池。
2. 每个 stage 都有自己的 `maxInFlight`。
3. 每次只 claim 一个小批量。
4. `StagePolicyRegistry` 支持普通时段和 `NORMALIZE` 优先窗口动态切换。

这套策略不是追求绝对公平，而是：

- 让上游和关键中间层先完成。
- 让后置重处理阶段持续跟进。
- 在 `NORMALIZE` 优先窗口里短时偏向结构化阶段，保证主链路上下文尽快成型。

## 11. 阅读代码入口

建议按这个顺序阅读调度代码：

1. `task/*Scheduler`
2. `pipeline.facade.InfectionPipelineFacade`
3. `pipeline.scheduler.executor.StageDispatcher`
4. `pipeline.scheduler.policy.StagePolicyRegistry`
5. `pipeline.stage.AbstractStageCoordinator`
6. `pipeline.stage.AbstractClaimingStageCoordinator`
7. 各阶段 `*Coordinator`
8. 各阶段 `*Handler`

继续追业务方法链路时，跳转：

- `docs/code-paths/code-chain-index.md`
