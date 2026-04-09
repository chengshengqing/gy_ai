# 方法级重构映射清单

## 1. 文档目的

本文档基于当前代码状态和 [shared-executor-architecture-design.md](/Users/chengshengqing/IdeaProjects/yg_ai/docs/shared-executor-architecture-design.md) 的最新结论，给出后续业务代码重构的可执行映射清单。

本文档不再以已经退出主链的旧类作为施工基线，例如：

- `InfectionPipeline`
- `SummaryAgent`
- `WarningAgent`

这些类只保留为历史迁移背景，不再作为下一步重构入口。

## 2. 当前基线结论

截至 2026-04-08，以下结构已经完成，不应重复施工：

1. `@Scheduled -> InfectionPipelineFacade -> StageDispatcher -> StageCoordinator -> WorkUnitExecutor -> Handler` 主链已打通。
2. 3 个 scheduler 已经是 trigger-only。
3. `pipeline.scheduler` 已拆成 `executor / limiter / policy / runtime`。
4. `pipeline.handler` 已经承接采集、结构化、事件抽取、病例重算主执行编排。
5. 模型调用已经统一经过 `ai.gateway.*Gateway -> ModelCallGuard`。
6. `domain.normalize` 已拆成：
   - `assemble`
   - `facts`
   - `facts.candidate`
   - `prompt`
   - `support`
   - `validation`

因此，本文档后续重点不再是“如何引入调度骨架”，而是：

1. 将剩余的大方法继续拆到当前已经存在的分层中。
2. 把仍然偏厚的业务类切成职责更单一的具体类。
3. 在不违反抽象治理准则的前提下，继续消化历史遗留的“大而全”类。

当前状态补充：

- 截至 2026-04-09，本文档第 11 节定义的结构重构完成判定标准已达成。
- 第 5 节与第 10.3 节中保留的 `SurveillanceAgent`、`AgentUtils`、遗留文档边界项，归为后续治理项，不阻塞本轮结构性重构结项。

## 3. 当前目标分层

当前和后续允许使用的目标包结构如下：

```text
com.zzhy.yg_ai.task
com.zzhy.yg_ai.pipeline.facade
com.zzhy.yg_ai.pipeline.scheduler.executor
com.zzhy.yg_ai.pipeline.scheduler.limiter
com.zzhy.yg_ai.pipeline.scheduler.policy
com.zzhy.yg_ai.pipeline.scheduler.runtime
com.zzhy.yg_ai.pipeline.stage
com.zzhy.yg_ai.pipeline.handler
com.zzhy.yg_ai.pipeline.model
com.zzhy.yg_ai.service.evidence
com.zzhy.yg_ai.service.event
com.zzhy.yg_ai.service.casejudge
com.zzhy.yg_ai.domain.normalize.assemble
com.zzhy.yg_ai.domain.normalize.facts
com.zzhy.yg_ai.domain.normalize.facts.candidate
com.zzhy.yg_ai.domain.normalize.prompt
com.zzhy.yg_ai.domain.normalize.support
com.zzhy.yg_ai.domain.normalize.validation
com.zzhy.yg_ai.domain.format
com.zzhy.yg_ai.ai.gateway
com.zzhy.yg_ai.ai.prompt
```

约束：

1. 不新增“单实现接口 + Default 实现”双层壳。
2. 新增类优先采用具体职责命名。
3. 只有出现第二实现、外部边界隔离，或 3 处以上稳定重复时，才允许新增抽象。
4. `domain` 目录优先承接稳定规则和数据结构，不继续作为 warning 业务 Spring 组件的平铺容器。

## 4. 已完成映射

本节用于标记已经完成的结构迁移，后续不要再围绕这些点重新设计。

### 4.1 Scheduler / Facade

| 方法 | 当前归属 | 状态 | 说明 |
|---|---|---|---|
| `enqueuePendingPatients()` | `task.InfectionMonitorScheduler` | 已完成 | 只调用 `InfectionPipelineFacade.triggerLoadEnqueue()` |
| `processPendingCollectTasks()` | `task.InfectionMonitorScheduler` | 已完成 | 只调用 `InfectionPipelineFacade.triggerLoadProcess()` |
| `formatPendingStructData()` | `task.StructDataFormatScheduler` | 已完成 | 只调用 `InfectionPipelineFacade.triggerNormalize()` |
| `processPendingEventTasks()` | `task.SummaryWarningScheduler` | 已完成 | 只调用 `InfectionPipelineFacade.triggerEventExtract()` |
| `processPendingCaseTasks()` | `task.SummaryWarningScheduler` | 已完成 | 只调用 `InfectionPipelineFacade.triggerCaseRecompute()` |

### 4.2 调度主链

以下能力已经完成，不再作为下一步重构对象：

- `InfectionPipelineFacade`
- `StageDispatcher`
- `WorkUnitExecutor`
- `StageCoordinator` 及各 stage coordinator
- `LoadEnqueueHandler`
- `LoadProcessHandler`
- `NormalizeHandler`
- `EventExtractHandler`
- `CaseRecomputeHandler`
- `ModelCallGuard`
- `NormalizeModelGateway`
- `FormatModelGateway`
- `WarningModelGateway`

### 4.3 `domain.normalize` 拆包

以下历史迁移已经完成：

- `SummaryAgent` 中的日维事实构造方法已迁入 `domain.normalize.facts.DayFactsBuilder`
- fusion 构造方法已迁入 `domain.normalize.facts.FusionFactsBuilder`
- 问题/风险候选构造方法已迁入 `domain.normalize.facts.candidate.*`
- prompt 选择与校验规则已迁入 `domain.normalize.prompt.*`
- 输出校验与重试逻辑已迁入 `domain.normalize.validation.*`
- note 准备与排序辅助已迁入 `domain.normalize.support.*`

当前后续重构不应再以 `SummaryAgent` 作为方法迁移源头，而应以当前 `pipeline.handler.NormalizeRowProcessor` 和 `domain.normalize.*` 为基线。

### 4.4 `NormalizeRowProcessor` 第一轮收薄

以下迁移已完成：

- `NormalizeRowProcessor.extractDailyIllness(...)` 已迁入 `pipeline.handler.NormalizeStructDataComposer.compose(...)`
- `resolveRawInputJson(...)` / `parseToNode(...)` / `toJson(...)` 已收敛到 `NormalizeStructDataComposer` 内部
- `NormalizeRowProcessor.process(...)` 当前只保留重置派生数据、调用 composer、结果回写与错误包装

### 4.5 `FormatAgent` 第一轮收薄

以下迁移已完成：

- `FormatAgent.format(...)` 已迁入 `domain.format.FormatContextComposer.compose(...)`
- `formatIllnessSection(...)` / `formatOtherSection(...)` / `callWithPrompt(...)` 已迁入 `domain.format.FormatSectionFormatter`
- `buildFinalMergePrompt(...)` 已迁入 `domain.format.FinalMergePromptBuilder`
- `filterIllnessCourse(...)` 已迁入 `domain.format.FilteredRawDataBuilder`
- `FormatAgent` 当前只保留兼容入口并转发到 `domain.format.*`

### 4.6 `StructuredFactRefinementServiceImpl` 收敛到 evidence 预处理

以下迁移已完成：

- `StructuredFactRefinementServiceImpl.refine(...)` 当前只保留入口编排、模型调用、审计落库、失败降级
- 候选构造、响应校验、结果回写已收敛到 `service.evidence.StructuredFactRefinementSupport`
- 原先拆到 `domain.event` 的 builder / executor / applier / audit builder 已回收，避免把业务 Spring 组件塞进 `domain`
- 同时补齐了“模型输出全无效项仍记成功”的校验，以及失败审计不覆盖原始异常的保护

## 5. 当前业务重构优先对象

下一步业务重构建议按以下顺序推进：

1. `ai.agent.SurveillanceAgent`
2. `ai.agent.AgentUtils`
3. 遗留文档与预留模块边界清理

## 6. Normalize 业务链路方法级映射

当前真实入口：

`NormalizeHandler -> NormalizeRowProcessor -> NormalizeStructDataComposer -> domain.normalize.* -> NormalizeModelGateway`

### 6.1 `NormalizeRowProcessor`

| 当前方法 | 当前归属 | 建议新归属 | 动作 | 说明 |
|---|---|---|---|---|
| `process(PatientRawDataEntity)` | `pipeline.handler.NormalizeRowProcessor` | 保留 | 已完成第一轮收薄 | 当前只保留重置派生数据、调用业务组装器、结果落库、错误包装 |
| `compose(PatientRawDataEntity)` | `pipeline.handler.NormalizeStructDataComposer` | 保留 | 已完成 | 承接原 `extractDailyIllness(...)` 的 note 结构化、fusion 调用、structData 组装、timeline 输出组装 |
| `resolveRawInputJson(...)` | `pipeline.handler.NormalizeStructDataComposer` | 保留内部 | 已完成 | 仅服务结构化输入选择 |
| `parseToNode(...)` | `pipeline.handler.NormalizeStructDataComposer` | 保留内部或后续公共 JSON 工具类 | 已完成 | 通用 JSON 解析 |
| `toJson(...)` | `pipeline.handler.NormalizeStructDataComposer` | 保留内部或后续公共 JSON 工具类 | 已完成 | 通用 JSON 序列化 |

说明：

- `NormalizeRowProcessor` 的第一轮收薄已完成，后续重点可转向 `FormatAgent`。
- 下一步不建议再把 `extractDailyIllness(...)` 塞回 `domain.normalize`，因为它本质上是用例编排，而不是单一领域规则。
- 推荐新增具体类 `NormalizeStructDataComposer`，不要新增接口。

### 6.2 `NormalizeStructDataComposer` 预期职责

以下流程已经集中到该具体类中：

1. 选择输入 JSON
2. 调用 `NormalizeNoteStructAssembler`
3. 调用 `DayFactsBuilder`
4. 调用 `FusionFactsBuilder`
5. 调用 `NormalizeOutputValidator`
6. 组装 `structDataJson`
7. 组装 `dailySummaryJson`

返回值仍建议复用：

- `domain.normalize.facts.DailyIllnessExtractionResult`

### 6.3 `NormalizeNoteStructAssembler`

| 当前方法 | 当前归属 | 建议动作 | 说明 |
|---|---|---|---|
| `assemble(...)` | `domain.normalize.assemble.NormalizeNoteStructAssembler` | 保留 | 当前职责清晰，继续作为 note 结构化入口 |
| `callNotePrompt(...)` | `NormalizeNoteStructAssembler` | 暂保留 | 如后续继续变厚，可拆到新的具体类 `NormalizeNotePromptRunner` |
| `buildStructuredNote(...)` | `NormalizeNoteStructAssembler` | 暂保留 | 当前只服务本类，不必提前拆 |
| `buildValidationNote(...)` | `NormalizeNoteStructAssembler` | 暂保留 | 同上 |
| `toJson(...)` | `NormalizeNoteStructAssembler` | 可后续迁移 | 若多个 normalize 类继续重复，可统一进 JSON 工具类 |
| `defaultIfBlank(...)` | `NormalizeNoteStructAssembler` | 可后续迁移 | 若多处重复再提取 |

### 6.4 `domain.normalize` 已落地映射

以下旧 `SummaryAgent` 能力已经同步到当前代码：

| 历史职责 | 当前类 |
|---|---|
| 日维事实构造 | `domain.normalize.facts.DayFactsBuilder` |
| fusion 输入与 meta 组装 | `domain.normalize.facts.FusionFactsBuilder` |
| 问题候选构造 | `domain.normalize.facts.candidate.ProblemCandidateBuilder` |
| 风险候选构造 | `domain.normalize.facts.candidate.RiskCandidateBuilder` |
| note 准备辅助 | `domain.normalize.support.NotePreparationSupport` |
| note 时间解析 | `domain.normalize.assemble.IllnessCourseTimeResolver` |
| note 类型排序 | `domain.normalize.support.NoteTypePriorityResolver` |
| prompt 选择与规则注册 | `domain.normalize.prompt.NormalizePromptCatalog` |
| 输出校验与重试 | `domain.normalize.validation.NormalizeOutputValidator` |
| prompt 结果校验 | `domain.normalize.validation.NormalizePromptOutputValidator` |
| retry 指令构造 | `domain.normalize.validation.NormalizeRetryInstructionBuilder` |
| 结果模型 | `domain.normalize.validation.NormalizeValidatedResult` 等 |

## 7. Format 业务链路方法级映射

当前第一轮拆分已完成，以下映射已经落地：

### 7.1 `FormatAgent`

| 当前方法 | 当前归属 | 建议新归属 | 动作 | 说明 |
|---|---|---|---|---|
| `format(...)` | `domain.format.FormatContextComposer` | 保留 | 已完成 | 承接输入拆分、病程去重、分段格式化、最终合并、`PatientContext` 构造 |
| `formatIllnessSection(...)` | `domain.format.FormatSectionFormatter` | 保留 | 已完成 | illness section 编排 |
| `formatOtherSection(...)` | `domain.format.FormatSectionFormatter` | 保留 | 已完成 | other section 编排 |
| `buildFinalMergePrompt(...)` | `domain.format.FinalMergePromptBuilder` | 保留 | 已完成 | prompt 组装 |
| `callWithPrompt(...)` | `domain.format.FormatSectionFormatter` | 继续经 `ai.gateway.FormatModelGateway` | 已完成 | 模型调用边界保持 `FormatModelGateway` |
| `filterIllnessCourse(...)` | `domain.format.FilteredRawDataBuilder` | 保留 | 已完成 | 直接操作 `PatientRawDataEntity` 的过滤逻辑已移出 agent |

当前已完成项：

1. `CompletableFuture.supplyAsync(...)` 已移除。
2. 模型调用已经经过 `FormatModelGateway`。
3. `domain.format` 包已建立并承接业务编排、section 格式化、merge prompt、rawData 过滤职责。

未完成项：

1. `FormatAgent` 已收薄为转发入口，但 `AgentUtils` 中仍保留部分 format 相关静态辅助函数，后续可继续观察是否需要下沉。

## 8. Warning 相关业务 service 方法级映射

旧 `WarningAgent` 已退出主链，后续应直接围绕当前 3 个 service 做方法级拆分。

### 8.1 `StructuredFactRefinementServiceImpl`

| 当前方法 | 建议新归属 | 动作 | 说明 |
|---|---|---|---|
| `refine(...)` | `StructuredFactRefinementServiceImpl` | 保留入口 | 已完成 | 当前只保留入口编排、模型调用、审计、失败降级 |
| `prepare(...)` | `service.evidence.StructuredFactRefinementSupport` | 保留 | 已完成 | LLM 输入组装、section 候选构造 |
| `parseAssignments(...)` | `service.evidence.StructuredFactRefinementSupport` | 保留 | 已完成 | 输出 JSON 校验与 assignment 解析 |
| `applyAssignments(...)` | `service.evidence.StructuredFactRefinementSupport` | 保留 | 已完成 | priority/reference facts 结果回写 |
| `buildPendingRun(...)` | `StructuredFactRefinementServiceImpl` | 保留内部 | 已完成 | node run 审计入参构造 |
| `buildRunPayload(...)` | `StructuredFactRefinementServiceImpl` | 保留内部 | 已完成 | 运行结果审计载荷 |
| `buildSectionInput(...)` / `buildCandidates(...)` / `collectRawTexts(...)` / `summarizeObject(...)` / `collectLeafValues(...)` | `service.evidence.StructuredFactRefinementSupport` | 保留内部 | 已完成 | 输入摘要和候选构造 |
| `mergeUniqueTexts(...)` / `copyTextArray(...)` / `normalizeSet(...)` / `normalizeIds(...)` | `service.evidence.StructuredFactRefinementSupport` | 保留内部 | 已完成 | 精炼结果应用辅助 |
| `parseJson(...)` / `writeJson(...)` / `jsonEquals(...)` | `service.evidence.StructuredFactRefinementSupport` 或 service 内私有实现 | 收敛 | 已完成 | 当前不单独抽公共 JSON 工具 |

当前已完成项：

1. `StructuredFactRefinementServiceImpl` 已收薄为入口 service。
2. `StructuredFactRefinementSupport` 已作为 evidence 预处理 helper 落在 `service.evidence`。
3. 模型输出中的无效 `source_section / candidate_id / promotion` 不再静默记成功。
4. `markFailed(...)` 失败不会再覆盖原始异常。
5. 不再继续扩张 `domain.event`，避免 `domain` 混入应用服务职责。

### 8.2 `LlmEventExtractorServiceImpl`

| 当前方法 | 建议新归属 | 动作 | 说明 |
|---|---|---|---|
| `extractAndSave(...)` | `LlmEventExtractorServiceImpl` | 保留入口 | 已完成收薄 | 继续作为事件抽取用例入口，负责逐 block 编排 |
| `extractSingleBlock(...)` | `LlmEventExtractorServiceImpl` | 保留内部 | 已完成收薄 | 当前只保留模型调用、归一化、落库、审计 |
| `buildPendingRun(...)` | `LlmEventExtractorServiceImpl` | 保留内部 | 已完成 | node run 审计入参构造 |
| `buildInputPayload(...)` | `service.event.LlmEventExtractionSupport` | 保留 | 已完成 | 主输入组装 |
| `buildStructuredContext(...)` / `buildRecentChangeContext(...)` / `limitTextArray(...)` | `service.event.LlmEventExtractionSupport` | 保留内部 | 已完成 | 上下文构造 |
| `prepareExtractorOutput(...)` | `service.event.LlmEventExtractionSupport` | 保留 | 已完成 | 输出预处理 |
| `normalizeEvents(...)` / `normalizeResponseStatus(...)` / `normalizeConfidence(...)` / `parseConfidence(...)` | `service.event.LlmEventExtractionSupport` | 保留内部 | 已完成 | 输出标准化 |
| `buildFailureMessage(...)` / `abbreviate(...)` / `buildRunPayload(...)` | `service.event.LlmEventExtractionSupport` | 保留 | 已完成 | 审计与错误摘要 |
| `buildAggregatedEventJson(...)` | `service.event.LlmEventExtractionSupport` | 保留 | 已完成 | 聚合 JSON 组装 |
| `extractConfidence(...)` / `countRawEvents(...)` | 删除 | 已删除 | 已完成 | 原有死 helper 已清理 |

说明：

- 不再预设迁入 `domain.event`。
- 当前已收敛为 `LlmEventExtractorServiceImpl + service.event.LlmEventExtractionSupport`。
- 这一步业务语义是事件抽取用例，不归入 `service.evidence`，也不进入 `domain`。

### 8.3 `InfectionJudgeServiceImpl`

| 当前方法 | 建议新归属 | 动作 | 说明 |
|---|---|---|---|
| `judge(...)` | `InfectionJudgeServiceImpl` | 保留入口 | 已完成收薄 | 继续作为病例裁决用例入口，负责模型调用和失败降级 |
| `buildPendingRun(...)` | `InfectionJudgeServiceImpl` | 保留内部 | 已完成 | node run 审计入参构造 |
| `buildInputPayload(...)` | `service.casejudge.InfectionCaseJudgeSupport` | 保留 | 已完成 | payload 序列化 |
| `parseDecision(...)` | `service.casejudge.InfectionCaseJudgeSupport` | 保留 | 已完成 | 裁决输出解析 |
| `buildFallbackDecision(...)` | `service.casejudge.InfectionCaseJudgeSupport` | 保留 | 已完成 | fallback 结果构造 |
| `buildRunPayload(...)` | `service.casejudge.InfectionCaseJudgeSupport` | 保留 | 已完成 | 审计载荷构造 |
| `readStringArray(...)` / `parseBoolean(...)` / `parseDateTime(...)` / `resolveNextJudgeTime(...)` / `resolveFollowUp(...)` / `parseResultVersion(...)` / `extractConfidence(...)` / `normalizeEnumText(...)` / `normalizeText(...)` / `validateEnum(...)` | `service.casejudge.InfectionCaseJudgeSupport` | 保留内部 | 已完成 | 裁决解析与护栏 |
| `collectValidKeys(...)` / `groupKeys(...)` / `resolveGroupKey(...)` | `service.casejudge.InfectionCaseJudgeSupport` | 保留内部 | 已完成 | fallback 证据筛选 |
| `writeJson(...)` | `service.casejudge.InfectionCaseJudgeSupport` | 保留内部 | 已完成 | 通用 JSON 序列化 |

说明：

- 不继续预设 `domain.casejudge` 作为 Spring 业务组件目录。
- 当前已收敛为 `InfectionJudgeServiceImpl + service.casejudge.InfectionCaseJudgeSupport`。
- 失败审计已补齐保护，不再覆盖原始异常。

## 9. Legacy Agent 清理状态

已完成：

1. `AbstractAgent` 已删除，不再作为主链或遗留 agent 的公共父类。
2. 空壳 `AuditAgent` 已删除，不再保留“无行为预留类”。
3. `SurveillanceAgent` 已改为直接注入 `ChatModel`，业务逻辑保持不变。

当前约束：

1. 不再新增 `extends AbstractAgent` 一类的共享父类。
2. 遗留 agent 如需继续保留，应直接声明自己的外部依赖，不复用抽象父类兜底。
3. `SurveillanceAgent` 当前仍属于遗留模块，后续若继续治理，优先处理边界与依赖，不改变既有业务逻辑。

## 10. 推荐实施顺序

### 10.1 第一批

优先处理：

1. `NormalizeRowProcessor`
2. `FormatAgent`

原因：

- 这两处仍然承担较重的业务编排职责
- 改动影响面可控
- 与当前 `pipeline`、`domain.normalize` 结构最直接相关

当前状态：

- 第一批中的 `NormalizeRowProcessor`、`FormatAgent` 第一轮拆分均已完成
- 第二批中的 `StructuredFactRefinementServiceImpl` 已完成收敛，当前保留为 `service + service.evidence` 结构
- 第二批中的 `LlmEventExtractorServiceImpl`、`InfectionJudgeServiceImpl` 也已完成收敛，分别保留为 `service + service.event`、`service + service.casejudge` 结构

### 10.2 第二批

继续处理：

1. 已完成

原因：

- 该批次的 3 个 warning 核心 service 已全部完成收薄
- 业务 helper 已按 use case 分别落到 `service.evidence`、`service.event`、`service.casejudge`

### 10.3 第三批

最后处理：

1. `SurveillanceAgent`
2. `AgentUtils`
3. 遗留文档与预留模块边界

原因：

- 这些能力暂不在共享线程池主链中
- 应在不影响现有主链的前提下单独做边界治理

## 11. 重构完成判定标准

完成本文档中列出的剩余迁移后，应达到以下状态：

1. 后续业务重构不再以 `InfectionPipeline`、`SummaryAgent`、`WarningAgent` 为施工基线。
2. `NormalizeRowProcessor` 只保留 handler 级 orchestration，不再混合 JSON 组装细节。
3. `FormatAgent` 不再承担“业务编排 + prompt 构造 + 数据过滤 + 模型调用”混合职责。
4. `StructuredFactRefinementServiceImpl`、`LlmEventExtractorServiceImpl`、`InfectionJudgeServiceImpl` 已分别收敛为 `service` 入口 + `service.evidence` / `service.event` / `service.casejudge` helper。
5. 遗留 agent 不再依赖抽象父类兜底。
6. 整个重构过程不重新引入“单实现接口 + Default 实现”或只做命名转发的薄封装。
