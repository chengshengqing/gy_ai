# 共享线程池调度架构设计草案

## 1. 设计目标

本文档用于规划将以下定时任务统一纳入共享线程池 `sharedExecutor` 的架构设计：

- `enqueuePendingPatients`
- `processPendingCollectTasks`
- `formatPendingStructData`
- `processPendingEventTasks`
- `processPendingCaseTasks`

设计目标：

1. 线程调度代码与业务代码彻底解耦。
2. `@Scheduled` 只负责触发，不直接执行业务。
3. 所有任务进入统一调度框架，由共享线程池统一调度。
4. 模型调用并发控制独立抽象，不嵌入具体业务处理逻辑。
5. 避免“线程池内嵌套提交并阻塞等待”的线程饥饿问题。
6. 为后续按时段动态调整 `NORMALIZE / EVENT_EXTRACT / CASE_RECOMPUTE` 配额预留扩展点。

## 2. 分层设计

建议拆分为 5 层：

1. Trigger Layer
   - 接收 `@Scheduled` 触发。
   - 只发起阶段触发信号。

2. Pipeline Facade Layer
   - 对外暴露统一触发入口。
   - 不负责任务 claim、线程调度或业务执行。

3. Scheduling Layer
   - 维护共享线程池、优先级、阶段并发、全局模型 permit。
   - 只认识阶段、策略、工作单元，不认识业务实体含义。

4. Stage Coordination Layer
   - 负责每个阶段的 claim 和 work unit 组装。
   - 负责“拉一小批任务并投递”，不负责任务业务执行。

5. Business Execution Layer
   - 负责采集、结构化、事件抽取、病例重算等具体业务。
   - 不直接依赖线程池，不维护调度状态。
- 业务执行层内部继续按具体职责向领域包下沉，例如 `domain.normalize.*`、`domain.format.*`；与 normalize 编排强相关的具体职责类保留在 `service.normalize`，避免 handler 或 agent 再次膨胀。
   - 当前模型调用边界已经统一收敛到 `ai.gateway.AiGateway -> ModelCallGuard`。

## 3. 包结构建议

建议新增以下包：

```text
com.zzhy.yg_ai.pipeline.facade
com.zzhy.yg_ai.pipeline.scheduler
com.zzhy.yg_ai.pipeline.stage
com.zzhy.yg_ai.pipeline.handler
com.zzhy.yg_ai.pipeline.model
```

## 4. 类清单

### 4.1 Facade 层

#### `com.zzhy.yg_ai.pipeline.facade.InfectionPipelineFacade`

职责：

- 提供统一触发入口。
- 将 scheduler 调用转发给调度框架。

### 4.2 Scheduler 框架层

#### `com.zzhy.yg_ai.pipeline.scheduler.PipelineStage`

职责：

- 统一阶段枚举。

建议枚举值：

- `LOAD_ENQUEUE`
- `LOAD_PROCESS`
- `NORMALIZE`
- `EVENT_EXTRACT`
- `CASE_RECOMPUTE`

#### `com.zzhy.yg_ai.pipeline.scheduler.ResourceProfile`

职责：

- 描述 work unit 的资源占用特征。

#### `com.zzhy.yg_ai.pipeline.scheduler.StagePolicy`

职责：

- 描述某阶段的调度策略。

#### `com.zzhy.yg_ai.pipeline.scheduler.StagePolicyRegistry`

职责：

- 提供阶段策略。
- 支持普通时段和特殊时段动态切换。

#### `com.zzhy.yg_ai.pipeline.scheduler.StageRuntimeState`

职责：

- 保存某阶段的运行态。

建议内部状态：

- `coordinatorScheduled`
- `triggerPending`
- `inFlight`
- `stagePermits`
- `lastSubmitTime`
- `lastFinishTime`

#### `com.zzhy.yg_ai.pipeline.scheduler.StageRuntimeRegistry`

职责：

- 维护所有阶段的 `StageRuntimeState`。

#### `com.zzhy.yg_ai.pipeline.scheduler.ModelConcurrencyLimiter`

职责：

- 统一管理模型调用 permit。
- 与业务调用解耦。

#### `com.zzhy.yg_ai.pipeline.scheduler.ModelCallGuard`

职责：

- 对模型调用进行统一包裹。
- 负责 acquire/release permit。
- 记录模型调用监控信息。

#### `com.zzhy.yg_ai.pipeline.scheduler.WorkUnit`

职责：

- 统一抽象调度执行单元。

#### `com.zzhy.yg_ai.pipeline.scheduler.WorkUnitExecutor`

职责：

- 负责将 `WorkUnit` 提交到共享线程池。
- 负责在任务完成后进行回调。

#### `com.zzhy.yg_ai.pipeline.scheduler.StageDispatcher`

职责：

- 接收阶段触发。
- 根据策略调度 coordinator。
- 负责 stage 级补投逻辑。

### 4.3 阶段协调层

#### `com.zzhy.yg_ai.pipeline.stage.StageCoordinator`

职责：

- 阶段协调器统一接口。

#### `com.zzhy.yg_ai.pipeline.stage.LoadEnqueueCoordinator`

职责：

- 处理 `LOAD_ENQUEUE` 的 claim 和投递。

#### `com.zzhy.yg_ai.pipeline.stage.LoadProcessCoordinator`

职责：

- 处理 `LOAD_PROCESS` 的 claim 和投递。

#### `com.zzhy.yg_ai.pipeline.stage.NormalizeCoordinator`

职责：

- 处理 `NORMALIZE` 的 claim 和投递。

#### `com.zzhy.yg_ai.pipeline.stage.EventExtractCoordinator`

职责：

- 处理 `EVENT_EXTRACT` 的 claim 和投递。

#### `com.zzhy.yg_ai.pipeline.stage.CaseRecomputeCoordinator`

职责：

- 处理 `CASE_RECOMPUTE` 的 claim 和投递。

#### `com.zzhy.yg_ai.pipeline.stage.WorkUnitFactory`

职责：

- 将业务实体转换为 `WorkUnit`。

### 4.4 业务执行层

#### `com.zzhy.yg_ai.pipeline.handler.LoadEnqueueHandler`

职责：

- 执行扫描上游批次和患者入队逻辑。

#### `com.zzhy.yg_ai.pipeline.handler.LoadProcessHandler`

职责：

- 执行患者原始数据采集任务。

#### `com.zzhy.yg_ai.pipeline.handler.NormalizeHandler`

职责：

- 执行结构化格式化任务。
- 编排任务组版本校验、单行结构化处理和结果收尾。
- 调用 `service.normalize.NormalizeStructDataService -> domain.normalize.*`。

#### `com.zzhy.yg_ai.pipeline.handler.EventExtractHandler`

职责：

- 执行事件抽取任务。

#### `com.zzhy.yg_ai.pipeline.handler.CaseRecomputeHandler`

职责：

- 执行病例重算任务。

### 4.5 任务结果模型

建议新增以下轻量结果对象，避免调度层依赖业务细节过深：

- `LoadEnqueueResult`
- `LoadProcessResult`
- `NormalizeResult`
- `EventExtractResult`
- `CaseRecomputeResult`

## 5. 方法签名草案

### 5.1 Facade 层

#### `InfectionPipelineFacade`

已收敛为具体类，不再保留接口 + Default 实现双层壳。

```java
public class InfectionPipelineFacade {

    public void triggerLoadEnqueue();

    public void triggerLoadProcess();

    public void triggerNormalize();

    public void triggerEventExtract();

    public void triggerCaseRecompute();
}
```

### 5.2 调度框架层

#### `ResourceProfile`

```java
public record ResourceProfile(
        boolean usesModel,
        boolean usesDbHeavy,
        int estimatedWeight
) {
}
```

#### `StagePolicy`

```java
public record StagePolicy(
        PipelineStage stage,
        int priority,
        int maxInFlight,
        int batchDispatchSize,
        boolean singleCoordinator,
        ResourceProfile resourceProfile
) {
}
```

#### `StagePolicyRegistry`

```java
public interface StagePolicyRegistry {

    StagePolicy getPolicy(PipelineStage stage);

    Map<PipelineStage, StagePolicy> currentPolicies();

    boolean isNormalizePreferredWindow();
}
```

#### `StageRuntimeState`

```java
public class StageRuntimeState {

    public boolean tryMarkCoordinatorScheduled();

    public void markCoordinatorFinished();

    public void markTriggered();

    public void clearTriggered();

    public int incrementInFlight();

    public int decrementInFlight();

    public int currentInFlight();

    public boolean hasPendingTrigger();
}
```

#### `StageRuntimeRegistry`

```java
public interface StageRuntimeRegistry {

    StageRuntimeState get(PipelineStage stage);
}
```

#### `ModelConcurrencyLimiter`

```java
public interface ModelConcurrencyLimiter {

    void acquire(PipelineStage stage) throws InterruptedException;

    boolean tryAcquire(PipelineStage stage);

    void release(PipelineStage stage);

    int availablePermits();
}
```

#### `ModelCallGuard`

```java
public interface ModelCallGuard {

    <T> T call(PipelineStage stage, Callable<T> action);

    void run(PipelineStage stage, Runnable action);
}
```

#### `WorkUnit`

```java
public interface WorkUnit {

    String unitId();

    PipelineStage stage();

    int priority();

    ResourceProfile resourceProfile();

    void execute();
}
```

#### `WorkUnitExecutor`

```java
public interface WorkUnitExecutor {

    void submit(WorkUnit workUnit);

    int queueSize();

    int activeCount();
}
```

#### `StageDispatcher`

```java
public interface StageDispatcher {

    void trigger(PipelineStage stage);

    void tryScheduleCoordinator(PipelineStage stage);

    void onWorkUnitCompleted(PipelineStage stage);
}
```

### 5.3 阶段协调层

#### `StageCoordinator`

```java
public interface StageCoordinator {

    PipelineStage stage();

    void dispatchOnce();
}
```

#### `LoadEnqueueCoordinator`

```java
public class LoadEnqueueCoordinator implements StageCoordinator {

    public PipelineStage stage();

    public void dispatchOnce();
}
```

#### `LoadProcessCoordinator`

```java
public class LoadProcessCoordinator implements StageCoordinator {

    public PipelineStage stage();

    public void dispatchOnce();
}
```

#### `NormalizeCoordinator`

```java
public class NormalizeCoordinator implements StageCoordinator {

    public PipelineStage stage();

    public void dispatchOnce();
}
```

#### `EventExtractCoordinator`

```java
public class EventExtractCoordinator implements StageCoordinator {

    public PipelineStage stage();

    public void dispatchOnce();
}
```

#### `CaseRecomputeCoordinator`

```java
public class CaseRecomputeCoordinator implements StageCoordinator {

    public PipelineStage stage();

    public void dispatchOnce();
}
```

#### `WorkUnitFactory`

```java
public interface WorkUnitFactory {

    WorkUnit newLoadEnqueueUnit();

    WorkUnit newLoadProcessUnit(PatientRawDataCollectTaskEntity taskEntity);

    WorkUnit newNormalizeUnit(List<PatientRawDataChangeTaskEntity> taskGroup);

    WorkUnit newEventExtractUnit(InfectionEventTaskEntity taskEntity);

    WorkUnit newCaseRecomputeUnit(InfectionEventTaskEntity taskEntity);
}
```

### 5.4 业务执行层

#### `LoadEnqueueHandler`

```java
public interface LoadEnqueueHandler {

    LoadEnqueueResult handle();
}
```

#### `LoadProcessHandler`

```java
public interface LoadProcessHandler {

    LoadProcessResult handle(PatientRawDataCollectTaskEntity taskEntity);
}
```

#### `NormalizeHandler`

```java
public interface NormalizeHandler {

    NormalizeResult handle(List<PatientRawDataChangeTaskEntity> taskGroup);
}
```

#### `EventExtractHandler`

```java
public interface EventExtractHandler {

    EventExtractResult handle(InfectionEventTaskEntity taskEntity);
}
```

#### `CaseRecomputeHandler`

```java
public interface CaseRecomputeHandler {

    CaseRecomputeResult handle(InfectionEventTaskEntity taskEntity);
}
```

### 5.5 结果对象

```java
public record LoadEnqueueResult(
        boolean success,
        int enqueuedCount,
        String message
) {
}

public record LoadProcessResult(
        Long taskId,
        String reqno,
        boolean success,
        String message
) {
}

public record NormalizeResult(
        List<Long> taskIds,
        String reqno,
        int totalRows,
        int successCount,
        int failedCount,
        String message
) {
}

public record EventExtractResult(
        List<Long> taskIds,
        String reqno,
        int successCount,
        int failedCount,
        boolean skipped,
        String message
) {
}

public record CaseRecomputeResult(
        List<Long> taskIds,
        String reqno,
        int successCount,
        int failedCount,
        boolean skipped,
        boolean rescheduled,
        String message
) {
}
```

## 6. 现有类如何迁移

### 6.1 Scheduler

以下类保留，但只保留 trigger 职责：

- `InfectionMonitorScheduler`
- `StructDataFormatScheduler`
- `SummaryWarningScheduler`

改造后只调用 `InfectionPipelineFacade`。

### 6.2 `InfectionPipeline`

建议迁移策略：

1. 现有 `InfectionPipeline` 不再负责线程池调度。
2. `executeParallelTasks(...)` 删除。
3. 当前业务处理方法迁移到各类 handler。
4. `InfectionPipeline` 可保留为 facade 兼容层，后续逐步替换为 `DefaultInfectionPipelineFacade`。

### 6.3 模型调用位置

以下模型调用点统一改由 `ModelCallGuard` 包裹：

- `SummaryAgent.extractDailyIllness(...)`
- `StructuredFactRefinementServiceImpl.refine(...)`
- `LlmEventExtractorServiceImpl.extractAndSave(...)`
- `InfectionJudgeServiceImpl.judge(...)`

建议业务 handler 依赖 `ModelCallGuard`，而不是直接感知 permit。

当前演进状态补充：

- `StructuredFactRefinementServiceImpl` 的候选构造、输出校验和结果应用已收口到 `service.evidence.StructuredFactRefinementSupport`
- `LlmEventExtractorServiceImpl` 的输入组装、输出规范化和运行载荷已收口到 `service.event.LlmEventExtractionSupport`
- `InfectionJudgeServiceImpl` 的输出解析、fallback 和运行载荷已收口到 `service.casejudge.InfectionCaseJudgeSupport`
- warning 相关 service 后续拆分应先按业务链路选择 `service` 侧具体职责类，不继续把 Spring 组件平铺进 `domain`

## 7. 推荐调度策略

### 7.1 普通时段

当前实际值：

- `LOAD_ENQUEUE = 1`
- `LOAD_PROCESS = 3`
- `NORMALIZE = 2`
- `EVENT_EXTRACT = 8`
- `CASE_RECOMPUTE = 3`
- `globalModelPermits = 12`
- `shared-threads = 16`（prod）

### 7.2 `NORMALIZE` 优先窗口

已实现。通过 `StagePolicyRegistry` 基于时间窗口自动切换。

适用于配置窗口时段（默认 `0:00-4:00 / 12:00-16:00`）：

- `LOAD_ENQUEUE = 1`（priority=100）
- `LOAD_PROCESS = 1`（priority=80，降低）
- `NORMALIZE = 8`（priority=95，提升为主消费者）
- `EVENT_EXTRACT = 2`（priority=60，保留少量处理紧急任务）
- `CASE_RECOMPUTE = 1`（priority=50，保底）
- `globalModelPermits = 12`

配置项：

- `infection.pipeline.normalize-window-start-hours`：窗口起始小时，逗号分隔（默认 `0,12`）
- `infection.pipeline.normalize-window-duration-hours`：窗口持续时长（默认 `4` 小时）

切换行为：

- 进入窗口时 `StagePolicyRegistry` 自动返回 NORMALIZE 优先策略
- 退出窗口时自动恢复普通时段策略
- 已有 in-flight 任务不会被强制中断，自然完成后新任务按新策略调度
- 策略切换日志：`进入 NORMALIZE 优先窗口` / `退出 NORMALIZE 优先窗口`

## 8. 禁止事项

以下设计不建议继续保留：

1. `@Scheduled` 方法内直接执行重业务逻辑。
2. 业务层直接依赖 `sharedExecutor`。
3. 在共享线程池内部继续 `submit + join` 同一个线程池。
4. 在业务代码中硬编码线程调度状态和 permit 管理。

## 9. 下一步实施建议

建议按以下顺序落地：

1. 新增调度框架抽象类和接口。
2. 将 scheduler 改为 trigger-only。
3. 引入 facade 层。
4. 拆出 handler 层，迁移业务逻辑。
5. 引入 coordinator 层，替换旧的批处理并发执行方式。
6. 最后接入 `ModelCallGuard` 和动态策略切换。

## 10. 调用时序设计

本节用于明确各层之间的调用顺序，确保“调度逻辑”和“业务逻辑”不交叉污染。

### 10.1 总体时序

```text
@Scheduled
  -> InfectionPipelineFacade.trigger(stage)
    -> StageDispatcher.trigger(stage)
      -> StageDispatcher.tryScheduleCoordinator(stage)
        -> sharedExecutor 执行 StageCoordinator.dispatchOnce()
          -> claim 一小批业务项
          -> WorkUnitFactory 组装 WorkUnit
          -> WorkUnitExecutor.submit(workUnit)
            -> sharedExecutor 执行 WorkUnit.execute()
              -> Handler.handle(...)
                -> 如需模型调用，走 ModelCallGuard.call(...)
              -> 完成后回调 StageDispatcher.onWorkUnitCompleted(stage)
                -> 释放 inFlight / permit
                -> 如仍有 backlog，继续 tryScheduleCoordinator(stage)
```

关键点：

1. scheduler 不直接进入业务逻辑。
2. coordinator 不等待所有子任务完成后再退出。
3. work unit 是唯一进入共享线程池执行具体业务的载体。
4. 模型限流只在 `ModelCallGuard` 统一处理。

### 10.2 Trigger 时序

职责：只发信号，不做业务。

```text
InfectionMonitorScheduler / StructDataFormatScheduler / SummaryWarningScheduler
  -> InfectionPipelineFacade.triggerXxx()
    -> StageDispatcher.trigger(stage)
      -> runtimeState.markTriggered()
      -> tryScheduleCoordinator(stage)
```

要求：

1. `triggerXxx()` 必须是快速返回方法。
2. 同一个 stage 连续触发时，不重复创建多个 coordinator。
3. 若该 stage 已有 coordinator 或仍有 work unit 在执行，只记录触发状态即可。

### 10.3 Coordinator 时序

职责：只拉任务、只投递，不做业务处理。

```text
StageDispatcher.tryScheduleCoordinator(stage)
  -> 判断 StageRuntimeState 是否允许调度
  -> 从 StagePolicyRegistry 读取当前策略
  -> sharedExecutor.submit(coordinator::dispatchOnce)

StageCoordinator.dispatchOnce()
  -> 读取 stage policy
  -> 计算本轮可投递容量
  -> claim 一小批任务
  -> 调用 WorkUnitFactory 生成 work units
  -> 循环调用 WorkUnitExecutor.submit(...)
  -> coordinator 结束
```

要求：

1. coordinator 不得调用 `join()` 等待 work unit。
2. coordinator 不得直接处理业务实体。
3. coordinator 一次只拉“当前 permit 能容纳”的那一小批任务。

### 10.4 WorkUnit 执行时序

职责：承载业务执行上下文，由调度层统一调度。

```text
WorkUnitExecutor.submit(workUnit)
  -> sharedExecutor 执行 workUnit.execute()
    -> handler.handle(...)
    -> finally:
         StageDispatcher.onWorkUnitCompleted(stage)
```

建议要求：

1. `WorkUnit.execute()` 内部不直接访问调度框架状态。
2. `WorkUnit` 只持有最小必要业务参数。
3. 结果汇总、日志、状态回写都在 handler 内完成。

### 10.5 模型调用时序

模型调用必须走统一门面，不允许业务代码自己控制 permit。

```text
Handler.handle(...)
  -> ModelCallGuard.call(stage, callable)
    -> ModelConcurrencyLimiter.acquire(stage)
    -> 调用真实模型服务
    -> ModelConcurrencyLimiter.release(stage)
```

约束：

1. `SummaryAgent`、`StructuredFactRefinementServiceImpl`、`LlmEventExtractorServiceImpl`、`InfectionJudgeServiceImpl`
   不直接感知并发 permit。
2. 所有模型并发统计、限流、监控都放在 `ModelCallGuard` / `ModelConcurrencyLimiter`。
3. 后续如果要拆分不同模型通道，也只需要替换 guard/limiter 实现。

### 10.6 Completion 回调时序

work unit 执行完后，由调度层决定是否继续补投。

```text
WorkUnit 完成
  -> StageDispatcher.onWorkUnitCompleted(stage)
    -> runtimeState.decrementInFlight()
    -> 如有需要，释放 stage permit
    -> 检查该 stage 是否仍有待处理触发或 backlog
    -> tryScheduleCoordinator(stage)
```

设计意图：

1. 不依赖 coordinator 常驻。
2. 通过 completion 驱动“持续补投”。
3. 保证共享线程池不会被少数长任务 coordinator 长时间占住。

### 10.7 普通时段调用链

适用于：

- `enqueuePendingPatients`
- `processPendingCollectTasks`
- `processPendingEventTasks`
- `processPendingCaseTasks`

```text
trigger
  -> coordinator
    -> claim batch
      -> submit work units
        -> handler
          -> optional model guard
            -> completion callback
              -> 补投下一轮
```

其中：

- `LOAD_ENQUEUE` 通常是轻量单任务
- `LOAD_PROCESS` 是多 work unit 并发
- `EVENT_EXTRACT` 是中等重量模型任务
- `CASE_RECOMPUTE` 是轻量模型尾部任务

### 10.8 `NORMALIZE` 优先窗口调用链

适用于每天 `0点 / 12点` 或 `NORMALIZE backlog` 很大时。

```text
triggerNormalize()
  -> dispatcher 读取窗口策略
    -> NormalizeCoordinator.dispatchOnce()
      -> claim reqno 分组
      -> 每个 reqno 分组生成一个 Normalize WorkUnit
      -> submit 到 sharedExecutor
        -> NormalizeHandler.handle(taskGroup)
          -> 多次模型调用均经 ModelCallGuard
        -> completion callback
          -> 若 backlog 仍高，继续补投 NormalizeCoordinator
```

设计意图：

1. `NORMALIZE` 在窗口期拥有更高配额。
2. 单个 `reqno` 分组作为一个 work unit，避免患者内顺序被打散。
3. `ModelCallGuard` 统一控制多次模型调用，不在 handler 中手工抢锁。

## 11. 类间依赖约束

为了保证架构边界清晰，建议显式约束依赖方向。

### 11.1 允许的依赖方向

```text
Scheduler -> Facade -> Dispatcher -> Coordinator -> WorkUnitFactory -> WorkUnit -> Handler
Handler -> Domain Service
Handler -> ModelCallGuard
ModelCallGuard -> ModelConcurrencyLimiter
```

### 11.2 禁止的依赖方向

以下依赖应视为架构违规：

```text
Scheduler -> Handler
Scheduler -> Domain Service
Coordinator -> Handler 具体实现以外的线程池控制类
Handler -> sharedExecutor
Handler -> StageRuntimeState
Handler -> StageDispatcher
Domain Service -> Dispatcher
Agent / LLM Service -> StageRuntimeState
```

### 11.3 原则总结

1. 调度层向下依赖业务接口，但业务层不反向依赖调度层。
2. handler 只知道自己在处理业务，不知道自己运行在哪个线程池。
3. 模型限流通过 guard 注入，不侵入 agent/service 的实现语义。

## 12. 观测与监控建议

为了验证设计是否有效，建议在调度框架层统一输出指标。

### 12.1 Stage 维度

每个 stage 建议输出：

- 当前 `inFlight`
- 当前队列长度
- 本轮 claim 数
- 本轮提交数
- 本轮完成数
- 本轮跳过数
- 最近一次触发时间
- 最近一次完成时间

### 12.2 模型维度

建议输出：

- `global model permits` 总数
- 当前已占用 permit 数
- 当前等待模型 permit 的 work unit 数
- 按 stage 统计模型调用次数
- 按 stage 统计模型调用耗时

### 12.3 线程池维度

建议输出：

- `sharedExecutor.activeCount`
- `sharedExecutor.queueSize`
- 各优先级任务数量
- 平均等待执行时长

### 12.4 告警建议

建议在以下场景加告警：

1. 某 stage 长时间 `triggerPending=true` 但没有补投成功。
2. 模型 permit 长时间满载且 `CASE_RECOMPUTE` 完全饥饿。
3. `NORMALIZE` 窗口期 backlog 持续升高。
4. `sharedExecutor` 队列长度连续超阈值。

## 13. 实施拆解清单

本节用于指导后续按阶段实施，避免一次性大改导致风险过高。

建议按 6 个迭代步骤推进，每一步都应保持：

1. 可编译
2. 可回滚
3. 可观测
4. 不同时引入“架构迁移 + 业务重写”两类风险

### 13.1 Step 1: 引入调度抽象骨架

目标：

- 先把调度框架的接口和基础对象建立起来。
- 不改现有业务路径。

新增类建议：

- `PipelineStage`
- `ResourceProfile`
- `StagePolicy`
- `StagePolicyRegistry`
- `StageRuntimeState`
- `StageRuntimeRegistry`
- `WorkUnit`
- `WorkUnitExecutor`
- `StageDispatcher`
- `ModelConcurrencyLimiter`
- `ModelCallGuard`
- `InfectionPipelineFacade`

本阶段不做的事：

- 不替换现有 `InfectionPipeline`
- 不迁移现有业务方法
- 不修改 scheduler 逻辑

验收标准：

1. 所有新类能通过编译。
2. 现有功能路径完全不受影响。
3. 调度抽象可以被 Spring 正常装配，但不接管真实流量。

回归验证点：

- 项目编译通过
- 应用启动正常
- 原有定时任务行为不变

### 13.2 Step 2: 将 Scheduler 改为 trigger-only

目标：

- 让 3 个 scheduler 从“执行业务”变为“触发阶段”。
- 但此时 facade 仍可以临时转发到原有 `InfectionPipeline` 逻辑，先不切断业务。

需要修改的现有类：

- `InfectionMonitorScheduler`
- `StructDataFormatScheduler`
- `SummaryWarningScheduler`

改造方式：

1. scheduler 只依赖 `InfectionPipelineFacade`
2. scheduler 方法体不再直接调业务方法
3. facade 内部可以先做兼容转发

建议兼容方式：

```text
Scheduler -> Facade.triggerXxx() -> 先临时调用旧逻辑
```

目的：

- 先统一入口
- 再逐步替换内部实现

验收标准：

1. scheduler 层不再依赖具体业务 handler/service。
2. 所有 trigger 方法都能通。
3. 业务结果与改造前一致。

回归验证点：

- 触发日志是否正常
- 原有定时任务成功/失败日志是否仍可见
- 无重复触发或漏触发

### 13.3 Step 3: 落地 StageDispatcher 与 Coordinator 骨架

目标：

- 让 facade 不再直接转旧业务，而是走统一 dispatcher。
- coordinator 先能运行，但允许内部仍临时调用旧入口。

新增类建议：

- `LoadEnqueueCoordinator`
- `LoadProcessCoordinator`
- `NormalizeCoordinator`
- `EventExtractCoordinator`
- `CaseRecomputeCoordinator`
- `WorkUnitFactory`

本阶段允许的过渡方式：

- coordinator 内部暂时仍可调用旧的 `InfectionPipeline` 兼容方法
- 但不允许直接自己做线程池并发控制

本阶段重点：

1. `trigger -> dispatcher -> coordinator` 链路跑通
2. `StageRuntimeState` 能阻止重复调度
3. `coordinatorScheduled` / `triggerPending` 行为可观测

验收标准：

1. 同一 stage 多次触发时不会重复提交多个 coordinator。
2. dispatcher 能按 stage 找到对应 coordinator。
3. coordinator 执行失败不会把 runtime state 卡死。

回归验证点：

- 同一 stage 连续触发压力测试
- coordinator 异常后是否还能继续触发下一轮
- dispatcher 日志是否能看到状态变化

### 13.4 Step 4: 拆出 Handler，迁移业务逻辑

目标：

- 把当前 `InfectionPipeline` 内部的业务逻辑拆到 handler 层。
- 此时仍可不完全启用共享池调度，只先完成职责分离。

建议迁移的方法：

- `processCollectTask(...)` -> `LoadProcessHandler`
- `processStructTask(...)` -> `NormalizeHandler`
- `processEventTask(...)` -> `EventExtractHandler`
- `processCaseTask(...)` -> `CaseRecomputeHandler`
- `finalizeCollectTask(...)` -> `LoadProcessHandler` 或结果后处理组件
- `finalizeStructTask(...)` -> `NormalizeHandler` 或结果后处理组件
- `finalizeEventTask(...)` -> `EventExtractHandler` 或结果后处理组件
- `finalizeCaseTask(...)` -> `CaseRecomputeHandler` 或结果后处理组件

建议同时新增：

- `LoadEnqueueHandler`
- 各类 `Result` 对象

本阶段要求：

1. handler 中禁止直接依赖线程池
2. handler 中禁止感知调度状态
3. handler 只返回结果，不做调度决定

验收标准：

1. `InfectionPipeline` 里的业务处理逻辑显著收缩
2. handler 可以独立单测
3. 业务结果与迁移前保持一致

回归验证点：

- 采集任务成功/失败路径
- 结构化任务成功/失败路径
- 事件抽取成功/失败路径
- 病例重算成功/失败/重排路径

### 13.5 Step 5: 用 WorkUnit 替换旧的批量并发执行

目标：

- 正式移除 `executeParallelTasks(...)`
- coordinator 改为 claim 小批量任务，并通过 `WorkUnitExecutor` 投递
- 不再在业务层做 `submit + join`

关键改动：

1. 删除或废弃旧的 `executeParallelTasks(...)`
2. `processPendingRawDataTasks()`、`processPendingStructData()`、`processPendingEventData()`、`processPendingCaseData()` 不再作为主执行入口
3. coordinator 负责：
   - 读取 policy
   - 计算剩余可投递容量
   - claim 小批任务
   - 转成 work units
   - 投递后立即返回

4. `WorkUnit.execute()` 完成后统一回调 `StageDispatcher.onWorkUnitCompleted(stage)`

本阶段是整个重构的核心切换点。

验收标准：

1. 共享线程池中不再出现“外层任务等待内层任务”的结构。
2. stage 的 `inFlight` 数与实际活跃任务一致。
3. backlog 能通过 completion 回调持续补投。

回归验证点：

- 高并发触发时无死锁
- 队列中有长任务时短任务仍可推进
- 同池下无明显线程饥饿

### 13.6 Step 6: 接入统一模型限流与动态策略

目标：

- 让 `format / event / case` 三类模型任务共用统一模型 permit。
- 引入普通时段和 `NORMALIZE` 优先窗口策略切换。

本阶段改动：

1. 所有模型调用接入 `ModelCallGuard`
2. `ModelConcurrencyLimiter` 正式启用
3. `StagePolicyRegistry` 支持动态策略
4. 监控和告警补齐

需要接入的模型调用点：

- `SummaryAgent.extractDailyIllness(...)`
- `StructuredFactRefinementServiceImpl.refine(...)`
- `LlmEventExtractorServiceImpl.extractAndSave(...)`
- `InfectionJudgeServiceImpl.judge(...)`

验收标准：

1. 模型调用并发数受统一配置约束。
2. `NORMALIZE` 窗口期能明显拿到更多调度配额。
3. `CASE_RECOMPUTE` 不会在模型高峰期永久饥饿。

回归验证点：

- 模型 permit 满载时是否仍稳定
- 普通时段和窗口时段策略切换是否生效
- 模型错误/超时后 permit 是否正确释放

当前状态（2026-04-09）：

- 已完成：`format / event / case` 三类模型调用已经统一接入 `ModelCallGuard`
- 已完成：`InfectionPipelineFacade` 已收敛为具体类，删除 `DefaultInfectionPipelineFacade` 过渡命名
- 已完成：共享池线程数 `shared-threads=16` 和 `model-permits=12` 已在所有 YAML 环境中显式配置
- 已完成：清理 `worker-threads` 遗留配置（YAML + Properties），统一由 `shared-threads` 管控
- 已完成：`StagePolicyRegistry` 已实现双策略表 + 时间窗口动态切换（普通时段 / NORMALIZE 优先窗口）
- 已完成：普通时段 `EVENT_EXTRACT maxInFlight=8` 为 LLM 主消费者；NORMALIZE 窗口期 `NORMALIZE maxInFlight=8` 集中处理
- 未完成：当前共享池仍是严格优先级队列，`CASE_RECOMPUTE` 仍缺少显式反饥饿机制（窗口期已通过 maxInFlight=1 保底缓解）
- 未完成：调度指标、模型指标、线程池指标和告警尚未补齐
- 未完成：文档第 16 节列出的单元测试、集成测试、压测场景尚未落地

说明：

- 本步骤里的 `ModelConcurrencyLimiter`、`ResourceProfile`、`WorkUnitFactory` 属于设计阶段草案。
- 当前代码已按第 18 节抽象治理规则收敛，未继续保留这些单独类型；后续如确有第二实现或真实变化点，再决定是否恢复独立抽象。

## 14. 各步骤涉及的文件改动范围建议

本节用于控制每一步的改动范围，避免过大 PR。

### 14.1 Step 1 文件范围

建议只新增：

- 新的 `pipeline/facade`
- 新的 `pipeline/scheduler`

不改：

- `task/*`
- `ai/orchestrator/InfectionPipeline.java`
- `service/impl/*`

### 14.2 Step 2 文件范围

建议修改：

- `task/InfectionMonitorScheduler.java`
- `task/StructDataFormatScheduler.java`
- `task/SummaryWarningScheduler.java`

建议新增：

- facade 实现类

### 14.3 Step 3 文件范围

建议新增：

- `pipeline/stage/*`

建议少量修改：

- facade 实现类
- dispatcher 相关配置类

### 14.4 Step 4 文件范围

建议新增：

- `pipeline/handler/*`
- `pipeline/model/*`

建议修改：

- `ai/orchestrator/InfectionPipeline.java`

但此时尽量只做“搬迁”，不做大规模逻辑重写。

### 14.5 Step 5 文件范围

重点修改：

- `ai/orchestrator/InfectionPipeline.java`
- `pipeline/stage/*`
- `pipeline/scheduler/*`

此阶段是移除旧并发模型的关键提交。

### 14.6 Step 6 文件范围

重点修改：

- `SummaryAgent`
- `StructuredFactRefinementServiceImpl`
- `LlmEventExtractorServiceImpl`
- `InfectionJudgeServiceImpl`
- `pipeline/scheduler/ModelCallGuard*`
- `pipeline/scheduler/ModelConcurrencyLimiter*`

## 15. 每一步的回滚策略

为了降低上线风险，建议每一步都预留独立回滚路径。

### 15.1 Step 1 回滚

- 新增类不接流量，直接回退提交即可。

### 15.2 Step 2 回滚

- scheduler 从 facade 改回直接调用旧 `InfectionPipeline`。

### 15.3 Step 3 回滚

- facade 暂时绕过 dispatcher，直接走旧业务入口。

### 15.4 Step 4 回滚

- handler 保留，但由 facade/旧入口重新回退到老实现。

### 15.5 Step 5 回滚

- 保留旧批量执行入口一段时间，必要时切回旧模式。
- 这是最需要 feature flag 的步骤。

建议增加开关示例：

```text
infection.pipeline.scheduler.enabled=false
```

### 15.6 Step 6 回滚

- 关闭统一模型 guard，恢复原始模型调用路径。
- 或把 `globalModelPermits` 放宽到接近无限，先降级观察。

## 16. 推荐测试清单

### 16.1 单元测试

建议覆盖：

1. `StageRuntimeState`
2. `StageDispatcher`
3. `StagePolicyRegistry`
4. `ModelConcurrencyLimiter`
5. 各 handler 的成功/失败路径

### 16.2 集成测试

建议覆盖：

1. scheduler 触发到 facade
2. facade 到 dispatcher
3. dispatcher 到 coordinator
4. coordinator claim 后投递 work unit
5. work unit 执行完成后 completion 补投

### 16.3 压测场景

建议压以下场景：

1. `EVENT_EXTRACT` 高并发积压
2. `NORMALIZE` 窗口期批量启动
3. `CASE_RECOMPUTE` 在模型满载场景下是否仍能推进
4. 模型服务超时/失败时 permit 是否泄漏
5. 数据库连接池紧张时线程池是否出现级联阻塞

## 17. 最终收敛目标

完成全部改造后，应达到以下状态：

1. Scheduler 完全 trigger-only。
2. 共享线程池调度逻辑统一收敛在 `pipeline.scheduler`。
3. 阶段 claim 和投递逻辑统一收敛在 `pipeline.stage`。
4. 业务执行逻辑统一收敛在 `pipeline.handler`。
5. 模型调用并发由 `ModelCallGuard` 统一控制。
6. `InfectionPipeline` 不再承担“大而全”的 orchestrator 职责，可逐步退化为兼容 facade 或删除。

## 18. 抽象治理与本轮收敛清单

说明：

- 本文前文包含设计阶段的示意代码、过渡态命名和历史计划。
- 如果前文示意与 `18.7`、`18.8` 的当前落地状态冲突，以 `18.7`、`18.8` 和当前代码实现为准。

### 18.1 抽象准入规则

后续新增 `interface`、`abstract class`、`*Registry`、`*Factory` 之前，必须先写清楚它要隔离的变化点，并且满足以下条件之一：

1. 当前迭代或下一迭代内，确定会出现第二个实现。
2. 该抽象用于隔离外部边界，例如模型调用、线程调度、持久化、第三方 SDK，便于替换、限流、测试或监控。
3. 已经出现 3 处以上稳定重复逻辑，且可以沉淀为统一模板或契约。

如果三条都不满足，则默认先使用具体类；等第二实现或稳定重复真正出现后，再进行抽象。

### 18.2 本轮优先收敛的薄抽象

以下旧形式如果仍然只有单实现，且主要起到“命名转发”作用，应优先收敛：

- `Handler` 接口 + `Default*Handler` 实现
- `StageCoordinatorRegistry` + `DefaultStageCoordinatorRegistry`

收敛原则：

- Handler 层不再采用“接口 + Default 实现”双类壳结构。
- 统一保留具体职责类，类名直接使用业务名，例如 `LoadEnqueueHandler`、`NormalizeHandler`。
- 如果多个 Handler 存在稳定共性，优先抽取一个抽象模板基类或组合组件，不再为每个 Handler 保留单独接口。

### 18.3 Handler / Coordinator 层统一抽象方式

Handler 层后续统一采用“抽象模板 + 具体职责类”模式：

- 抽象层只承载稳定共性，例如异常包装、结果回写模板、日志骨架。
- 具体类只承载该 stage 独有的业务流程。
- 不允许同时存在“单实现接口”和“模板基类”两套抽象。

Coordinator 层后续统一采用“调度模板父类 + 具体 stage 类”模式：

- claim、batch size、trigger 状态更新、work unit 提交等稳定重复逻辑，应下沉到父类模板。
- 具体 coordinator 只保留 stage 标识、claim 来源、work item 构造、handler 调用。
- 不为 coordinator 再增加 registry 式转发包装。

### 18.4 当前可以保留的边界抽象

以下抽象当前仍然有保留价值，可以继续存在，但要确保后续真正承接变化点：

- `ModelCallGuard`
- `AiGateway`
- `WorkUnit`

保留原因：

- 它们隔离的是模型调用、调度执行单元等外部或跨层边界，不只是简单命名包装。

### 18.5 观察项

以下抽象在“动态策略、监控、可替换实现”真正落地前，暂定为观察项：

- `StageDispatcher`
- `StagePolicyRegistry`
- `StageRuntimeRegistry`
- `WorkUnitExecutor`

判断原则：

- 如果后续确实承接了多实现、动态策略或对外稳定边界，则保留。
- 如果后续仍然只有单实现且不承担真实变化点，则继续收敛为具体类。

已收敛的观察项：

- `InfectionPipelineFacade`：已从"接口 + DefaultInfectionPipelineFacade"收敛为具体类

### 18.6 命名收口

- facade 实现类应直接使用正式主入口名称，禁止保留 `LegacyCompatible`、`Default` 之类过渡态命名。
- `InfectionPipelineFacade` 已完成收口，当前为具体类。

### 18.7 已完成的 `pipeline.scheduler` 收敛

当前 `pipeline.scheduler` 已按职责拆成 4 个子包：

- `executor`
- `limiter`
- `policy`
- `runtime`

本轮已完成的收敛包括：

- 删除 `StagePolicyRegistry + DefaultStagePolicyRegistry` 双层壳，统一为具体类 `StagePolicyRegistry`
- 删除 `StageRuntimeRegistry + InMemoryStageRuntimeRegistry` 双层壳，统一为具体类 `StageRuntimeRegistry`
- 删除 `StageDispatcher + DefaultStageDispatcher` 双层壳，统一为具体类 `StageDispatcher`
- 删除 `WorkUnitExecutor + DefaultWorkUnitExecutor` 双层壳，统一为具体类 `WorkUnitExecutor`
- 删除 `WorkUnit + RunnableWorkUnit` 双层壳，统一为具体类 `WorkUnit`
- 删除 `ModelCallGuard + DefaultModelCallGuard + ModelConcurrencyLimiter + SemaphoreModelConcurrencyLimiter` 的多层包装，统一收敛为 `ModelCallGuard`

同时，以下未进入真实运行链路的调度元数据已删除：

- `ResourceProfile`
- `StagePolicy.singleCoordinator`
- `StagePolicyRegistry.currentPolicies()`
- `StagePolicyRegistry.isNormalizePreferredWindow()`

收敛后的原则是：

- `scheduler` 内只保留真正独立的调度概念，不保留“单实现接口 + 默认实现”或“命名转发”包装。
- 如果后续动态策略、监控或多实现没有真实落地，`StageDispatcher / StagePolicyRegistry / StageRuntimeRegistry / WorkUnitExecutor` 仍应继续接受收敛审查。

### 18.8 已完成的 `domain.normalize` 收敛

当前 `domain.normalize` 已按职责拆成 6 个子包：

- `assemble`
- `facts`
- `facts.candidate`
- `facts.support`
- `prompt`
- `support`
- `validation`

本轮已完成的收敛包括：

- 删除 `DayFactsBuilder`、`FusionFactsBuilder`、`TimelineEntryBuilder` 等 root 包单实现接口，统一改为具体职责类
- 删除 `NormalizeNoteStructAssembler`、`NormalizeOutputValidator`、`NormalizeRetryInstructionBuilder` 等“接口 + Default 实现”双层壳，统一改为具体职责类
- 删除 `NotePreparationService`、`NoteTypePriorityResolver`、`ProblemCandidateBuilder`、`RiskCandidateBuilder` 等单实现接口，统一改为具体职责类
- 保留 `NormalizePromptCatalog`、`NormalizePromptDefinition`、`NormalizeValidation*` 等提示词与校验元数据，但迁入独立子包，避免与业务执行类混放
- `facts.support.DailyFusionInputCompactor` 作为具体职责类处理 daily fusion 输入长度预算；短输入不裁剪，超预算后才分级压缩重复来源、医嘱冗余和低优先级候选

`facts.candidate` 中新增的 `AbstractStructuredNoteFactsBuilder` 属于合规抽象，因为它承接了 `ProblemCandidateBuilder`、`RiskCandidateBuilder`、`FusionFactsBuilder` 三处稳定重复的“结构化 note 读取”逻辑，满足“3 处以上稳定重复才抽象”的准入条件。

收敛后的原则是：

- `normalize` 领域优先按职责拆目录，不再把 assembler、prompt、validator、facts builder、support helper 混放在一个平面包中。
- 单实现类直接用业务职责命名，不保留 `Default*` 前缀。
- 只有跨 3 处以上的稳定重复逻辑，才允许抽取父类模板。

### 18.9 文档同步要求

后续凡是涉及包结构调整、抽象收敛、命名收口的重构，必须同步更新以下文档：

- `docs/shared-executor-architecture-design.md`
- `docs/refactor-handover-status.md`
- `README.md`
- `AGENTS.md`

### 18.10 尚未完成的规划任务

截至 2026-04-09，本文档规划中仍未完成的任务如下：

1. ~~动态策略切换~~ **已完成**
   - `StagePolicyRegistry` 已实现基于时间窗口的双策略表切换
   - 配置项：`normalize-window-start-hours` / `normalize-window-duration-hours`
   - 后续可扩展基于 backlog 数量的自适应切换

2. `CASE_RECOMPUTE` 反饥饿保证
   - 当前 NORMALIZE 窗口期 `CASE_RECOMPUTE maxInFlight=1` 做保底，但普通时段无显式反饥饿
   - 后续如需进一步保证，可引入 aging 或独立补投阈值

3. 调度观测与告警
   - 需要补齐第 12 节中定义的 stage 维度、模型维度、线程池维度指标
   - 需要补齐 backlog、permit 满载、补投失败等告警

4. 测试补齐
   - 需要补齐 `StageRuntimeState`、`StageDispatcher`、`StagePolicyRegistry`、handler 成功/失败路径单测
   - 需要补齐 scheduler -> facade -> dispatcher -> coordinator -> work unit 的集成测试
   - 需要补齐策略切换窗口期前后的行为验证

5. 文档草案与当前实现的差异清理
   - 前文仍保留 `ModelConcurrencyLimiter`、`ResourceProfile`、`WorkUnitFactory` 等草案描述
   - 后续如不计划恢复这些抽象，应继续逐段清理前文历史草案，避免读者把草案误认为当前实现

6. 线程池与 LLM 并发调优验证
   - 当前 prod 配置 `shared-threads=16`、`model-permits=12`，基于 vLLM PP 模式 12 并发 40 秒的实测数据
   - 需要补测 8/10/14/16 并发的吞吐曲线，确认 PP 甜点区上界
   - 需要验证 NORMALIZE 窗口切换前后 LLM 吞吐是否平稳过渡
