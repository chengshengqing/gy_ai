# 调度资源倾斜与共享线程池执行流程

本文档描述当前已经落地的调度实现，重点回答两个问题：

1. 五个定时任务如何进入同一个共享线程池。
2. `StagePolicy` 如何对不同阶段做资源倾斜。

本文档基于当前代码实现，不是规划草案。

## 1. 总览

当前调度主链路统一为：

`@Scheduled -> InfectionPipelineFacade -> StageDispatcher -> StageCoordinator -> WorkUnitExecutor -> Handler`

共享线程池中实际承载的五个业务阶段为：

| 定时任务入口 | Facade 触发方法 | Stage | 业务目标 |
| --- | --- | --- | --- |
| `InfectionMonitorScheduler.enqueuePendingPatients()` | `triggerLoadEnqueue()` | `LOAD_ENQUEUE` | 扫描上游批次并将患者采集任务入队 |
| `InfectionMonitorScheduler.processPendingCollectTasks()` | `triggerLoadProcess()` | `LOAD_PROCESS` | 执行原始数据采集并写入 `patient_raw_data` |
| `StructDataFormatScheduler.formatPendingStructData()` | `triggerNormalize()` | `NORMALIZE` | 对原始数据做结构化整理并写回 `struct_data_json / event_json` |
| `SummaryWarningScheduler.processPendingEventTasks()` | `triggerEventExtract()` | `EVENT_EXTRACT` | 从证据块抽取标准化事件并写入事件池 |
| `SummaryWarningScheduler.processPendingCaseTasks()` | `triggerCaseRecompute()` | `CASE_RECOMPUTE` | 基于事件池和快照做病例重算与裁决 |

对应的阶段枚举固定为：

- `LOAD_ENQUEUE`
- `LOAD_PROCESS`
- `NORMALIZE`
- `EVENT_EXTRACT`
- `CASE_RECOMPUTE`

## 2. 共享线程池的真实结构

### 2.1 线程池不是只跑业务任务

共享线程池 `WorkUnitExecutor` 里实际会跑两类任务：

1. `coordinator work unit`
   - 由 `StageDispatcher.tryScheduleCoordinator(...)` 提交。
   - 负责本阶段的一次 `dispatchOnce()`。
   - 它不直接做业务，只负责 claim 和投递。

2. `business work unit`
   - 由各 `StageCoordinator` 在 `dispatchOnce()` 中继续提交。
   - 负责真正执行 `Handler`。

也就是说，线程池里既有“调度任务”，也有“业务任务”，它们使用同一套优先级队列。

### 2.2 线程池的排序规则

共享线程池使用 `PriorityBlockingQueue`。

排序规则只有两层：

1. 先按 `StagePolicy.priority` 倒序执行。
2. 同优先级下按提交先后顺序执行。

因此，阶段倾斜不是靠独立线程池实现的，而是靠单池内的优先级和并发配额实现的。

### 2.3 线程数上限

共享线程数来自：

- `infection.pipeline.shared-threads`

但最终生效线程数还会再和 JDBC 连接池做一次保护：

- 如果使用 Hikari，则最大线程数不会超过 `maximumPoolSize - 1`

这样做的目的，是避免调度线程把数据库连接耗尽。

## 3. StagePolicy 的四个参数

`StagePolicy` 当前只有四个字段：

| 参数 | 作用 |
| --- | --- |
| `stage` | 策略归属到哪个阶段 |
| `priority` | 该阶段 work unit 在共享线程池中的优先级 |
| `maxInFlight` | 该阶段同时运行中的 work unit 上限 |
| `batchDispatchSize` | 单次 `dispatchOnce()` 最多 claim 的任务数上限 |

要特别区分：

- `priority` 控执行顺序
- `maxInFlight` 控阶段并发
- `batchDispatchSize` 控单次 claim 批量

`batchDispatchSize` 不是线程数，也不是全局并发上限。

## 4. 当前资源倾斜策略

### 4.1 普通时段

当前普通时段策略如下：

| Stage | priority | maxInFlight | batchDispatchSize | 解释 |
| --- | ---: | ---: | ---: | --- |
| `LOAD_ENQUEUE` | 100 | 1 | 1 | 入队扫描保持单实例，避免重复扫描和重复入队 |
| `LOAD_PROCESS` | 90 | 3 | 3 | 原始采集是上游入口，优先级高于后续链路 |
| `NORMALIZE` | 80 | 2 | 4 | 结构化默认稳态推进，不压满资源 |
| `EVENT_EXTRACT` | 70 | 8 | 8 | 事件抽取在普通时段吞吐放得最开 |
| `CASE_RECOMPUTE` | 60 | 3 | 4 | 病例重算在主链路末端，优先级最低 |

### 4.2 NORMALIZE 优先窗口

在 `normalize window` 内，策略切换为：

| Stage | priority | maxInFlight | batchDispatchSize | 解释 |
| --- | ---: | ---: | ---: | --- |
| `LOAD_ENQUEUE` | 100 | 1 | 1 | 批次扫描仍保持单实例 |
| `LOAD_PROCESS` | 80 | 1 | 1 | 采集降速，给结构化让路 |
| `NORMALIZE` | 95 | 8 | 8 | 结构化成为当前窗口的最高优先阶段 |
| `EVENT_EXTRACT` | 60 | 2 | 2 | 事件抽取压缩配额，避免抢占资源 |
| `CASE_RECOMPUTE` | 50 | 1 | 2 | 病例重算进一步降速，保证上游先清空积压 |

### 4.3 资源倾斜图

#### 普通时段

```text
优先级高
  LOAD_ENQUEUE   [100]  并发 1  批量 1
  LOAD_PROCESS   [ 90]  并发 3  批量 3
  NORMALIZE      [ 80]  并发 2  批量 4
  EVENT_EXTRACT  [ 70]  并发 8  批量 8
  CASE_RECOMPUTE [ 60]  并发 3  批量 4
优先级低
```

普通时段的倾向是：

- 保证采集入口优先推进
- 让事件抽取承担更高吞吐
- 让病例重算在后面持续收敛

#### NORMALIZE 优先窗口

```text
优先级高
  LOAD_ENQUEUE   [100]  并发 1  批量 1
  NORMALIZE      [ 95]  并发 8  批量 8
  LOAD_PROCESS   [ 80]  并发 1  批量 1
  EVENT_EXTRACT  [ 60]  并发 2  批量 2
  CASE_RECOMPUTE [ 50]  并发 1  批量 2
优先级低
```

窗口期的倾向是：

- 先把 `patient_raw_data` 的结构化上下文补齐
- 再让事件抽取和病例重算跟上
- 避免后置阶段长期挤占结构化阶段的资源

## 5. 单次调度是如何算 claim 数量的

对于 `LOAD_PROCESS / NORMALIZE / EVENT_EXTRACT / CASE_RECOMPUTE` 这类 claim 型 stage，单次 `dispatchOnce()` 的实际 claim 数不是只看 `batchDispatchSize`，而是三层共同约束：

1. 阶段当前还剩多少并发槽位
2. `StagePolicy.batchDispatchSize`
3. 该 stage 的业务配置 `batchSize`

可简化为：

```java
availableSlots = maxInFlight - currentInFlight
configuredBatchSize = min(stageConfigBatchSize, policy.batchDispatchSize)
claimLimit = min(availableSlots, configuredBatchSize)
```

其中：

- `LOAD_ENQUEUE` 不走 claim 逻辑，它是直接提交一个固定 work unit
- 其余四个 stage 都走 claim 逻辑

因此：

- `maxInFlight` 决定“能同时跑多少”
- `batchDispatchSize` 决定“一次最多从库里捞多少”
- 业务配置 `batchSize` 决定“这个阶段自己愿意一次吃多大一批”

## 6. 五个任务进入共享线程池的完整流程

下面的五条流程，描述的是“从定时触发到 handler 执行”的实际路径。

### 6.1 LOAD_ENQUEUE

```text
InfectionMonitorScheduler.enqueuePendingPatients()
  -> InfectionPipelineFacade.triggerLoadEnqueue()
  -> StageDispatcher.trigger(LOAD_ENQUEUE)
  -> 提交 coordinator:LOAD_ENQUEUE 到共享线程池
  -> LoadEnqueueCoordinator.dispatchOnce()
  -> 提交 business work unit: load-enqueue
  -> LoadEnqueueHandler.handle()
  -> 扫描 sourceBatchTime 和活跃患者
  -> 写入或重置 patient_raw_data_collect_task
```

这个阶段的特点：

- 没有 claim 任务表
- 不分批
- 永远只跑一个扫描任务

### 6.2 LOAD_PROCESS

```text
InfectionMonitorScheduler.processPendingCollectTasks()
  -> InfectionPipelineFacade.triggerLoadProcess()
  -> StageDispatcher.trigger(LOAD_PROCESS)
  -> 提交 coordinator:LOAD_PROCESS 到共享线程池
  -> LoadProcessCoordinator.dispatchOnce()
  -> claim patient_raw_data_collect_task
  -> 为每条采集任务提交一个 business work unit
  -> LoadProcessHandler.handle(task)
  -> patientService.collectAndSaveRawDataResult(...)
  -> 写入/更新 patient_raw_data
  -> 路由 patient_raw_data_change_task
  -> 必要时路由 infection_event_task(EVENT_EXTRACT)
```

这个阶段是整个链路的上游生产者：

- 它一边落 `patient_raw_data`
- 一边给展示链路和预警链路分别产出下游任务

### 6.3 NORMALIZE

```text
StructDataFormatScheduler.formatPendingStructData()
  -> InfectionPipelineFacade.triggerNormalize()
  -> StageDispatcher.trigger(NORMALIZE)
  -> 提交 coordinator:NORMALIZE 到共享线程池
  -> NormalizeCoordinator.dispatchOnce()
  -> claim patient_raw_data_change_task
  -> 按 reqno 分组构造成 work item
  -> 为每个 reqno 组提交一个 business work unit
  -> NormalizeHandler.handle(taskGroup)
  -> NormalizeRowProcessor / NormalizeStructDataComposer
  -> 写回 patient_raw_data.struct_data_json / event_json
```

这个阶段的特点是：

- claim 的是“多条变更 task”
- 真正投递到线程池的 work item 是“按 `reqno` 聚合后的任务组”
- 所以 `batchDispatchSize=8` 不等于一定产生 8 个并发 work unit

### 6.4 EVENT_EXTRACT

```text
SummaryWarningScheduler.processPendingEventTasks()
  -> InfectionPipelineFacade.triggerEventExtract()
  -> StageDispatcher.trigger(EVENT_EXTRACT)
  -> 提交 coordinator:EVENT_EXTRACT 到共享线程池
  -> EventExtractCoordinator.dispatchOnce()
  -> claim infection_event_task(EVENT_EXTRACT)
  -> 为每条事件任务提交一个 business work unit
  -> EventExtractHandler.handle(task)
  -> 构建 evidence block
  -> 调用 LlmEventExtractorService
  -> 写入 infection_event_pool
  -> 必要时 upsert infection_event_task(CASE_RECOMPUTE)
```

这个阶段的特点是：

- 单任务单 work unit
- 普通时段吞吐最高
- 但在 `NORMALIZE` 优先窗口里会主动让出资源

### 6.5 CASE_RECOMPUTE

```text
SummaryWarningScheduler.processPendingCaseTasks()
  -> InfectionPipelineFacade.triggerCaseRecompute()
  -> StageDispatcher.trigger(CASE_RECOMPUTE)
  -> 提交 coordinator:CASE_RECOMPUTE 到共享线程池
  -> CaseRecomputeCoordinator.dispatchOnce()
  -> claim infection_event_task(CASE_RECOMPUTE)
  -> 为每条病例任务提交一个 business work unit
  -> CaseRecomputeHandler.handle(task)
  -> 检查 infection_case_snapshot 与 event pool version
  -> 构建 InfectionEvidencePacket
  -> 调用 InfectionJudgeService
  -> 更新 infection_case_snapshot
  -> 写入 infection_alert_result
```

这个阶段的特点是：

- 也是单任务单 work unit
- 但处于主链路最后一段
- 所以在两套策略里都低于 `NORMALIZE` 和 `LOAD_PROCESS`

## 7. 调度循环如何持续推进

共享线程池不是每次触发只跑一轮，它有“补投”机制。

### 7.1 trigger

`StageDispatcher.trigger(stage)` 会先把该 stage 标记为 `triggerPending=true`，然后尝试投递一个 coordinator。

### 7.2 coordinator dispatch

coordinator 在共享线程池中执行 `dispatchOnce()`，完成：

1. 读取当前 `StagePolicy`
2. 检查 `availableSlots`
3. 计算 `claimLimit`
4. claim 小批任务
5. 构造 work item
6. 提交 business work unit

### 7.3 business completion

business work unit 执行完后，`WorkUnitExecutor` 会回调：

- `StageDispatcher.onWorkUnitCompleted(stage)`

此时会做两件事：

1. `inFlight - 1`
2. 如果该阶段仍有 `triggerPending=true`，并且还有空闲槽位，则再次投递 coordinator

所以整个机制不是“大批量一次性塞满线程池”，而是“小批 claim + 完成后继续补投”。

## 8. 为什么这套设计能实现倾斜而不是互相打满

原因有四个：

1. 所有 stage 共享一个优先级线程池
   - 高优阶段不需要专门独占线程池，也能先拿到执行机会。

2. 每个 stage 都有自己的 `maxInFlight`
   - 即使某阶段 backlog 很大，也不能无限扩张。

3. 每次只 claim 一个小批量
   - 减少了单个 stage 一次性把队列塞满的风险。

4. `NORMALIZE` 窗口支持动态切换策略
   - 通过 `StagePolicyRegistry` 在普通时段和窗口时段之间切换，不需要改业务代码。

## 9. 业务理解上的关键结论

从业务角度看，这五个任务不是平级竞争关系，而是同一条链路中的不同位置：

1. `LOAD_ENQUEUE`
   - 负责发现新批次。

2. `LOAD_PROCESS`
   - 负责把源数据变成可处理的 `patient_raw_data`。

3. `NORMALIZE`
   - 负责给展示和后续 LLM 处理补齐结构化上下文。

4. `EVENT_EXTRACT`
   - 负责把证据块提升成标准化事件。

5. `CASE_RECOMPUTE`
   - 负责在事件池之上做病例级判断和结果沉淀。

因此，当前倾斜策略的核心思想不是绝对公平，而是：

- 让上游和关键中间层先完成
- 让后置重处理阶段持续跟进
- 在 `NORMALIZE` 优先窗口里短时偏向结构化阶段，保证主链路上下文尽快成型

## 10. 阅读代码时的对照点

如果要对照代码阅读，建议按这个顺序：

1. `task/*Scheduler.java`
2. `pipeline/facade/InfectionPipelineFacade`
3. `pipeline/scheduler/executor/StageDispatcher`
4. `pipeline/scheduler/policy/StagePolicyRegistry`
5. `pipeline/stage/AbstractStageCoordinator`
6. `pipeline/stage/AbstractClaimingStageCoordinator`
7. 各阶段 `*Coordinator`
8. 各阶段 `*Handler`

这样最容易看清：

- 定时任务只负责触发
- Facade 只负责转发
- Dispatcher 只负责调度
- Coordinator 只负责 claim 和投递
- Handler 才是真正执行业务
