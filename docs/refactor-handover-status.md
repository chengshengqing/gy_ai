# Refactor Handover Status

日期：2026-04-09

这份文档用于在新 chat 窗口继续当前重构任务。

当前代码已通过编译验证：

```bash
./mvnw -q -DskipTests compile
```

## 1. 当前主链路

### 1.1 共享线程池调度主链路

当前 3 个调度任务已经统一走共享线程池主链路：

`@Scheduled -> InfectionPipelineFacade -> StageDispatcher -> StageCoordinator -> WorkUnitExecutor -> Handler`

对应代码：

- `task`
  - `InfectionMonitorScheduler`
  - `StructDataFormatScheduler`
  - `SummaryWarningScheduler`
- `pipeline.facade`
  - `InfectionPipelineFacade`（具体类，已删除接口 + Default 实现双层壳）
- `pipeline.scheduler`
  - `executor.StageDispatcher`
  - `executor.WorkUnitExecutor`
  - `executor.WorkUnit`
  - `policy.PipelineStage`
  - `policy.StagePolicy`
  - `policy.StagePolicyRegistry`（双策略表 + 时间窗口动态切换）
  - `runtime.StageRuntimeRegistry`
  - `limiter.ModelCallGuard`
- `pipeline.stage`
  - `StageCoordinator`
  - `AbstractStageCoordinator`
  - `AbstractClaimingStageCoordinator`
  - `AbstractSingleItemStageCoordinator`
  - `LoadEnqueueCoordinator`
  - `LoadProcessCoordinator`
  - `NormalizeCoordinator`
  - `EventExtractCoordinator`
  - `CaseRecomputeCoordinator`
- `pipeline.handler`
  - `AbstractTaskHandler`
  - `LoadEnqueueHandler`
  - `LoadProcessHandler`
  - `NormalizeHandler`
  - `NormalizeStructDataComposer`
  - `EventExtractHandler`
  - `CaseRecomputeHandler`

### 1.2 模型调用限流主链路

当前真实模型调用已经统一通过 gateway 与 guard 收口：

`Handler / Service -> *ModelGateway -> ModelCallGuard -> Spring AI / vLLM`

对应代码：

- `ai.gateway`
  - `NormalizeModelGateway`
  - `FormatModelGateway`
  - `WarningModelGateway`
  - `SpringAiNormalizeModelGateway`
  - `SpringAiFormatModelGateway`
  - `SpringAiWarningModelGateway`
- `pipeline.scheduler.limiter`
  - `ModelCallGuard`

### 1.3 Normalize 业务主链路

`NORMALIZE` 当前主链路已经收口到：

`NormalizeCoordinator -> NormalizeHandler -> NormalizeRowProcessor -> NormalizeStructDataComposer -> domain.normalize.* -> NormalizeModelGateway`

`domain.normalize` 当前已拆分为：

- `assemble`
  - `NormalizeNoteStructAssembler`
  - `NormalizeNoteStructureResult`
  - `IllnessCourseTimeResolver`
- `facts`
  - `DayFactsBuilder`
  - `FusionFactsBuilder`
  - `TimelineEntryBuilder`
  - `DailyIllnessExtractionResult`
- `facts.candidate`
  - `AbstractStructuredNoteFactsBuilder`
  - `ProblemCandidateBuilder`
  - `RiskCandidateBuilder`
- `prompt`
  - `NormalizePromptCatalog`
  - `NormalizePromptDefinition`
  - `NormalizePromptType`
  - `NormalizePromptValidationSpec`
  - `NormalizeEnumFieldRule`
- `support`
  - `NotePreparationSupport`
  - `NoteTypePriorityResolver`
- `validation`
  - `NormalizeOutputValidator`
  - `NormalizePromptOutputValidator`
  - `NormalizeRetryInstructionBuilder`
  - `NormalizeAttemptReport`
  - `NormalizeValidatedResult`
  - `NormalizeValidationIssue`
  - `NormalizeValidationResult`

本轮已新增：

- `pipeline.handler.NormalizeStructDataComposer`

当前分工：

- `NormalizeRowProcessor` 仅保留 reset derived data、调用 composer、结果回写、错误包装
- `NormalizeStructDataComposer` 负责输入 JSON 选择、note 结构化、fusion 调用、`struct_data_json` / `event_json` 组装

### 1.4 Format 业务主链路

`FormatAgent` 当前已收口到：

`FormatAgent -> domain.format.FormatContextComposer -> domain.format.FormatSectionFormatter -> FormatModelGateway`

`domain.format` 当前已拆分为：

- `FormatContextComposer`
- `FormatSectionFormatter`
- `FinalMergePromptBuilder`
- `FilteredRawDataBuilder`

当前分工：

- `FormatAgent` 仅保留兼容入口并转发到 `domain.format.*`
- `FormatContextComposer` 负责编排输入拆分、病程去重、section 合并与 `PatientContext` 构造
- `FormatSectionFormatter` 负责 section 级格式化与 `FormatModelGateway` 调用
- `FinalMergePromptBuilder` 负责最终 merge prompt 组装
- `FilteredRawDataBuilder` 负责过滤后的 `filter_data_json` 构造

### 1.5 StructuredFactRefinement 业务主链路

`StructuredFactRefinementServiceImpl` 当前已收口到：

`StructuredFactRefinementServiceImpl -> service.evidence.StructuredFactRefinementSupport`

当前分工：

- `StructuredFactRefinementServiceImpl` 仅保留入口编排、模型调用、审计落库与失败降级
- `StructuredFactRefinementSupport` 负责 refinement input、候选构造、输出校验和 priority/reference facts 应用规则
- 这一步业务语义属于 evidence 预处理，不再扩张 `domain.event`

### 1.6 LlmEventExtractor 业务主链路

`LlmEventExtractorServiceImpl` 当前已收口到：

`LlmEventExtractorServiceImpl -> service.event.LlmEventExtractionSupport`

当前分工：

- `LlmEventExtractorServiceImpl` 仅保留逐 block 编排、模型调用、事件归一化、事件池写入和审计落库
- `LlmEventExtractionSupport` 负责 block 输入组装、模型输出规范化、聚合 JSON、运行载荷和错误摘要
- 这一步业务语义属于事件抽取用例，不进入 `domain`，也不混入 `service.evidence`

### 1.7 InfectionJudge 业务主链路

`InfectionJudgeServiceImpl` 当前已收口到：

`InfectionJudgeServiceImpl -> service.casejudge.InfectionCaseJudgeSupport`

当前分工：

- `InfectionJudgeServiceImpl` 仅保留模型调用、裁决入口编排、审计落库与 fallback 降级
- `InfectionCaseJudgeSupport` 负责 payload 序列化、裁决输出解析、fallback 构造、运行载荷和裁决护栏
- 这一步业务语义属于病例裁决用例，不进入 `domain`

## 2. 本轮已完成的结构收敛

### 2.1 `pipeline.scheduler`

`pipeline.scheduler` 已从平铺目录收敛为：

- `executor`
- `limiter`
- `policy`
- `runtime`

本轮已删除或收敛的薄抽象：

- `DefaultStagePolicyRegistry`
- `InMemoryStageRuntimeRegistry`
- `DefaultStageDispatcher`
- `DefaultWorkUnitExecutor`
- `RunnableWorkUnit`
- `DefaultModelCallGuard`
- `ModelConcurrencyLimiter`
- `SemaphoreModelConcurrencyLimiter`
- `InfectionPipelineFacade`（接口）+ `DefaultInfectionPipelineFacade` -> 收敛为具体类 `InfectionPipelineFacade`

同时已删除未进入真实调度链路的无效元数据：

- `ResourceProfile`
- `StagePolicy.singleCoordinator`
- `StagePolicyRegistry.currentPolicies()`
- `StagePolicyRegistry.isNormalizePreferredWindow()` → 已恢复，用于时间窗口动态策略切换

### 2.2 `domain.normalize`

`domain.normalize` 已从单层平铺目录收敛为 6 个职责子包。

本轮已删除的单实现薄抽象包括：

- `DayFactsBuilder` root 接口
- `FusionFactsBuilder` root 接口
- `TimelineEntryBuilder` root 接口
- `NormalizeNoteStructAssembler` root 接口
- `NormalizeOutputValidator` root 接口
- `NormalizeRetryInstructionBuilder` root 接口
- `NotePreparationService`
- `NoteTypePriorityResolver` root 接口
- `ProblemCandidateBuilder` root 接口
- `RiskCandidateBuilder` root 接口

当前 `facts.candidate.AbstractStructuredNoteFactsBuilder` 是唯一新增父类模板，用于统一 3 处以上稳定重复的结构化 note 读取逻辑，符合抽象治理准则。

### 2.3 `NormalizeRowProcessor` 收薄

本轮已完成的收敛：

- 将 `extractDailyIllness(...)` 从 `NormalizeRowProcessor` 迁移到新的具体类 `NormalizeStructDataComposer`
- 将 `resolveRawInputJson(...)`、`parseToNode(...)`、`toJson(...)` 收敛为 `NormalizeStructDataComposer` 私有实现
- 保持 `NormalizeRowProcessor` 为 handler 级 orchestration，不新增接口或 `Default*` 壳

### 2.4 `FormatAgent` 收薄

本轮已完成的收敛：

- 新增 `domain.format` 包，按职责拆分为 `FormatContextComposer`、`FormatSectionFormatter`、`FinalMergePromptBuilder`、`FilteredRawDataBuilder`
- 将 `FormatAgent.format(...)` 的业务编排迁移到 `FormatContextComposer`
- 将 section 格式化和 prompt 调用迁移到 `FormatSectionFormatter`
- 将 merge prompt 组装迁移到 `FinalMergePromptBuilder`
- 将 `filterIllnessCourse(...)` 迁移到 `FilteredRawDataBuilder`
- 保持 `FormatAgent` 为兼容入口，不新增接口或 `Default*` 壳

### 2.5 `StructuredFactRefinementServiceImpl` 收薄

本轮已完成的收敛：

- 新增 `service.evidence.StructuredFactRefinementSupport`
- 将 `StructuredFactRefinementServiceImpl` 的输入构造、候选筛选、输出校验、结果应用收口到该 support
- 修正模型输出无效项静默成功的问题，改为显式失败
- 修正 `markFailed(...)` 失败覆盖原始异常的问题
- 撤回了将业务 Spring 组件平铺进 `domain.event` 的方向

### 2.6 `LlmEventExtractorServiceImpl` 收薄

本轮已完成的收敛：

- 新增 `service.event.LlmEventExtractionSupport`
- 将 `LlmEventExtractorServiceImpl` 的输入组装、输出规范化、聚合 JSON、运行载荷和错误摘要收口到该 support
- 删除 `extractConfidence(...)`、`countRawEvents(...)` 等无效 helper
- 保持 `LlmEventExtractorServiceImpl` 为事件抽取用例入口，不新增接口或 `Default*` 壳

### 2.7 `InfectionJudgeServiceImpl` 收薄

本轮已完成的收敛：

- 新增 `service.casejudge.InfectionCaseJudgeSupport`
- 将 `InfectionJudgeServiceImpl` 的 payload 序列化、输出解析、fallback 构造、运行载荷和裁决护栏收口到该 support
- 修正 `markFailed(...)` 失败覆盖原始异常的问题
- 保持 `InfectionJudgeServiceImpl` 为病例裁决用例入口，不新增接口或 `Default*` 壳

## 3. 当前抽象治理状态

当前已经落实的规则：

- 不再保留“单实现接口 + Default 实现”的双层壳
- 具体主职责类直接使用业务名，不再使用 `Default*`
- 稳定重复逻辑优先收敛为模板父类
- 包结构按职责拆分，不再把 enum、record、helper、validator、service 混放到同一平面目录

当前仍可继续观察的点：

- `StageDispatcher`
- `StagePolicyRegistry`
- `StageRuntimeRegistry`
- `WorkUnitExecutor`
- `InfectionPipelineFacade`

判断原则：

- 如果后续确实承接动态策略、多实现或对外稳定边界，则保留
- 如果后续仍然只有单实现且不承担真实变化点，则继续收敛

## 4. 与文档同步要求

涉及结构重构时，必须同步更新：

- `docs/shared-executor-architecture-design.md`
- `docs/refactor-handover-status.md`
- `README.md`
- `AGENTS.md`

## 5. 当前未完成项

以下任务已在设计文档中重新标记为“未完成”，后续应优先补齐：

### 5.1 Step 6 未完成收尾

- `StagePolicyRegistry` 仍是固定策略，尚未支持普通时段与 `NORMALIZE` 优先窗口切换
- 当前共享池按固定优先级调度，`CASE_RECOMPUTE` 缺少显式反饥饿机制
- 调度指标、模型指标、线程池指标和告警尚未补齐
- 单元测试、集成测试、压测场景尚未补齐

### 5.2 草案与现状差异

以下内容仍存在于设计草案前文，但当前代码没有按草案原样落地：

- `ModelConcurrencyLimiter`
- `ResourceProfile`
- `WorkUnitFactory`

当前处理原则：

- 这些类型不是“漏实现”，而是按抽象治理规则被主动收敛掉了
- 若后续出现第二实现或真实变化点，再决定是否恢复独立抽象
