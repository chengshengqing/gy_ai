# 方法级重构映射清单

## 1. 文档目的

本文档基于 [shared-executor-architecture-design.md](/Users/chengshengqing/IdeaProjects/yg_ai/docs/shared-executor-architecture-design.md) 给出方法级别的重构映射建议。

目标：

1. 将现有代码迁移到 `Scheduler -> Facade -> Dispatcher -> Coordinator -> WorkUnit -> Handler` 的结构。
2. 明确哪些方法应该迁移，迁移到哪个新类。
3. 明确哪些方法必须删除，哪些可以保留，哪些只需要改名或搬迁。
4. 明确完成这些迁移后，是否可以适配共享线程池调度架构。

## 2. 适配结论

如果严格按本文档进行重构，代码可以适配共享线程池调度设计。

前提：

1. 所有 scheduler 只保留 trigger 职责。
2. `InfectionPipeline` 不再保留 `submit + join` 型并发执行逻辑。
3. 所有模型调用统一收口到 `ModelCallGuard`。
4. `SummaryAgent`、`FormatAgent` 这类“大而全”的 agent 被拆成：
   - handler 层
   - domain service 层
   - model gateway 层
5. 业务层不再直接依赖线程池、`CompletableFuture` 或 `ChatModel`。

## 3. 新目标分层

建议采用以下目标包结构：

```text
com.zzhy.yg_ai.task
com.zzhy.yg_ai.pipeline.facade
com.zzhy.yg_ai.pipeline.scheduler
com.zzhy.yg_ai.pipeline.stage
com.zzhy.yg_ai.pipeline.handler
com.zzhy.yg_ai.pipeline.model
com.zzhy.yg_ai.domain.normalize
com.zzhy.yg_ai.domain.event
com.zzhy.yg_ai.domain.casejudge
com.zzhy.yg_ai.domain.format
com.zzhy.yg_ai.ai.gateway
com.zzhy.yg_ai.ai.prompt
```

职责约束：

1. `task`
   - 只触发，不执行业务。

2. `pipeline.facade`
   - 只暴露 trigger 方法。

3. `pipeline.scheduler`
   - 只负责任务调度、优先级、permit、completion。

4. `pipeline.stage`
   - 只负责 claim 和 work unit 组装。

5. `pipeline.handler`
   - 只负责单个业务 work unit 的执行编排。

6. `domain.*`
   - 放业务规则、数据加工、结果组装、校验、解析。

7. `ai.gateway`
   - 只放模型调用适配器。

## 4. Scheduler 方法级映射

### 4.1 `InfectionMonitorScheduler`

| 现有方法 | 新归属 | 动作 | 说明 |
|---|---|---|---|
| `enqueuePendingPatients()` | `task.InfectionMonitorScheduler` | 保留方法名，改方法体 | 方法体只调用 `InfectionPipelineFacade.triggerLoadEnqueue()` |
| `processPendingCollectTasks()` | `task.InfectionMonitorScheduler` | 保留方法名，改方法体 | 方法体只调用 `InfectionPipelineFacade.triggerLoadProcess()` |

额外调整：

1. scheduler 中移除对 `PatientService`、`PatientRawDataCollectTaskService` 的直接依赖。
2. scheduler 中移除基于同步返回值的成功日志。

### 4.2 `StructDataFormatScheduler`

| 现有方法 | 新归属 | 动作 | 说明 |
|---|---|---|---|
| `formatPendingStructData()` | `task.StructDataFormatScheduler` | 保留方法名，改方法体 | 方法体只调用 `InfectionPipelineFacade.triggerNormalize()` |

### 4.3 `SummaryWarningScheduler`

| 现有方法 | 新归属 | 动作 | 说明 |
|---|---|---|---|
| `processPendingEventTasks()` | `task.SummaryWarningScheduler` | 保留方法名，改方法体 | 方法体只调用 `InfectionPipelineFacade.triggerEventExtract()` |
| `processPendingCaseTasks()` | `task.SummaryWarningScheduler` | 保留方法名，改方法体 | 方法体只调用 `InfectionPipelineFacade.triggerCaseRecompute()` |

## 5. `InfectionPipeline` 方法级映射

## 5.1 顶层入口方法

| 现有方法 | 新归属 | 动作 | 说明 |
|---|---|---|---|
| `enqueueRawDataTasks(...)` | `pipeline.handler.LoadEnqueueHandler` | 迁移 | scheduler 不再直接调用该方法 |
| `processPendingRawDataTasks()` | `pipeline.stage.LoadProcessCoordinator` | 拆分迁移 | claim + work unit 投递迁到 coordinator |
| `processPendingStructData()` | `pipeline.stage.NormalizeCoordinator` | 拆分迁移 | `reqno` 分组保留，但移到 coordinator |
| `processPendingEventData()` | `pipeline.stage.EventExtractCoordinator` | 拆分迁移 | claim + 投递迁到 coordinator |
| `processPendingCaseData()` | `pipeline.stage.CaseRecomputeCoordinator` | 拆分迁移 | claim + 投递迁到 coordinator |
| `resolveExecutorThreads(...)` | `pipeline.scheduler.WorkUnitExecutor` 或执行器配置类 | 迁移 | 与业务无关，属于调度配置 |

### 5.2 采集任务相关

| 现有方法 | 新归属 | 动作 | 说明 |
|---|---|---|---|
| `processCollectTask(...)` | `pipeline.handler.LoadProcessHandler` | 迁移 | 单任务执行逻辑 |
| `finalizeCollectTask(...)` | `pipeline.handler.LoadProcessHandler` 或 `LoadProcessResultApplier` | 迁移 | 状态回写属于业务结果处理，不属于 orchestrator |

### 5.3 结构化任务相关

| 现有方法 | 新归属 | 动作 | 说明 |
|---|---|---|---|
| `processStructTask(...)` | `pipeline.handler.NormalizeHandler` | 迁移 | 单个 `reqno` 组的执行编排 |
| `processPendingRawData(...)` | `pipeline.handler.NormalizeHandler` | 内聚到 handler 内部 | 可以拆成 `normalizeSingleRawData(...)` |
| `finalizeStructTask(...)` | `pipeline.handler.NormalizeHandler` 或 `NormalizeResultApplier` | 迁移 | 业务结果回写 |
| `extractStructTaskIds(...)` | `pipeline.handler.NormalizeHandler` 或 `pipeline.model.NormalizeWorkUnitPayload` | 迁移 | 仅服务结构化任务 |
| `resolveStructReqno(...)` | `pipeline.handler.NormalizeHandler` 或 `pipeline.model.NormalizeWorkUnitPayload` | 迁移 | 仅服务结构化任务 |
| `collectFreshRawData(List<...>)` | `pipeline.handler.NormalizeHandler` | 迁移 | 与结构化业务绑定 |

### 5.4 事件抽取任务相关

| 现有方法 | 新归属 | 动作 | 说明 |
|---|---|---|---|
| `processEventTask(...)` | `pipeline.handler.EventExtractHandler` | 迁移 | 单任务编排 |
| `extractEvents(...)` | `pipeline.handler.EventExtractHandler` | 迁移 | 或拆为内部 service |
| `finalizeEventTask(...)` | `pipeline.handler.EventExtractHandler` 或 `EventExtractResultApplier` | 迁移 | 业务结果回写 |
| `extractEventTaskIds(...)` | `pipeline.handler.EventExtractHandler` 或 `pipeline.model.EventExtractWorkUnitPayload` | 迁移 | 仅服务事件抽取 |
| `resolveEventReqno(...)` | `pipeline.handler.EventExtractHandler` 或 `pipeline.model.EventExtractWorkUnitPayload` | 迁移 | 仅服务事件抽取 |
| `collectFreshRawData(InfectionEventTaskEntity)` | `pipeline.handler.EventExtractHandler` | 迁移 | 与事件抽取业务绑定 |
| `selectPrimaryBlocks(...)` | `domain.event.EventPrimaryBlockSelector` | 迁移 | 这是领域规则，不是 orchestrator 逻辑 |
| `hasAny(...)` 两个重载 | `domain.event.EventPrimaryBlockSelector` | 迁移 | 只服务 primary block 筛选 |
| `filterByBlockType(...)` | `domain.event.EventPrimaryBlockSelector` | 迁移 | 同上 |

### 5.5 病例重算任务相关

| 现有方法 | 新归属 | 动作 | 说明 |
|---|---|---|---|
| `processCaseTask(...)` | `pipeline.handler.CaseRecomputeHandler` | 迁移 | 单任务编排 |
| `finalizeCaseTask(...)` | `pipeline.handler.CaseRecomputeHandler` 或 `CaseRecomputeResultApplier` | 迁移 | 业务结果回写 |
| `persistAlertResult(...)` | `pipeline.handler.CaseRecomputeHandler` 或 `domain.casejudge.AlertResultPersistenceService` | 迁移 | 与病例结果持久化绑定 |
| `updateSnapshot(...)` | `pipeline.handler.CaseRecomputeHandler` 或 `domain.casejudge.CaseSnapshotUpdater` | 迁移 | 与病例状态更新绑定 |
| `writeJson(...)` | 公共 JSON 工具类 | 迁移 | 不应留在 orchestrator |

### 5.6 并发执行相关

| 现有方法 | 新归属 | 动作 | 说明 |
|---|---|---|---|
| `executeParallelTasks(...)` | 删除 | 删除 | 和新调度设计冲突，必须删除 |

### 5.7 结果 record

| 现有 record | 新归属 | 动作 | 说明 |
|---|---|---|---|
| `StructTaskExecutionResult` | `pipeline.model.NormalizeResult` | 替换 | 使用统一结果对象 |
| `RawDataProcessResult` | `pipeline.model.NormalizeStepResult` | 替换 | 可保留更细粒度内部结果 |
| `EventTaskExecutionResult` | `pipeline.model.EventExtractResult` | 替换 | 使用统一结果对象 |
| `CaseTaskExecutionResult` | `pipeline.model.CaseRecomputeResult` | 替换 | 使用统一结果对象 |
| `RawCollectTaskExecutionResult` | `pipeline.model.LoadProcessResult` | 替换 | 使用统一结果对象 |

## 6. `SummaryAgent` 方法级映射

`SummaryAgent` 当前是“业务编排 + 领域规则 + 校验重试 + 模型调用”混合体，必须拆分。

建议拆出的目标类：

- `pipeline.handler.NormalizeHandler`
- `domain.normalize.NotePreparationService`
- `domain.normalize.DayFactsBuilder`
- `domain.normalize.FusionFactsBuilder`
- `domain.normalize.ProblemCandidateBuilder`
- `domain.normalize.RiskCandidateBuilder`
- `domain.normalize.TimelineEntryBuilder`
- `domain.normalize.NoteTimeResolver`
- `domain.normalize.NormalizePromptSelector`
- `domain.normalize.NormalizeOutputValidator`
- `domain.normalize.NormalizeRetryInstructionBuilder`
- `ai.gateway.NormalizeModelClient`
- `pipeline.model.NormalizeResult`

### 6.1 用例入口

| 现有方法 | 新归属 | 动作 | 说明 |
|---|---|---|---|
| `extractDailyIllness(...)` | `pipeline.handler.NormalizeHandler` | 拆分迁移 | 作为 normalize 用例入口，不再放在 agent 中 |

### 6.2 日维事实构造

| 现有方法 | 新归属 | 动作 | 说明 |
|---|---|---|---|
| `buildStandardizedDayFacts(...)` | `domain.normalize.DayFactsBuilder` | 迁移 | 日维标准事实构造 |
| `summarizeVitals(...)` | `domain.normalize.DayFactsBuilder` | 迁移 | 体征摘要 |
| `summarizeDoctorOrders(...)` | `domain.normalize.DayFactsBuilder` | 迁移 | 医嘱摘要 |
| `buildDataPresence(...)` | `domain.normalize.DayFactsBuilder` | 迁移 | 数据存在性判断 |
| `buildDiagnosisFacts(...)` | `domain.normalize.DayFactsBuilder` | 迁移 | 诊断事实 |
| `summarizeLabs(...)` | `domain.normalize.DayFactsBuilder` | 迁移 | 检验摘要 |
| `summarizeImaging(...)` | `domain.normalize.DayFactsBuilder` | 迁移 | 影像摘要 |
| `buildObjectiveEvents(...)` | `domain.normalize.DayFactsBuilder` | 迁移 | 客观事件构造 |
| `canGenerateDailyFusion(...)` | `domain.normalize.FusionFactsBuilder` | 迁移 | fusion 触发规则 |
| `buildFusionReadyFacts(...)` | `domain.normalize.FusionFactsBuilder` | 迁移 | fusion 输入组装 |

### 6.3 候选问题 / 风险候选构造

| 现有方法 | 新归属 | 动作 | 说明 |
|---|---|---|---|
| `buildProblemCandidates(...)` | `domain.normalize.ProblemCandidateBuilder` | 迁移 | 问题候选构造 |
| `buildFusionMeta(...)` | `domain.normalize.FusionFactsBuilder` | 迁移 | fusion 元信息 |
| `buildDifferentialCandidates(...)` | `domain.normalize.ProblemCandidateBuilder` | 迁移 | 鉴别诊断候选 |
| `buildEtiologyCandidates(...)` | `domain.normalize.ProblemCandidateBuilder` | 迁移 | 病因候选 |
| `buildObjectiveEvidence(...)` | `domain.normalize.ProblemCandidateBuilder` | 迁移 | 客观证据 |
| `buildActionFacts(...)` | `domain.normalize.ProblemCandidateBuilder` | 迁移 | 行为事实 |
| `buildPendingFacts(...)` | `domain.normalize.ProblemCandidateBuilder` | 迁移 | 待办事实 |
| `buildDetailedRiskCandidates(...)` | `domain.normalize.RiskCandidateBuilder` | 迁移 | 风险候选 |
| `mergeProblemCandidate(...)` | `domain.normalize.ProblemCandidateBuilder` | 迁移 | 候选合并 |
| `extractProblemCandidatesFromStructured(...)` | `domain.normalize.ProblemCandidateBuilder` | 迁移 | 从结构化结果提取候选 |
| `readStructuredNotes(...)` | `domain.normalize.ProblemCandidateBuilder` | 迁移 | 读取结构化 note |

### 6.4 时间线结果装配

| 现有方法 | 新归属 | 动作 | 说明 |
|---|---|---|---|
| `buildDailyFusionTimelineEntries(...)` | `domain.normalize.TimelineEntryBuilder` | 迁移 | 生成 timeline entry |
| `locateTimelineArray(...)` | `domain.normalize.TimelineEntryBuilder` 或 JSON 工具类 | 迁移 | 时间线数组定位 |
| `replaceTimelineArray(...)` | `domain.normalize.TimelineEntryBuilder` 或 JSON 工具类 | 迁移 | 时间线数组回写 |
| `resolveReferenceEpoch(...)` | `domain.normalize.TimelineEntryBuilder` | 迁移 | 时间参考 |
| `extractTimelineEpoch(...)` | `domain.normalize.TimelineEntryBuilder` | 迁移 | 时间提取 |
| `parseToEpoch(...)` | `domain.normalize.TimelineEntryBuilder` 或时间工具类 | 迁移 | 时间解析 |
| `resolveIllnessTime(...)` | `domain.normalize.NoteTimeResolver` | 迁移 | 病程时间标准化 |
| `noteTypePriority(...)` | `domain.normalize.NoteTypePriorityResolver` | 迁移 | note 排序规则 |

### 6.5 Prompt 选择与输出校验

| 现有方法 | 新归属 | 动作 | 说明 |
|---|---|---|---|
| `selectPromptByItemName(...)` | `domain.normalize.NormalizePromptSelector` | 迁移 | note prompt 选择 |
| `dailyFusionPromptDefinition()` | `domain.normalize.NormalizePromptSelector` | 迁移 | fusion prompt 选择 |
| `callWithValidation(...)` | `domain.normalize.NormalizeOutputValidator` + `ai.gateway.NormalizeModelClient` | 拆分 | 不能继续混合校验和模型调用 |
| `callPrompt(...)` | `domain.normalize.NormalizeOutputValidator` + `ai.gateway.NormalizeModelClient` | 拆分 | 组装 retry prompt 与模型调用分开 |
| `validatePromptOutput(...)` | `domain.normalize.NormalizeOutputValidator` | 迁移 | 输出校验 |
| `validateEnumRule(...)` | `domain.normalize.NormalizeOutputValidator` | 迁移 | 枚举规则校验 |
| `validateEnumRuleSegments(...)` | `domain.normalize.NormalizeOutputValidator` | 迁移 | 递归校验 |
| `validateLeafValue(...)` | `domain.normalize.NormalizeOutputValidator` | 迁移 | 叶子值校验 |
| `buildRetryUserInput(...)` | `domain.normalize.NormalizeRetryInstructionBuilder` | 迁移 | retry 输入 |
| `buildRetryInstruction(...)` | `domain.normalize.NormalizeRetryInstructionBuilder` | 迁移 | retry 指令 |
| `validationSpecFor(...)` | `domain.normalize.NormalizePromptSelector` 或 `NormalizeValidationSpecRegistry` | 迁移 | 规则注册 |

### 6.6 模型调用

| 现有方法 | 新归属 | 动作 | 说明 |
|---|---|---|---|
| `callWithPrompt(String, String, String)` | `ai.gateway.NormalizeModelClient` | 迁移 | 必须经 `ModelCallGuard` 调用 |
| `callWithPrompt(String, String)` | `ai.gateway.NormalizeModelClient` | 迁移 | 同上 |

### 6.7 通用工具

| 现有方法 | 新归属 | 动作 | 说明 |
|---|---|---|---|
| `hasNonEmptyNode(...)` | 公共 JSON 工具类 | 迁移 | 通用函数 |
| `hasLabData(...)` | `domain.normalize.DayFactsBuilder` 或工具类 | 迁移 | 领域相关 |
| `hasDoctorOrderData(...)` | `domain.normalize.DayFactsBuilder` 或工具类 | 迁移 | 领域相关 |
| `valueAsString(...)` | 工具类 | 迁移 | 通用函数 |
| `defaultIfBlank(...)` | 工具类 | 迁移 | 通用函数 |
| `buildLabResultDisplay(...)` | `domain.normalize.DayFactsBuilder` | 迁移 | 检验展示 |
| `isLabResultAbnormal(...)` | `domain.normalize.DayFactsBuilder` | 迁移 | 检验异常规则 |
| `firstNonBlank(...)` | 工具类 | 迁移 | 通用函数 |
| `collectTextValues(...)` | 工具类 | 迁移 | JSON 文本提取 |
| `deduplicateKeepOrder(...)` | 工具类 | 迁移 | 通用函数 |
| `buildSimpleEvent(...)` | `domain.normalize.DayFactsBuilder` | 迁移 | 领域构造 |
| `addEventIfPresent(...)` | `domain.normalize.DayFactsBuilder` | 迁移 | 领域构造 |
| `extractFirstTextFromMapList(...)` | 工具类 | 迁移 | 通用函数 |
| `preferNonBlank(...)` | 工具类 | 迁移 | 通用函数 |
| `toStructuredNode(...)` | 工具类 | 迁移 | 通用函数 |
| `buildNoteRef(...)` | `domain.normalize.NotePreparationService` | 迁移 | note ref 生成 |
| `deduplicateMapList(...)` | 工具类 | 迁移 | 通用函数 |
| `addAllStrings(...)` | 工具类 | 迁移 | 通用函数 |
| `collectFieldText(...)` | 工具类 | 迁移 | 通用函数 |
| `flattenText(...)` | 工具类 | 迁移 | 通用函数 |
| `buildShortExcerpt(...)` | 工具类 | 迁移 | 通用函数 |
| `parseDouble(...)` | 工具类 | 迁移 | 通用函数 |
| `parseBloodPressure(...)` | 工具类 | 迁移 | 通用函数 |
| `formatNumber(...)` | 工具类 | 迁移 | 通用函数 |
| `buildVitalMostAbnormal(...)` | `domain.normalize.DayFactsBuilder` | 迁移 | 体征规则 |
| `collectArrayText(...)` | 工具类 | 迁移 | 通用函数 |
| `containsAny(...)` | 工具类 | 迁移 | 通用函数 |
| `failureReport(...)` | `domain.normalize.NormalizeOutputValidator` 或结果工厂 | 迁移 | 错误报告 |
| `parseJsonQuietly(...)` | 工具类 | 迁移 | 通用函数 |
| `toJsonQuietly(...)` | 工具类 | 迁移 | 通用函数 |

### 6.8 结果模型

| 现有类型 | 新归属 | 动作 | 说明 |
|---|---|---|---|
| `DailyIllnessResult` | `pipeline.model.NormalizeResult` 或 `domain.normalize.NormalizeOutput` | 替换 | 不建议继续嵌在 agent 内 |
| `PromptDefinition` | `domain.normalize.NormalizePromptSelector` | 迁移 | 作为内部模型 |
| `PromptValidationSpec` | `domain.normalize.NormalizeValidationSpecRegistry` | 迁移 | 校验规则 |
| `EnumFieldRule` | `domain.normalize.NormalizeValidationSpecRegistry` | 迁移 | 校验规则 |
| `ValidationIssue` | `domain.normalize.NormalizeOutputValidator` | 迁移 | 校验结果 |
| `ValidationResult` | `domain.normalize.NormalizeOutputValidator` | 迁移 | 校验结果 |
| `AttemptReport` | `domain.normalize.NormalizeOutputValidator` | 迁移 | 重试结果 |
| `ValidatedLlmResult` | `domain.normalize.NormalizeOutputValidator` | 迁移 | 校验和输出结果 |

## 7. `FormatAgent` 方法级映射

`FormatAgent` 不应继续保留“业务 + 并发 + 模型调用”混合结构。

建议拆出的目标类：

- `pipeline.handler.FormatContextHandler`
- `domain.format.FormatInputSplitter`
- `domain.format.IllnessCourseDeduplicator`
- `domain.format.FinalMergePromptBuilder`
- `domain.format.FilteredRawDataBuilder`
- `ai.gateway.FormatModelClient`

| 现有方法 | 新归属 | 动作 | 说明 |
|---|---|---|---|
| `format(...)` | `pipeline.handler.FormatContextHandler` | 拆分迁移 | 作为格式化用例入口 |
| `formatIllnessSection(...)` | `domain.format.FormatSectionService` 或 `ai.gateway.FormatModelClient` | 拆分 | section 处理逻辑 |
| `formatOtherSection(...)` | `domain.format.FormatSectionService` 或 `ai.gateway.FormatModelClient` | 拆分 | section 处理逻辑 |
| `buildFinalMergePrompt(...)` | `domain.format.FinalMergePromptBuilder` | 迁移 | prompt 构造 |
| `callWithPrompt(...)` | `ai.gateway.FormatModelClient` | 迁移 | 模型调用必须统一收口 |
| `filterIllnessCourse(...)` | `domain.format.FilteredRawDataBuilder` | 迁移 | 直接操作 `PatientRawDataEntity`，不应留在 agent 中 |

强制要求：

1. 删除 `format(...)` 内部的 `CompletableFuture.supplyAsync(...)`。
2. `FormatAgent` 若继续存在，只能作为薄 gateway；更推荐直接删除，改为 `FormatModelClient`。

## 8. `WarningAgent` 方法级映射

`WarningAgent` 当前结构最接近目标状态，但命名与分层不合理。

建议迁移目标：

- `ai.gateway.EventExtractorModelClient`
- `ai.gateway.StructuredFactRefinementModelClient`
- `ai.gateway.CaseJudgeModelClient`

| 现有方法 | 新归属 | 动作 | 说明 |
|---|---|---|---|
| `callEventExtractor(...)` | `ai.gateway.EventExtractorModelClient` | 迁移或拆分 | 纯模型调用适配 |
| `callStructuredFactRefinement(...)` | `ai.gateway.StructuredFactRefinementModelClient` | 迁移或拆分 | 纯模型调用适配 |
| `callCaseJudge(...)` | `ai.gateway.CaseJudgeModelClient` | 迁移或拆分 | 纯模型调用适配 |

要求：

1. 这些 model client 不直接暴露给 scheduler/coordinator。
2. 只能由 handler 或业务 service 通过 `ModelCallGuard` 间接调用。

## 9. `AbstractAgent` 重构建议

当前 `AbstractAgent` 直接暴露 `ChatModel`，不利于统一限流。

建议：

1. 废弃 `AbstractAgent`
2. 不再允许业务类通过继承直接拿到 `ChatModel`
3. 改为显式注入 `ModelGateway` 或 `ModelCallGuard`

迁移策略：

| 现有类 | 新归属 | 动作 | 说明 |
|---|---|---|---|
| `AbstractAgent` | 删除 | 删除 | 不应作为公共基类保留 |

## 10. 推荐实施顺序

### 10.1 第一批先迁

优先迁移：

1. 3 个 scheduler 的方法体
2. `InfectionPipeline.executeParallelTasks(...)`
3. `WarningAgent` -> `ai.gateway.*`
4. `SummaryAgent` 中直接模型调用方法

### 10.2 第二批迁移

继续迁移：

1. `InfectionPipeline.process* / finalize*`
2. `SummaryAgent.extractDailyIllness(...)`
3. `FormatAgent.format(...)`

### 10.3 第三批清理

最后处理：

1. `SummaryAgent` 中通用工具方法
2. `FormatAgent.filterIllnessCourse(...)`
3. `AbstractAgent`
4. 各内部 record

## 11. 重构完成判定标准

完成本文档中列出的迁移后，应达到以下状态：

1. scheduler 中不再直接调用业务逻辑。
2. `InfectionPipeline` 中不再存在 `executeParallelTasks(...)`。
3. 业务层不再直接调用 `ChatModel`。
4. 所有模型调用都可通过 `ModelCallGuard` 统一控制。
5. `SummaryAgent`、`FormatAgent` 不再承担“业务编排 + 模型调用 + 工具类”的混合职责。
6. 所有 work unit 都可直接挂接到 [shared-executor-architecture-design.md](/Users/chengshengqing/IdeaProjects/yg_ai/docs/shared-executor-architecture-design.md) 中定义的调度链路。
