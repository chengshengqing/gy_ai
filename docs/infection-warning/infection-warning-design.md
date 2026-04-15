# 院感预警与法官裁决设计

## 1. 文档定位

本文档是 `yg_ai` 院感预警、事件抽取和法官裁决链路的主文档，已合并原有主设计、Block 路由策略和事件抽取优化计划三类内容。

本文档只描述当前主链路、已落地能力和后续治理方向。详细表字段以 `docs/data/data-model-design.md` 为准；系统总览以 `docs/overview/architecture.md` 为准；患者原始 JSON 结构以 `docs/data/patient-raw-json-structure.md` 为准。

当前阶段明确不做：

- 最终审核 Agent 的正式实现
- 完整人工复核闭环
- 多级仲裁体系
- 事件版本链和完整生命周期审计表

## 2. 当前状态

当前院感预警基础链路已经具备以下能力：

- `infection_event_task(EVENT_EXTRACT)` 事件抽取任务
- `infection_event_task(CASE_RECOMPUTE)` 病例重算任务
- EvidenceBlock 构建
- Block 路由裁剪，减少无效 LLM 调用
- `StructuredFactRefinementService`
- `LlmEventExtractorService`
- `EventNormalizerService`
- `infection_event_pool` 标准化事件池
- `infection_llm_node_run` LLM 节点运行记录
- `infection_case_snapshot` 病例当前态快照
- `InfectionJudgeService` 院感法官基础节点
- `infection_alert_result` 预警结果版本落库
- 患者级 `CASE_RECOMPUTE` 防抖和局部重算

当前仍未创建：

- 院感预警专用 Controller
- `infection_node_result`

说明：

- `infection_node_result` 当前没有 Entity / Mapper / Service / SQL；如后续页面或评估链路需要高频按节点结果查询，再单独设计。
- `infection_pre_review_demo` 是 AI 智能预审临时演示快照表，不进入正式院感预警主链路。

## 3. 主链路

当前主链路如下：

```text
patient_raw_data
  -> infection_event_task(EVENT_EXTRACT)
  -> EventExtractHandler
  -> InfectionEvidenceBlockService.buildBlocks(...)
  -> ClinicalTextBlockBuilder 对 CLINICAL_TEXT 执行 LLM 候选原文片段选择
  -> 过滤空候选并按长度预算合并成功候选块
  -> Block 路由选择 primaryBlocks
  -> LlmEventExtractorService.extractAndSave(...)
  -> EventNormalizerService.normalize(...)
  -> InfectionEventPoolService.saveOrUpdateByEventKey(...)
  -> infection_event_task(CASE_RECOMPUTE)
  -> CaseRecomputeHandler
  -> InfectionEvidencePacketBuilder
  -> InfectionJudgeService
  -> infection_case_snapshot
  -> infection_alert_result
```

链路边界：

- 原始采集只更新事实和任务，不直接做院感结论。
- 结构化摘要链路通过 `patient_raw_data_change_task` 驱动 `NORMALIZE`。
- 院感预警链路通过 `infection_event_task` 驱动 `EVENT_EXTRACT` 和 `CASE_RECOMPUTE`。
- `patient_raw_data.event_json` 是时间线展示和事件抽取窗口上下文来源，不是事件池结果。
- 标准化事件只写入 `infection_event_pool`。

## 4. 输入层

### 4.1 `patient_raw_data`

`patient_raw_data` 是每日病例快照输入源。

关键字段分工：

- `data_json`
  保存每日聚合后的原始采集块。
- `filter_data_json`
  保存规则处理后的事实块，是结构化事实 EvidenceBlock 的主要来源。
- `struct_data_json`
  保存单日结构化中间结果，是中间语义 EvidenceBlock 的来源之一。
- `event_json`
  保存单日时间轴摘要，是时间线展示和窗口上下文来源。
- `last_time`
  表示该日快照对应业务源最后更新时间。
- `is_del`
  逻辑删除标记，主链路默认只消费 `is_del = 0` 的快照。

### 4.2 增量约束

院感预警链路必须遵守增量模式：

- 每日定时拉取增量数据
- 比较新旧快照
- 识别新增 / 更正 / 撤销
- 将差异转为标准化事件
- 只对受影响病例做局部重算

不得退化为每日全量重跑整个病例历史。

## 5. EvidenceBlock

EvidenceBlock 是事件抽取前的统一输入模型。

当前 Block 类型：

- `STRUCTURED_FACT`
- `CLINICAL_TEXT`
- `MID_SEMANTIC`
- `TIMELINE_CONTEXT`

当前构建类：

- `StructuredFactBlockBuilder`
- `ClinicalTextBlockBuilder`
- `MidSemanticBlockBuilder`
- `TimelineContextBlockBuilder`

当前构建入口：

```text
InfectionEvidenceBlockServiceImpl
  -> TimelineContextBlockBuilder
  -> StructuredFactBlockBuilder
  -> ClinicalTextBlockBuilder
  -> MidSemanticBlockBuilder
```

执行边界：

- `STRUCTURED_FACT`、`CLINICAL_TEXT` 属于 primary blocks，可执行事件抽取。
- `MID_SEMANTIC` 仅作为压缩上下文注入 primary blocks，不再单独执行事件抽取。
- `TIMELINE_CONTEXT` 只作为上下文存在，不单独执行事件抽取。
- `structuredFactBlocks` 可以只作为上下文存在，不一定进入本次 primaryBlocks 执行集合。
- Block 选择只影响“是否执行抽取”，不影响 `buildBlocks(...)` 的构建结果。

### 5.1 `STRUCTURED_FACT`

来源：

- `patient_raw_data.filter_data_json`

主要用途：

- 承载诊断、体征、检验、影像、医嘱、用药、转科、手术等结构化事实。
- 为 LLM 事件抽取提供可溯源事实。
- 作为 `CLINICAL_TEXT` 的结构化上下文。

当前会在部分 section 上先执行 `StructuredFactRefinementService`，用于将候选事实区分为：

- `priority_facts`
- `reference_facts`
- `raw`

适用 section：

- `doctor_orders`
- `use_medicine`
- `operation`

原则：

- 原始结构化事实优先。
- LLM 不得发明事实。
- 结构化事实必须保留 `source_section` 和可追溯 `source_text`。

### 5.2 `CLINICAL_TEXT`

来源：

- `patient_raw_data.data_json` 中的 `pat_illnessCourse`
- 或过滤后的病程文本块

主要用途：

- 识别病程文本中的感染相关表达。
- 识别“待排、考虑、不能除外、已排除”等语义。
- 在事件抽取前先通过 `ClinicalTextBlockBuilder` 调用 `AiGateway` 做候选原文片段选择，减少长病程正文噪声。
- 候选层输入只包含当前 `CLINICAL_TEXT` block 内的 `note_items`，不传入 `structuredContext`、`recentChangeContext` 等外部上下文。
- 候选层只接受 JSON 输出，`status` 只允许 `success / skipped`，`candidate_items` 必须为数组。
- 候选项只允许返回 `note_ref + source_text + reason_tag`，且 `source_text` 必须能回查到对应病程原文。
- `status=skipped` 或候选项为空时，删除该 `CLINICAL_TEXT` block，不再进入后续事件抽取 LLM。
- 候选层输出非空但不可追溯、JSON 非法或调用异常时，回退原始病程 block；回退 block 不参与后续候选合并。
- 成功候选 block 会按 `MAX_NOTE_TEXT_LENGTH=5000` 的文本预算继续合并为 `clinical_note_candidates_batch`，减少后续事件抽取 LLM 请求；超过预算时分批保留多个 block。

当前触发重点：

- 病程变化
- 新患者全量快照

### 5.3 `MID_SEMANTIC`

来源：

- `patient_raw_data.struct_data_json`
- 结构化摘要中的风险项、感染问题、待排感染等中间语义

主要用途：

- 承载结构化摘要阶段已经生成的中间语义。
- 为 `STRUCTURED_FACT` 和 `CLINICAL_TEXT` 提供压缩背景上下文。
- 不写入 `EvidenceBlockBuildResult`，不作为 primary block 单独触发事件抽取。

### 5.4 `TIMELINE_CONTEXT`

来源：

- `patient_raw_data.event_json`
- 最近窗口摘要上下文

主要用途：

- 提供 7 天窗口上下文。
- 帮助 LLM 判断是否存在新变化、持续趋势或反复描述。

限制：

- 不直接落入事件池。
- 不单独执行事件抽取。
- 只能作为其他 block 的辅助上下文。

## 6. Block 路由策略

`EVENT_EXTRACT` 当前根据 `infection_event_task.trigger_reason_codes` 和 `infection_event_task.changed_types` 选择需要执行的 primary blocks。

路由原则：

- `trigger_reason_codes` 是主路由依据。
- `changed_types` 是补充兜底与收窄依据。
- 任一字段命中即可执行对应 block。
- 两套字段都缺失时，回退执行全部 primary blocks。
- 解析后没有任何 block 被选中时，也回退执行全部 primary blocks，避免静默漏跑。

### 6.1 `STRUCTURED_FACT` 路由

命中以下 `trigger_reason_codes` 时执行：

- `LAB_RESULT_CHANGED`
- `MICROBE_CHANGED`
- `IMAGING_CHANGED`
- `ANTIBIOTIC_OR_ORDER_CHANGED`
- `OPERATION_CHANGED`
- `TRANSFER_CHANGED`
- `VITAL_SIGN_CHANGED`

命中以下 `changed_types` 时执行：

- `FULL_PATIENT`
- `DIAGNOSIS`
- `BODY_SURFACE`
- `DOCTOR_ADVICE`
- `LAB_TEST`
- `USE_MEDICINE`
- `VIDEO_RESULT`
- `TRANSFER`
- `OPERATION`
- `MICROBE`

说明：

- 这些变化直接对应结构化事实层。
- 只有 `ILLNESS_COURSE_CHANGED` 时，不执行 `STRUCTURED_FACT` 抽取。

### 6.2 `CLINICAL_TEXT` 路由

命中以下 `trigger_reason_codes` 时执行：

- `ILLNESS_COURSE_CHANGED`

命中以下 `changed_types` 时执行：

- `FULL_PATIENT`
- `ILLNESS_COURSE`

说明：

- 当前病程文本语义主要由病程变化触发。

### 6.3 `MID_SEMANTIC` 上下文

说明：

- `MID_SEMANTIC` 当前来自 `struct_data_json.day_context`，承载日级压缩语义。
- `MID_SEMANTIC` 不再作为 primary block 单独路由和抽取事件。
- `STRUCTURED_FACT` 仅注入去除 `note_semantics` 后的压缩客观上下文。
- `CLINICAL_TEXT` 仅注入当前 note 对应的 `note_semantics` 压缩上下文。

## 7. LLM 事件抽取

当前事件抽取入口：

```text
LlmEventExtractorServiceImpl
  -> LlmEventExtractionSupport
  -> AiGateway
  -> EventNormalizerService
  -> InfectionEventIngestionService
```

抽取规则：

- LLM 只从输入 EvidenceBlock 和上下文中抽取事件。
- 不允许凭空补全事实。
- `STRUCTURED_FACT` 必须输出真实 `source_section`。
- `CLINICAL_TEXT` 和 `MID_SEMANTIC` 不应伪造结构化来源。
- `TIMELINE_CONTEXT` 只能作上下文，不直接生成事件。

失败语义：

- 非法 JSON 必须失败。
- `status=skipped` 且 `events=[]` 可视为成功跳过。
- `status=success` 且 `events=[]` 应视为失败。
- `status=success` 且 LLM 输出非空，但 Normalizer 后全部丢弃，应视为失败。
- 单条事件部分失败但保留部分成功事件时，任务可成功，但必须记录 rejected count。

当前 `infection_llm_node_run.normalized_output_payload` 已记录：

- `EVENT_EXTRACTOR`：`raw/normalized/rejected/persisted`
- `STRUCTURED_FACT_REFINEMENT`：`section/candidate/promote/keep/drop/changed`
- `CASE_JUDGE`：`group/key/fallback`

## 8. Canonical Schema

当前最大治理目标是避免文档、Prompt、枚举、Normalizer、事件池实现之间出现多套定义。

单一真源：

- `InfectionEventSchema`
- `InfectionEventType`
- `InfectionEventSubtype`
- `InfectionClinicalMeaning`
- `InfectionBodySite`
- `InfectionSourceType`
- `InfectionEventStatus`
- `InfectionEvidenceRole`
- `InfectionEvidenceTier`
- `InfectionSourceSection`
- `InfectionEventCategory`

治理原则：

- Prompt、Normalizer、文档必须引用或镜像同一套定义。
- 写路径严格，读路径简单。
- 不为旧枚举值、旧字段值、旧 Prompt 输出做长期兜底。
- 当前没有历史生产数据，不保留旧数据兼容层。
- 模型输出与 Canonical Schema 不一致时，不在 `EventNormalizer` 中做隐式字段修复；应在对应 Prompt 或 Schema 设计层显式调整，并同步记录约束变化。

关键字段语义：

- `event_type`
  一级医学事实类型，只描述事实类别。
- `event_subtype`
  二级事件类型，只描述“这是什么事件”，不描述最终推理方向。
- `clinical_meaning`
  事件对感染判断的语义含义，不承载暴露类概念。
- `evidence_role`
  法官节点前的证据方向桶。
- `source_type`
  事件来源层级，使用 `raw / mid / summary / manual_patch`。
- `event_category`
  抽取来源类别，使用 `fact / text / semantic / context`。

说明：

- `structured_fact`、`clinical_text`、`mid_semantic` 是 block 类型或来源描述，不是 `source_type` 落库值。
- `risk_only / support / against / uncertain` 不允许作为 `clinical_meaning` 落库值。
- 详细 canonical 值清单以 `docs/data/data-model-design.md` 和代码枚举为准。

## 9. EventNormalizer

当前职责：

- 校验 LLM 输出 JSON 结构。
- 校验枚举值。
- 校验 `source_section` 与 block 类型边界。
- 校验 `event_type / event_subtype / clinical_meaning` 组合关系。
- 校验 `source_text` 是否可追溯。
- 生成 `NormalizedInfectionEvent`。
- 构造 `event_key`。
- 生成 `attributes_json` 和 `evidence_json`。

当前护栏：

- 块类型固定。
- 输出 Schema 强约束。
- timeline 只作辅助背景。
- 原始结构化事实优先。
- 非法 JSON、关键字段非法、全部事件被丢弃时必须显式失败。

当前策略：

- `EventNormalizer` 只负责协议校验、标准化落库对象构造和护栏拒绝。
- 不在 `EventNormalizer` 中放宽 canonical schema，也不在此处兜底模型字段漂移。
- 模型输出与 Canonical Schema 不一致时，`EventNormalizer` 保持拒绝语义；字段语义变化应在 Prompt 或 Schema 设计层显式处理。

## 10. 事件池

`infection_event_pool` 是院感分析的标准化事件池。

当前定位：

- 当前标准化事件池
- 按 `event_key` 幂等写入
- 同一 `event_key` 命中时原地更新
- `event_key` 包含 `EvidenceBlock.blockKey` 维度；同一 block 重试入池前按 `event_key` block 前缀清理本 block 旧事件
- 不引入追加式版本记录
- 不以 `superseded` 为主流程设计核心

当前不做：

- 事件版本链
- `superseded/revoked` 完整生命周期建模
- 追加式历史事件审计表

如果未来需要事件生命周期审计，应单独升级为版本表，不在当前最新态模型上叠加伪版本字段语义。

## 11. 病例重算与防抖

`CASE_RECOMPUTE` 当前是患者级任务。

任务合并键：

```text
CASE_RECOMPUTE:{reqno}
```

当前防抖字段：

- `first_triggered_at`
- `last_event_at`
- `debounce_until`
- `trigger_priority`
- `event_pool_version_at_enqueue`

当前策略：

- 事件抽取产生有效事件后，触发或更新患者级 `CASE_RECOMPUTE`。
- 重复触发时按患者合并。
- 到达 `debounce_until` 后再执行病例重算。
- 执行前校验事件池版本，避免用过期任务重复裁决。

## 12. 法官裁决

当前法官链路：

```text
CaseRecomputeHandler
  -> InfectionEvidencePacketBuilder
  -> InfectionJudgeService
  -> InfectionCaseJudgeSupport
  -> AiGateway
  -> infection_case_snapshot
  -> infection_alert_result
```

### 12.1 证据包

`InfectionEvidencePacket` 是法官压缩输入模型。

当前主结构：

- `eventCatalog`
- `evidenceGroups`
- `decisionBuckets`
- `backgroundSummary`

压缩原则：

- 不在 packet 中重复展开 `newEvents / activeEvents / supportingEvidence / againstEvidence / riskContext`。
- 法官只允许引用 packet 中存在的合法 `eventKey`。
- `decisionBuckets` 只提供预分桶，不替代最终裁决。

### 12.2 法官输出

当前 `JudgeDecisionResult` 输出核心字段：

- `decisionStatus`
- `warningLevel`
- `primarySite`
- `nosocomialLikelihood`
- `newOnsetFlag`
- `after48hFlag`
- `procedureRelatedFlag`
- `deviceRelatedFlag`
- `infectionPolarity`
- `decisionReason`
- `newSupportingKeys`
- `newAgainstKeys`
- `newRiskKeys`
- `dismissedKeys`
- `requiresFollowUp`
- `nextSuggestedJudgeAt`
- `resultVersion`

临时演示字段：

- `missingEvidenceReminders`
- `aiSuggestions`

说明：

- 演示字段只写入 `infection_alert_result.result_json`，不改变正式表结构。
- 最终审核 Agent 当前只做规划，不进入正式实现。

### 12.3 快照与结果

`infection_case_snapshot` 保存病例当前态：

- `case_state`
- `warning_level`
- `primary_site`
- `nosocomial_likelihood`
- active event / risk / against keys
- 最近裁决时间
- 最近结果版本
- 最近事件池版本

`infection_alert_result` 保存结果版本：

- `alert_status`
- `overall_risk_level`
- `primary_site`
- `result_json`
- `diff_json`
- `source_snapshot_id`

当前说明：

- `alert_status` 当前直接写入法官输出的 `decisionStatus`，与 `case_state` 同口径。
- `overall_risk_level` 当前直接写入法官输出的 `warningLevel`。
- `result_json` 保存法官完整输出。
- `diff_json` 当前保存 packet / 差异上下文，后续需细化为稳定 diff 结构。

## 13. LLM 运行记录

院感预警相关 LLM 节点统一记录到 `infection_llm_node_run`。

当前实际落地节点：

- `EVENT_EXTRACTOR`
- `STRUCTURED_FACT_REFINEMENT`
- `CASE_JUDGE`

预留节点：

- `NEW_ONSET_JUDGE`
- `AFTER_48H_JUDGE`
- `PROCEDURE_ASSOCIATION_JUDGE`
- `INFECTION_POLARITY_JUDGE`
- `SITE_ATTRIBUTION_JUDGE`
- `SEVERITY_JUDGE`
- `EXPLANATION_GENERATOR`
- `FINAL_AUDIT_PLANNING_ONLY`

说明：

- `FINAL_AUDIT_PLANNING_ONLY` 仅作为规划枚举，不进入正式运行。
- 当前不新增最终审核 Agent 运行结果表。

## 14. 后续治理顺序

后续优先级：

1. 治理 `infection_event_pool` Canonical Schema 与代码枚举 / Prompt / Normalizer 一致性。
2. 保持 `EventNormalizer` 与 Canonical Schema / Prompt 输出协议一致。
3. 细化 `infection_alert_result.diff_json` 的稳定结构。
4. 完善医生可读解释和页面展示字段。
5. 评估是否需要 `infection_node_result`。
6. 规划最终审核 Agent 的输入输出契约，但不正式实现。

后续每一项优化都必须遵守：

1. 先改 schema / enum / 常量定义。
2. 再改 normalizer。
3. 再改 prompt。
4. 再改 builder / service。
5. 最后更新文档。

禁止继续出现：

- 先改 prompt，后补 schema。
- 某个字段只在 normalizer 改，prompt 不改。
- 文档和代码长期分叉。

## 15. 验收标准

事件抽取：

- 输入某个 `reqno + data_date`，能成功生成事件并写入 `infection_event_pool`。
- 同一批重复执行，不产生重复事件，`event_key` 保持 block 级幂等。
- 至少覆盖 `STRUCTURED_FACT`、`CLINICAL_TEXT`、`MID_SEMANTIC`。
- `TIMELINE_CONTEXT` 不直接落入事件池，只作为上下文。
- LLM 返回非法 JSON 时写入 `infection_llm_node_run` 错误记录，并触发显式失败或确定性 fallback。
- 低置信度或非法事件能正确标记、丢弃或失败。

病例裁决：

- 事件池更新后只对受影响病例做局部重算。
- `CASE_RECOMPUTE` 按患者合并并执行防抖。
- 法官输出必须只引用合法 `eventKey`。
- `infection_case_snapshot` 能维护当前态。
- `infection_alert_result` 能输出可回溯的结果版本。

## 16. 一句话结论

当前院感预警分析已经具备“标准化事件池 + 病例状态快照 + LLM 法官节点 + 结果版本化”的基础链路。后续重点是治理事件标准化、结果 diff、展示联动和人工复核前置能力，而不是再造全量分析链路。
