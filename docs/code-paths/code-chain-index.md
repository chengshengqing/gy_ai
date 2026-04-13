# 代码链路索引

## 1. 文档目标

本文档只保留当前真实代码链路，供后续 AI 或开发者快速定位入口类、执行类和关键方法。

本文档不记录：

- 重构施工步骤
- 草案伪代码
- 已完成 / 未完成流水账
- 已退出主链的历史类迁移清单

当前详细架构说明见：

- `docs/overview/architecture.md`
- `docs/scheduling/shared-executor-architecture-design.md`
- `docs/infection-warning/infection-warning-design.md`
- `docs/data/data-model-design.md`

## 2. 调度主链路

统一调度链路：

```text
@Scheduled
  -> InfectionPipelineFacade
  -> StageDispatcher
  -> StageCoordinator
  -> WorkUnitExecutor
  -> Handler
```

核心类：

- `task.InfectionMonitorScheduler`
- `task.StructDataFormatScheduler`
- `task.SummaryWarningScheduler`
- `pipeline.facade.InfectionPipelineFacade`
- `pipeline.scheduler.executor.StageDispatcher`
- `pipeline.scheduler.executor.WorkUnitExecutor`
- `pipeline.stage.StageCoordinator`
- `pipeline.handler.AbstractTaskHandler`

阶段枚举：

- `LOAD_ENQUEUE`
- `LOAD_PROCESS`
- `NORMALIZE`
- `EVENT_EXTRACT`
- `CASE_RECOMPUTE`

调度层边界：

- `@Scheduled` 只触发阶段，不承载业务处理。
- `InfectionPipelineFacade` 只暴露阶段触发入口，不 claim 任务。
- `StageCoordinator` 负责 claim 和 work unit 组装。
- `Handler` 负责具体业务执行。

## 3. LOAD_ENQUEUE

入口方法：

```text
InfectionMonitorScheduler.enqueuePendingPatients()
```

当前链路：

```text
InfectionMonitorScheduler.enqueuePendingPatients()
  -> InfectionPipelineFacade.triggerLoadEnqueue()
  -> StageDispatcher
  -> LoadEnqueueCoordinator
  -> WorkUnitExecutor
  -> LoadEnqueueHandler
  -> PatientRawDataCollectTaskService
  -> patient_raw_data_collect_task
```

职责：

- 扫描待采集患者。
- 判断上游批次时间。
- 生成患者级采集任务。
- 不直接执行原始数据采集。

## 4. LOAD_PROCESS

入口方法：

```text
InfectionMonitorScheduler.processPendingCollectTasks()
```

当前链路：

```text
InfectionMonitorScheduler.processPendingCollectTasks()
  -> InfectionPipelineFacade.triggerLoadProcess()
  -> StageDispatcher
  -> LoadProcessCoordinator
  -> WorkUnitExecutor
  -> LoadProcessHandler
  -> PatientServiceImpl.collectAndSaveRawDataResult(...)
  -> patient_raw_data
  -> patient_raw_data_change_task
  -> infection_event_task(EVENT_EXTRACT)
```

职责：

- claim `patient_raw_data_collect_task`。
- 读取 SQL Server 业务源数据。
- 按天聚合患者事实块。
- 写入 `patient_raw_data.data_json` 和 `patient_raw_data.filter_data_json`。
- 写入结构化链路任务 `patient_raw_data_change_task`。
- 命中事件来源规则时写入 `infection_event_task(EVENT_EXTRACT)`。

关键类：

- `pipeline.stage.LoadProcessCoordinator`
- `pipeline.handler.LoadProcessHandler`
- `service.impl.PatientServiceImpl`
- `service.PatientRawDataCollectTaskService`
- `service.PatientRawDataChangeTaskService`
- `service.InfectionEventTaskService`

## 5. NORMALIZE

入口方法：

```text
StructDataFormatScheduler.formatPendingStructData()
```

当前链路：

```text
StructDataFormatScheduler.formatPendingStructData()
  -> InfectionPipelineFacade.triggerNormalize()
  -> StageDispatcher
  -> NormalizeCoordinator
  -> WorkUnitExecutor
  -> NormalizeHandler
  -> NormalizeStructDataService
  -> NormalizeContextBuilder
  -> NormalizeResultAssembler
  -> AiGateway
  -> patient_raw_data.struct_data_json
  -> patient_raw_data.event_json
```

职责：

- claim `patient_raw_data_change_task`。
- 校验 `raw_data_last_time` 与 `patient_raw_data.last_time` 是否一致。
- 只处理仍有效的单日快照。
- 生成单日结构化结果并写入 `struct_data_json`。
- 生成单日时间轴摘要并写入 `event_json`。

关键类：

- `pipeline.stage.NormalizeCoordinator`
- `pipeline.handler.NormalizeHandler`
- `service.normalize.NormalizeStructDataService`
- `service.normalize.NormalizeContextBuilder`
- `domain.normalize.validation.NormalizeResultAssembler`
- `domain.normalize.facts.DayFactsBuilder`
- `domain.normalize.facts.FusionFactsBuilder`
- `domain.normalize.facts.support.DailyFusionInputCompactor`
- `domain.normalize.prompt.NormalizePromptCatalog`
- `ai.gateway.AiGateway`

职责边界：

- `NormalizeHandler` 负责任务编排、单行 reset、结果回写、状态收尾。
- `NormalizeStructDataService` 负责串联 normalize 三层调用。
- `NormalizeContextBuilder` 负责输入准备、note prompt 输入、day context、daily fusion 输入。
- `DailyFusionInputCompactor` 只在超过输入预算时分级压缩 daily fusion 输入。
- `NormalizeResultAssembler` 负责模型结果校验、重试和最终 JSON 组装。

## 6. EVENT_EXTRACT

入口方法：

```text
SummaryWarningScheduler.processPendingEventTasks()
```

当前链路：

```text
SummaryWarningScheduler.processPendingEventTasks()
  -> InfectionPipelineFacade.triggerEventExtract()
  -> StageDispatcher
  -> EventExtractCoordinator
  -> WorkUnitExecutor
  -> EventExtractHandler
  -> PatientService.buildSummaryWindowJson(...)
  -> InfectionEvidenceBlockServiceImpl.buildBlocks(...)
  -> EventExtractHandler.selectPrimaryBlocks(...)
  -> LlmEventExtractorServiceImpl.extractAndSave(...)
  -> LlmEventExtractionSupport
  -> AiGateway
  -> EventNormalizerServiceImpl.normalize(...)
  -> InfectionEventIngestionServiceImpl.normalizeAndSave(...)
  -> InfectionEventPoolServiceImpl.saveOrUpdateByEventKey(...)
  -> infection_event_pool
  -> infection_event_task(CASE_RECOMPUTE)
```

职责：

- claim `infection_event_task` 中的 `EVENT_EXTRACT` 任务。
- 基于 `patient_raw_data.event_json` 构造窗口上下文。
- 构建 `STRUCTURED_FACT / CLINICAL_TEXT / MID_SEMANTIC / TIMELINE_CONTEXT` EvidenceBlock。
- 根据 `trigger_reason_codes` 和 `changed_types` 裁剪 primary blocks。
- 调用 LLM 抽取事件。
- 通过 `EventNormalizerServiceImpl` 归一化并校验事件。
- 按 `event_key` 幂等写入 `infection_event_pool`。
- 产生有效事件后触发患者级 `CASE_RECOMPUTE`。

关键类：

- `pipeline.stage.EventExtractCoordinator`
- `pipeline.handler.EventExtractHandler`
- `service.impl.InfectionEvidenceBlockServiceImpl`
- `service.evidence.StructuredFactBlockBuilder`
- `service.evidence.ClinicalTextBlockBuilder`
- `service.evidence.MidSemanticBlockBuilder`
- `service.evidence.TimelineContextBlockBuilder`
- `service.impl.StructuredFactRefinementServiceImpl`
- `service.evidence.StructuredFactRefinementSupport`
- `service.impl.LlmEventExtractorServiceImpl`
- `service.event.LlmEventExtractionSupport`
- `service.impl.EventNormalizerServiceImpl`
- `service.impl.InfectionEventIngestionServiceImpl`
- `service.impl.InfectionEventPoolServiceImpl`
- `domain.schema.InfectionEventSchema`
- `ai.prompt.WarningPromptCatalog`
- `ai.gateway.AiGateway`

## 7. CASE_RECOMPUTE / 法官裁决

入口方法：

```text
SummaryWarningScheduler.processPendingCaseTasks()
```

当前链路：

```text
SummaryWarningScheduler.processPendingCaseTasks()
  -> InfectionPipelineFacade.triggerCaseRecompute()
  -> StageDispatcher
  -> CaseRecomputeCoordinator
  -> WorkUnitExecutor
  -> CaseRecomputeHandler
  -> InfectionCaseSnapshotServiceImpl
  -> InfectionEvidencePacketBuilderImpl
  -> InfectionJudgeServiceImpl.judge(...)
  -> InfectionCaseJudgeSupport
  -> AiGateway
  -> InfectionCaseSnapshotServiceImpl.saveOrUpdate(...)
  -> InfectionAlertResultServiceImpl.saveResult(...)
  -> infection_case_snapshot
  -> infection_alert_result
```

职责：

- claim `infection_event_task` 中的 `CASE_RECOMPUTE` 任务。
- 按患者级 `merge_key` 合并重算任务。
- 执行患者级防抖。
- 校验事件池版本，避免过期任务重复裁决。
- 构建 `InfectionEvidencePacket`。
- 调用院感法官节点。
- 更新 `infection_case_snapshot` 当前态。
- 写入 `infection_alert_result` 结果版本。

关键类：

- `pipeline.stage.CaseRecomputeCoordinator`
- `pipeline.handler.CaseRecomputeHandler`
- `service.impl.InfectionEvidencePacketBuilderImpl`
- `service.impl.InfectionJudgeServiceImpl`
- `service.casejudge.InfectionCaseJudgeSupport`
- `service.impl.InfectionCaseSnapshotServiceImpl`
- `service.impl.InfectionAlertResultServiceImpl`
- `domain.model.InfectionEvidencePacket`
- `domain.model.JudgeDecisionResult`
- `ai.prompt.WarningPromptCatalog`
- `ai.gateway.AiGateway`

## 8. 模型调用统一边界

统一模型调用链路：

```text
Handler / Service
  -> AiGateway
  -> ModelCallGuard
  -> Spring AI ChatModel / vLLM
```

关键类：

- `ai.gateway.AiGateway`
- `pipeline.scheduler.limiter.ModelCallGuard`

当前主要调用点：

- `domain.normalize.validation.NormalizeResultAssembler`
- `domain.format.FormatSectionFormatter`
- `service.impl.StructuredFactRefinementServiceImpl`
- `service.impl.LlmEventExtractorServiceImpl`
- `service.impl.InfectionJudgeServiceImpl`

边界：

- 业务类不直接维护模型并发 permit。
- `AiGateway` 负责 Spring AI 调用和输出归一化。
- `ModelCallGuard` 负责模型并发控制、耗时记录和监控埋点。

## 9. Format 辅助链路

当前链路：

```text
FormatContextComposer
  -> FormatSectionFormatter
  -> FinalMergePromptBuilder
  -> FilteredRawDataBuilder
  -> AiGateway
```

关键类：

- `ai.prompt.FormatAgentPrompt`
- `domain.format.FormatContextComposer`
- `domain.format.FormatSectionFormatter`
- `domain.format.FinalMergePromptBuilder`
- `domain.format.FilteredRawDataBuilder`
- `ai.gateway.AiGateway`

说明：

- `FormatAgent` 已退出当前代码。
- 格式化相关编排当前保留在 `domain.format.*`。
- 当前主结构化链路以 `NormalizeStructDataService` 为准。

## 10. 症候群监测链路

当前链路：

```text
InfectiousSyndromeSurveillanceTask
  -> SurveillanceAgent
  -> SurveillanceResponseValidator
  -> AiProcessLogServiceImpl
  -> ItemsInforZhqServiceImpl
```

关键类：

- `task.InfectiousSyndromeSurveillanceTask`
- `ai.agent.SurveillanceAgent`
- `ai.validator.SurveillanceResponseValidator`
- `service.impl.AiProcessLogServiceImpl`
- `service.impl.ItemsInforZhqServiceImpl`

说明：

- 这条链路与 `patient_raw_data -> timeline` 主链路基本并行。
- 默认不作为院感事件池主链路入口。

## 11. 当前包结构索引

调度与执行：

- `task`
- `pipeline.facade`
- `pipeline.scheduler.executor`
- `pipeline.scheduler.limiter`
- `pipeline.scheduler.policy`
- `pipeline.scheduler.runtime`
- `pipeline.stage`
- `pipeline.handler`

Normalize：

- `service.normalize`
- `domain.normalize.assemble`
- `domain.normalize.facts`
- `domain.normalize.facts.candidate`
- `domain.normalize.facts.support`
- `domain.normalize.prompt`
- `domain.normalize.support`
- `domain.normalize.validation`

Format：

- `domain.format`

院感预警：

- `service.evidence`
- `service.event`
- `service.casejudge`
- `domain.schema`
- `domain.model`
- `domain.enums`

模型调用：

- `ai.gateway`
- `ai.prompt`
