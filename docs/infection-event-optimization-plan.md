# 院感事件抽取链路优化方案

## 1. 目的

本文档用于约束后续院感事件抽取链路的优化顺序与目标，避免继续在文档、Prompt、枚举、Normalizer、事件池实现之间出现多套定义并存。

本文档结论基于当前代码审查结果，覆盖范围包括：

- `infection_event_pool` 数据模型
- 事件抽取 Prompt
- `EventNormalizer`
- 事件抽取定时任务链路
- 法官节点前置组包链路

## 2. 当前前提

当前系统仍处于开发阶段，没有历史生产数据。

因此后续优化遵循以下前提：

1. 不保留旧数据兼容层。
2. 不为旧枚举值、旧字段值、旧 Prompt 输出做长期兜底。
3. 一旦确定 Canonical Schema，所有写路径必须只接受标准值。
4. 对现有代码中的旧兼容逻辑，后续应直接删除，而不是继续扩大兼容范围。

## 3. 总体结论

当前系统的主要问题不是“缺少更多规则”，而是“缺少唯一规则源”。

最核心的结构性问题有 5 个：

1. `data-model-design.md`、枚举类、Prompt、Normalizer 并非完全基于同一套定义。
2. 枚举值之外仍散落大量协议字段定义。
3. 事件抽取链路对“合法 JSON 但字段漂移”的情况仍存在软失败。
4. `infection_event_pool` 文档意图与当前实现并不完全一致。
5. 任务链路和病例重算链路存在明显重复代码。

## 4. 优化原则

### 4.1 单一真源

以下内容必须存在唯一代码定义源：

- `event_type`
- `event_subtype`
- `clinical_meaning`
- `body_site`
- `source_type`
- `event_status`
- `evidence_role`
- `evidence_tier`
- `source_section`
- `event_category`

Prompt、Normalizer、文档必须引用或镜像这同一套定义，不能各自手写。

### 4.2 写路径严格，读路径简单

由于没有历史数据：

- 写路径不再接受 alias
- 读路径不再承担历史兼容职责
- 现有 alias、降级、兜底逻辑后续应删除

### 4.3 失败必须显式

对于事件抽取：

- 非法 JSON 必须失败
- 合法 JSON 但关键字段非法、导致全部事件被丢弃，也必须失败
- 只有明确 `status=skipped` 才允许空结果

### 4.4 先减复杂度，再补能力

优先删除冗余逻辑和重复构建，再考虑是否扩展版本化、事件生命周期等高级能力。

## 5. Canonical Schema 目标

## 5.1 `infection_event_pool` 定位

后续明确采用：

- `infection_event_pool` 是“当前标准化事件池”
- 当前阶段按 `event_key` 维护最新态
- 不引入事件版本链

因此当前阶段：

- `event_key` 仍作为幂等键
- `saveOrUpdateByEventKey` 继续保留
- 不引入追加式版本记录
- 不以 `superseded` 为主流程设计核心

如果未来确实需要事件生命周期审计，再单独升级为版本表。

## 5.2 字段语义

### `event_type`

定义为一级医学事实类型。

当前标准值：

- `diagnosis`
- `vital_sign`
- `lab_panel`
- `lab_result`
- `microbiology`
- `imaging`
- `order`
- `device`
- `procedure`
- `assessment`
- `problem`

### `event_subtype`

定义为事件二级类型，只描述“这是什么事件”，不描述最终推理方向。

当前标准值：

- `fever`
- `lab_abnormal`
- `culture_ordered`
- `culture_positive`
- `antibiotic_started`
- `antibiotic_upgraded`
- `procedure_exposure`
- `device_exposure`
- `imaging_infection_hint`
- `infection_positive_statement`
- `infection_negative_statement`
- `contamination_statement`
- `contamination_possible`
- `colonization_statement`
- `colonization_possible`

### `clinical_meaning`

定义为事件对感染判断的语义标签，只描述“这意味着什么”，不再承载暴露类概念。

当前标准值：

- `infection_support`
- `infection_against`
- `infection_uncertain`
- `screening`
- `baseline_problem`

### `evidence_role`

定义为法官节点前的证据方向桶。

当前标准值：

- `support`
- `against`
- `risk_only`
- `background`

### `source_type`

定义为事件来源层级，使用当前实现值：

- `raw`
- `mid`
- `summary`
- `manual_patch`

不再使用：

- `structured_fact`
- `clinical_text`
- `mid_semantic`

这些概念属于 block 类型或来源描述，不属于 `source_type` 落库值。

### `event_category`

定义为抽取来源类别，当前标准值：

- `fact`
- `text`
- `semantic`
- `context`

## 6. 需要删除的旧兼容逻辑

由于不存在历史数据，以下兼容逻辑应在后续优化中删除：

### 6.1 枚举 alias

应删除：

- `InfectionEventType.fromCodeOrAlias`
- `InfectionEventSubtype.fromCodeOrAlias`
- `InfectionClinicalMeaning.fromCodeOrAlias`

以及对应 alias：

- `note -> assessment`
- `consult -> assessment`
- `lab -> lab_result`
- `image -> imaging`
- `microbe -> microbiology`
- `culture_order -> culture_ordered`
- `culture_pos -> culture_positive`
- `antibiotic_upgrade -> antibiotic_upgraded`
- `support -> infection_support`
- `against -> infection_against`
- `uncertain -> infection_uncertain`

目标是只保留：

- `fromCode(...)`
- 严格 canonical code 校验

### 6.2 组包阶段旧值降级

应删除：

- `InfectionEvidencePacketBuilderImpl.normalizeClinicalMeaning(...)`

当前它会把旧值降级成 `infection_uncertain`。没有历史数据后，这类逻辑应删除，避免脏值继续被吞掉。

### 6.3 旧 Prompt 输出兜底

Prompt 中不再保留：

- `note`
- `consult`
- 暴露类 `clinical_meaning`

并明确要求模型只输出 canonical 值。

## 7. 协议定义统一方案

建议新增统一定义层，例如：

- `InfectionEventSchema`
- 或 `InfectionEventProtocol`

该层统一提供：

1. 所有可用枚举值集合
2. `source_section -> allowed event_type` 映射
3. `evidence_role` 集合
4. `evidence_tier` 集合
5. `event_category` 集合

后续各层使用方式：

- Prompt：由 schema 拼接枚举字符串
- Normalizer：直接引用 schema 校验
- 文档：以 schema 为准更新

## 8. 事件抽取失败语义优化

当前需要修正的不是“非法 JSON”，而是“字段漂移导致全部丢弃却仍然成功”。

后续规则应改为：

1. `status=skipped` 且 `events=[]`：成功
2. `status=success` 且 `events=[]`：失败
3. `status=success` 且 `events` 非空，但 `normalizedEvents=[]`：失败
4. 单条事件部分失败但仍保留部分成功事件：成功，但必须记录 rejected count

建议新增运行结果指标：

- `raw_event_count`
- `normalized_event_count`
- `persisted_event_count`
- `rejected_event_count`

并在 `infection_llm_node_run.output_payload` 或 `normalized_output_payload` 中体现。

当前已落地：

- `EVENT_EXTRACTOR`：记录 `raw/normalized/rejected/persisted`
- `STRUCTURED_FACT_REFINEMENT`：记录 `section/candidate/promote/keep/drop/changed`
- `CASE_JUDGE`：记录 `group/key/fallback`

## 9. 文档改造方案

`data-model-design.md` 当前混杂了：

- 需求映射
- 建议设计
- 当前实现

后续建议拆成 3 段：

### 9.1 Canonical Schema

只写当前真实生效定义。

### 9.2 Implementation Notes

只写当前实现细节，例如：

- `event_key` 生成规则
- `source_type` 使用值
- `event_category` 生成方式

### 9.3 Future Evolution

只写未来可能做但当前未实现的内容，例如：

- 事件版本化
- 生命周期表
- 撤销/替代链

## 10. 任务链路精简方案

## 10.1 事件抽取主链

当前主链为：

`SummaryWarningScheduler.processPendingEventTasks`
-> `InfectionPipeline.processPendingEventData`
-> `InfectionEventTaskService.claimPendingTasks(EVENT_EXTRACT, ...)`
-> `InfectionPipeline.processEventTask`
-> `InfectionEvidenceBlockService.buildBlocks`
-> `LlmEventExtractorService.extractAndSave`
-> `EventNormalizerService.normalize`
-> `InfectionEventPoolService.saveNormalizedEvents`
-> `upsertCaseRecomputeTask`

该主链方向合理，但存在两类冗余。

## 10.2 可立即精简的冗余

### 冗余 1：病例重算 packet 重复构建

当前：

- `InfectionPipeline.processCaseTask` 先构建 `packet`
- `InfectionJudgeService.judge` 内部再次构建 `packet`

后续应改为：

- `judge(InfectionEvidencePacket packet, LocalDateTime judgeTime)`

由 pipeline 构建一次并传入。

### 冗余 2：4 类任务处理流程重复

当前以下 4 段结构几乎相同：

- `processPendingRawDataTasks`
- `processPendingStructData`
- `processPendingEventData`
- `processPendingCaseData`

后续建议抽取通用模板：

- claim
- parallel execute
- collect result
- finalize

例如统一成一个内部模板方法。

### 冗余 3：事件抽取 block 全量执行

当前 `primaryBlocks()` 会无差别执行：

- `STRUCTURED_FACT`
- `CLINICAL_TEXT`
- `MID_SEMANTIC`

后续应根据 `triggerReasonCodes / changedTypes` 选择性执行。

例如：

- 没有病程变化时，不执行 `CLINICAL_TEXT`
- 没有 `struct_data_json` 风险项变化时，不执行 `MID_SEMANTIC`
- 只有化验变化时，不需要强制跑全部 block

当前已落地第一版：

- `triggerReasonCodes` 主导路由
- `changedTypes` 补充兜底和收窄
- 两者都缺失时回退为全量 `primaryBlocks`

## 11. 推荐优化顺序

### 阶段 1：统一定义

目标：

- 确定 canonical schema
- 修正文档
- 清理 `data-model-design.md` 中混乱定义

交付：

- 统一 schema 定义类
- 更新后的文档

### 阶段 2：移除旧兼容

目标：

- 删除 alias
- 删除旧值降级
- Prompt 只允许标准值

交付：

- 精简后的 enum
- 精简后的 builder
- 更严格的 normalizer

当前状态：

- 已完成

### 阶段 3：修正失败语义

目标：

- 禁止“成功但空”
- rejected event 显式记录

交付：

- 抽取失败语义重构
- node run 输出增强

当前状态：

- 已完成

### 阶段 4：精简链路

目标：

- 删除病例重算重复 build
- 抽取任务执行模板

交付：

- 更短的 `InfectionPipeline`
- 更简单的 `InfectionJudgeService`

当前状态：

- `judge(packet, judgeTime)` 已完成
- 并发任务执行模板已抽取

### 阶段 5：减少无效 LLM 调用

目标：

- 根据 trigger reason 裁剪 block
- 减少无必要抽取

交付：

- block 执行路由规则
- 更低 token 消耗

当前状态：

- 第一版 block 路由已完成

## 11.1 当前测试补齐情况

已补测试：

- `EventNormalizerServiceImpl` 严格失败语义
- `InfectionPipeline.selectPrimaryBlocks(...)` 路由
- `InfectionJudgeServiceImpl / StructuredFactRefinementServiceImpl` 的 `normalized_output_payload.stats`

## 12. 暂不做的事项

当前阶段不建议优先投入：

1. 事件版本链
2. `superseded/revoked` 完整生命周期建模
3. 复杂历史兼容
4. 新增更多事件类型
5. 用 LLM 做二次 schema 修复

原因：

- 当前收益最低
- 会放大复杂度
- 不能优先解决定义漂移和软失败问题

## 13. 后续执行要求

后续每一项优化都必须满足：

1. 先改 schema / enum / 常量定义
2. 再改 normalizer
3. 再改 prompt
4. 再改 builder / service
5. 最后更新文档

禁止继续出现：

- 先改 prompt，后补 schema
- 某个字段只在 normalizer 改，prompt 不改
- 文档和代码长期分叉

## 14. 第一批待执行事项

建议后续按以下顺序逐项落地：

1. 重写 `data-model-design.md` 的 `infection_event_pool` 部分
2. 新增统一 schema 定义层
3. 删除 `event_type / event_subtype / clinical_meaning` alias
4. 删除 `InfectionEvidencePacketBuilderImpl` 的旧值降级逻辑
5. 将抽取结果“成功但空”改为失败
6. 改造 `judge(packet, judgeTime)`，删除重复 build
7. 为 `EVENT_EXTRACT` 增加基于 trigger reason 的 block 裁剪
