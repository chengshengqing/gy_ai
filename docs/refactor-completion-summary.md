# 重构完成说明

## 1. 结论

基于 [method-level-refactor-mapping.md](/Users/chengshengqing/IdeaProjects/yg_ai/docs/method-level-refactor-mapping.md) 第 11 节的完成判定标准，当前这轮结构性重构已完成。

说明：

- 已完成的是主链路与 warning 核心 service 的职责收敛、包结构治理、抽象治理清理。
- 仍保留的 `SurveillanceAgent`、`AgentUtils`、部分遗留文档边界说明，属于后续治理项，不影响本轮重构结项。

## 2. 当前结构基线

当前调度主链路：

`@Scheduled -> InfectionPipelineFacade -> StageDispatcher -> StageCoordinator -> WorkUnitExecutor -> Handler`

当前核心业务链路：

1. `NormalizeHandler -> NormalizeRowProcessor -> NormalizeStructDataComposer -> domain.normalize.* -> NormalizeModelGateway`
2. `FormatAgent -> domain.format.FormatContextComposer / FormatSectionFormatter / FinalMergePromptBuilder / FilteredRawDataBuilder -> FormatModelGateway`
3. `StructuredFactRefinementServiceImpl -> service.evidence.StructuredFactRefinementSupport -> WarningModelGateway`
4. `LlmEventExtractorServiceImpl -> service.event.LlmEventExtractionSupport -> WarningModelGateway`
5. `InfectionJudgeServiceImpl -> service.casejudge.InfectionCaseJudgeSupport -> WarningModelGateway`

## 3. 已完成的职责收敛

### 3.1 Normalize

- `NormalizeRowProcessor` 已收薄为 handler 级 orchestration，只保留 reset、调用 composer、结果回写和错误包装。
- `NormalizeStructDataComposer` 收口了输入选择、note 结构化、fusion 调用、`struct_data_json` / `event_json` 组装。
- `domain.normalize` 已稳定为 `assemble / facts / facts.candidate / prompt / support / validation` 六个职责子包。

### 3.2 Format

- `FormatAgent` 已降为兼容入口。
- `FormatContextComposer` 负责输入拆分、病程去重、分段格式化编排和 `PatientContext` 构造。
- `FormatSectionFormatter` 负责 section 级格式化与 `FormatModelGateway` 调用。
- `FinalMergePromptBuilder` 负责 merge prompt 组装。
- `FilteredRawDataBuilder` 负责过滤后的 `filter_data_json` 构造。

### 3.3 Warning 核心 service

- `StructuredFactRefinementServiceImpl` 已收薄为入口 service；候选构造、输出校验、结果回写收口到 `service.evidence.StructuredFactRefinementSupport`。
- `LlmEventExtractorServiceImpl` 已收薄为事件抽取入口；输入组装、输出规范化、聚合 JSON、运行载荷收口到 `service.event.LlmEventExtractionSupport`。
- `InfectionJudgeServiceImpl` 已收薄为病例裁决入口；输出解析、fallback、运行载荷与裁决护栏收口到 `service.casejudge.InfectionCaseJudgeSupport`。

## 4. 抽象治理结果

本轮已经落实的治理原则：

1. 不再使用“单实现接口 + Default 实现”双层壳。
2. `domain` 优先承接稳定规则和数据结构，不继续放 warning 业务 Spring 组件。
3. warning 侧 helper 按 use case 落在 `service.evidence`、`service.event`、`service.casejudge`。
4. `AbstractAgent` 已删除，空壳 `AuditAgent` 已删除。
5. `SurveillanceAgent` 改为直接注入 `ChatModel`，业务逻辑保持不变。

## 5. 行为与边界影响

本轮重构未改变以下边界：

- 不改变调度主链路入口和 stage 结构。
- 不改变数据库表结构与 Mapper 边界。
- 不改变现有 API 返回字段。
- 不改变 `Normalize`、`Format`、`EventExtractor`、`CaseJudge` 的核心 prompt 字段名。

本轮补齐的行为护栏：

- `StructuredFactRefinement` 对无效 `source_section / candidate_id / promotion` 不再静默记成功。
- `StructuredFactRefinement`、`LlmEventExtractor`、`InfectionJudge` 在 `markFailed(...)` 再次失败时，不再覆盖原始异常。

## 6. 已删除或退出主链的历史结构

以下结构已退出当前主链基线：

- `InfectionPipeline`
- `SummaryAgent`
- `WarningAgent`
- `AbstractAgent`
- `AuditAgent`

这些类现在只应被视为历史迁移背景，不再作为后续施工入口。

## 7. 验证结果

本轮结构性重构完成后，已执行：

```bash
./mvnw -q -DskipTests compile
```

验证结果：通过。

当前未完成项：

- 未补单元测试。
- `SurveillanceAgent`、`AgentUtils` 仍有遗留治理空间，但不属于本轮结构重构阻塞项。
