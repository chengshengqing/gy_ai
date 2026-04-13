# 数据模型实施文档

## 1. 文档目标

本文档用于定义院感预警分析阶段的核心数据模型，明确：

- 现有表在新架构中的角色
- 当前已经落地哪些表
- 每张表的职责是什么
- 表之间如何关联
- 后续仍需补充哪些模型

本文档优先服务 Codex agent 模式下的后续开发，不追求一次性覆盖所有最终字段。

说明：

- 本文档当前以代码和 SQL 文件已经落地的表结构为基线
- 若与历史“院感预警分析需求文档”存在字段级差异，应优先以当前代码和 SQL 文件为准
- 本文档同时记录当前已经落地的采集任务表与变更任务表职责

## 2. 现有数据模型角色

## 2.1 `patient_raw_data`

角色：

- 每日病例快照输入源

主键语义：

- 业务上按 `reqno + data_date` 理解

当前关键字段：

- `reqno`
- `data_date`
- `data_json`
- `filter_data_json`
- `struct_data_json`
- `event_json`
- `clinical_notes`
- `last_time`
- `is_del`

在新架构中的定位：

- 每日增量事实源
- 差异识别输入源
- EvidenceBlock 的主要来源

当前字段边界补充：

- `data_json`
  只保存原始采集块，包含顶层字段 `admission_time`、`patient_summary`，以及 `pat_diagInfor`、`pat_bodySurface`、`pat_doctorAdvice_*`、`pat_illnessCourse`、`pat_testSam`、`pat_useMedicine`、`pat_videoResult`、`pat_transfer`、`pat_opsCutInfor`、`pat_test`
- `filter_data_json`
  保存规则处理后的事实块，当前包含顶层字段 `admission_time`、`patient_summary`，以及 `patient_info`、`diagnosis`、`vital_signs`、`lab_results`、`imaging`、`doctor_orders`、`use_medicine`、`transfer`、`operation`、`clinical_notes`、过滤后的 `pat_illnessCourse`
- `struct_data_json`
  保存单日结构化中间结果，不再重复存放单日摘要内容
- `event_json`
  当前已切换为“单日时间轴摘要”字段，不再保存事件抽取结果
- `last_time`
  当前已重新启用，表示该日 `patient_raw_data` 最后一次更新时间
- `is_del`
  逻辑删除标记，`0` 表示有效快照，`1` 表示删除；所有 `PatientRawDataEntity` 查询默认只返回 `is_del = 0`

## 2.2 `patient_raw_data_collect_task`

角色：

- 原始数据采集任务表

当前职责：

- 记录待采集患者 `reqno`
- 记录采集任务状态、重试次数、可执行时间
- 记录本轮识别出的 `change_types`

当前关键字段：

- `reqno`
- `status`
- `attempt_count`
- `max_attempts`
- `source_last_time`
- `change_types`
- `available_at`
- `last_start_time`
- `last_finish_time`
- `last_error_message`

在当前架构中的定位：

- 原始采集两段式调度的中间任务表
- 第一阶段负责“扫描入队”
- 第二阶段负责“claim 执行采集”

说明：

- 这张表只负责“患者级采集任务”
- 不负责下游摘要、预警、审核等业务任务

## 2.3 `patient_raw_data_change_task`

角色：

- 原始数据变更消费任务表

当前职责：

- 记录每次被新增或修改的 `patient_raw_data` 行
- 以 `patient_raw_data_id + raw_data_last_time` 形式向下游发布变更信号
- 作为结构化摘要链路的直接消费任务表
- 不再承担事件抽取链路的直接消费

当前关键字段：

- `patient_raw_data_id`
- `reqno`
- `data_date`
- `source_batch_time`
- `status`
- `attempt_count`
- `max_attempts`
- `raw_data_last_time`
- `available_at`
- `last_start_time`
- `last_finish_time`
- `last_error_message`
- `create_time`
- `update_time`

在当前架构中的定位：

- 原始采集层与下游消费者的解耦桥梁
- 当前结构化摘要已经消费这张表，而不是直接耦合采集流程
- 事件抽取链路已经从这张表剥离，改为消费 `infection_event_task`

当前实现补充：

- 采集阶段会写 `patient_raw_data` 并追加 `patient_raw_data_change_task`
- 结构化阶段 claim 这张表
- 对同一患者本轮 claim 到的变更行，先校验 `raw_data_last_time` 是否仍与 `patient_raw_data.last_time` 一致
- 仅处理仍然有效的单日变更行，不再做“从最早变更日开始”的整段重放
- 结构化调度前会巡检最近窗口的 `patient_raw_data`，补建 `appendChanges()` 漏掉的任务

## 2.4 `infection_event_task`

角色：

- 预警主链路任务表

当前职责：

- 承载 `EVENT_EXTRACT` 任务
- 承载 `CASE_RECOMPUTE` 任务
- 将事件抽取与结构化链路彻底解耦

当前关键字段：

- `task_type`
- `status`
- `reqno`
- `patient_raw_data_id`
- `data_date`
- `raw_data_last_time`
- `source_batch_time`
- `changed_types`
- `trigger_reason_codes`
- `priority`
- `merge_key`
- `attempt_count`
- `max_attempts`
- `available_at`
- `last_start_time`
- `last_finish_time`
- `last_error_message`
- `create_time`
- `update_time`

当前实现补充：

- `EVENT_EXTRACT` 的 `merge_key` 使用 `patient_raw_data_id + raw_data_last_time`
- `CASE_RECOMPUTE` 的 `merge_key` 已改为患者级：
  - `CASE_RECOMPUTE:{reqno}`
- `CASE_RECOMPUTE` 已新增防抖字段：
  - `first_triggered_at`
  - `last_event_at`
  - `debounce_until`
  - `trigger_priority`
  - `event_pool_version_at_enqueue`
- 候选层不再额外调用 LLM，只按变更来源写入 `trigger_reason_codes`
- `ILLNESS_COURSE` 新增会直接路由到 `EVENT_EXTRACT`

## 2.5 `ai_process_log`

角色：

- 现有 AI 调用日志表

在新架构中的定位：

- 可以继续用于已有链路日志
- 不建议直接复用为院感预警节点运行日志主表

原因：

- 院感预警后续会有更多节点类型
- 需要更细粒度的运行记录

## 2.6 `items_infor_zhq`

角色：

- 症候群监测业务结果表

在新架构中的定位：

- 保持独立用途
- 不与院感预警结果表混用

## 3. 院感数据模型落地状态

当前院感分析主链路已落地 5 张正式表：

1. `infection_event_pool`
2. `infection_llm_node_run`
3. `infection_case_snapshot`
4. `infection_alert_result`
5. `infection_daily_job_log`

当前关系：

```text
patient_raw_data
    -> infection_event_task(EVENT_EXTRACT)
    -> infection_event_pool
    -> infection_event_task(CASE_RECOMPUTE)
    -> infection_case_snapshot
    -> infection_alert_result

infection_event_pool
    -> infection_case_snapshot
    -> infection_alert_result

infection_llm_node_run
    -> infection_event_pool
    -> infection_alert_result
```

说明：

- `patient_raw_data_collect_task`
- `patient_raw_data_change_task`
- `infection_event_task`

这 3 张表属于当前采集与下游处理的任务层，不纳入院感预警核心结果表统计。

- `infection_event_pool`
- `infection_llm_node_run`
- `infection_case_snapshot`
- `infection_alert_result`

这 4 张表是院感预警结果层的核心表，当前已经具备 Entity / Mapper / Service 基础骨架。

补充说明：

- `infection_daily_job_log` 已落地，用于批处理和任务运维记录。
- `infection_node_result` 当前没有 Entity / Mapper / Service / SQL，不属于已落地主链路；如后续确实需要节点结果快速查询，再单独设计。
- `infection_pre_review_demo` 是 AI 智能预审临时演示快照表，不纳入正式院感预警主链路。

## 4. `infection_event_pool`

## 4.1 表职责

`infection_event_pool` 是院感事件抽取链路的标准化事件池。

当前阶段采用“最新态事件池”设计：

- 按 `event_key` 幂等写入
- 同一 `event_key` 命中时原地更新
- 当前不维护事件版本链
- 当前不把 `superseded` 作为主流程能力

它承担的职责是：

- 保存标准化后的事件
- 作为病例级法官节点的主输入
- 作为病例快照与预警结果的上游事实层

## 4.2 Canonical Schema

### 主键与关联字段

- `id`
- `reqno`
- `raw_data_id`
- `data_date`
- `source_type`
- `source_ref`

### 事件身份字段

- `event_key`
- `event_type`
- `event_subtype`
- `event_category`

### 时间字段

- `event_time`
- `detected_time`
- `ingest_time`

### 医学语义字段

- `site`
- `polarity`
- `certainty`
- `severity`
- `is_hard_fact`
- `is_active`

### 内容字段

- `title`
- `content`
- `evidence_json`
- `attributes_json`

### 追踪字段

- `extractor_type`
- `prompt_version`
- `model_name`
- `confidence`
- `status`

### 审计字段

- `created_at`
- `updated_at`

### Canonical 枚举定义

### `source_type`

当前标准值：

- `raw`
- `mid`
- `summary`
- `manual_patch`

说明：

- `source_type` 表示落库事件的来源层级
- 不使用 `structured_fact`、`clinical_text`、`mid_semantic` 这类 block 概念作为落库值

### `event_type`

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

说明：

- `event_type` 是一级事件类型
- 只描述事实类别，不承载细粒度临床语义

### `event_subtype`

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

说明：

- `event_subtype` 是二级事件类型
- 只描述“这是什么事件”，不描述最终推理方向

### `clinical_meaning`

当前标准值：

- `infection_support`
- `infection_against`
- `infection_uncertain`
- `screening`
- `baseline_problem`

说明：

- `clinical_meaning` 只描述事件对感染判断的语义含义
- 不再承载 `device_exposure`、`procedure_exposure` 这类暴露概念

### `body_site`

当前标准值：

- `urinary`
- `respiratory`
- `upper_respiratory`
- `lower_respiratory`
- `pleural`
- `cardiac_valve`
- `myocardial_pericardial`
- `mediastinum`
- `vascular`
- `bloodstream`
- `blood`
- `gastrointestinal`
- `abdominal`
- `intra_abdominal`
- `central_nervous_system`
- `surgical_site`
- `superficial_incision`
- `deep_incision`
- `organ_space`
- `skin_soft_tissue`
- `burn`
- `joint`
- `bone_joint`
- `genital`
- `eye_ear_oral`
- `systemic`
- `unknown`
- `other`

### `abnormal_flag`

当前标准值：

- `high`
- `low`
- `positive`
- `negative`
- `abnormal`
- `normal`

说明：

- 只用于表达原始检验或结果文本中的异常标记
- 允许为空
- 不替代 `clinical_meaning` 或 `evidence_role`

### `status`

当前标准值：

- `active`
- `revoked`
- `superseded`
- `invalid`

说明：

- 当前写路径主用 `active`
- 其余状态保留为扩展值，不作为当前主流程能力

## 4.3 Implementation Notes

### `event_key`

用途：

- 幂等写入
- 去重
- 作为病例快照版本比较的基础键

当前实现规则：

- `reqno + "|" + data_date + "|" + source_type + "|" + source_module + "|" + short_hash(business_key)`

其中 `business_key` 当前由以下字段组合：

- `source_section`
- `event_type`
- `event_subtype`
- `event_time`
- `site`
- `title`
- `content`

### `event_category`

当前标准值：

- `fact`
- `text`
- `semantic`
- `context`

说明：

- `fact` 对应 `STRUCTURED_FACT`
- `text` 对应 `CLINICAL_TEXT`
- `semantic` 对应 `MID_SEMANTIC`
- `context` 对应 `TIMELINE_CONTEXT`

### `attributes_json`

当前固定承载的扩展字段包括：

- `event_value`
- `event_unit`
- `abnormal_flag`
- `infection_related`
- `negation_flag`
- `uncertainty_flag`
- `clinical_meaning`
- `evidence_tier`
- `evidence_role`
- `source_section`

## 4.4 Future Evolution

当前不做：

- 事件版本链
- `superseded` 驱动的主流程状态迁移
- 追加式历史事件审计表

如果未来需要事件生命周期审计，应单独升级 `infection_event_pool` 为版本表，而不是继续在当前最新态模型上叠加伪版本字段语义。

## 4.5 当前落地与索引

- `uk_infection_event_pool_event_key`
- `idx_infection_event_pool_reqno_time`

当前实现说明：

- Entity：`InfectionEventPoolEntity`
- Mapper：`InfectionEventPoolMapper`
- Service：`InfectionEventPoolService` / `InfectionEventPoolServiceImpl`
- 建表 SQL：`src/main/resources/sql/infection_refactor_init.sql`

后续可按查询压力补充：

- `idx_infection_event_pool_reqno_type`
- `idx_infection_event_pool_reqno_active`
- `idx_infection_event_pool_raw_data_id`

## 5. `infection_llm_node_run`

## 5.1 表职责

记录所有院感预警相关 LLM 节点运行过程。

作用：

- 回溯输入输出
- 对比 Prompt 版本
- 支持调试和评估
- 支持失败排查

## 5.2 当前落地字段

### 主键与关联字段

- `id`
- `reqno`
- `raw_data_id`
- `alert_result_id`
- `node_run_key`

### 节点信息

- `node_type`
- `node_name`
- `prompt_version`
- `model_name`

### 输入输出

- `input_payload`
- `output_payload`
- `normalized_output_payload`

### 运行结果

- `status`
- `confidence`
- `latency_ms`
- `retry_count`

### 错误信息

- `error_code`
- `error_message`

### 审计字段

- `created_at`
- `updated_at`

当前实现说明：

- Entity：`InfectionLlmNodeRunEntity`
- Mapper：`InfectionLlmNodeRunMapper`
- Service：`InfectionLlmNodeRunService` / `InfectionLlmNodeRunServiceImpl`
- 建表 SQL：`src/main/resources/sql/infection_refactor_init.sql`
- 当前未落地 `data_date`、`input_hash`、`token_in`、`token_out` 字段；如后续需要 token 统计或输入去重，应在该表现有字段上增量扩展。

## 5.3 `node_type` 当前枚举

当前代码中的正式取值为：

- `EVENT_EXTRACTOR`
- `STRUCTURED_FACT_REFINEMENT`
- `CASE_JUDGE`
- `NEW_ONSET_JUDGE`
- `AFTER_48H_JUDGE`
- `PROCEDURE_ASSOCIATION_JUDGE`
- `INFECTION_POLARITY_JUDGE`
- `SITE_ATTRIBUTION_JUDGE`
- `SEVERITY_JUDGE`
- `EXPLANATION_GENERATOR`
- `FINAL_AUDIT_PLANNING_ONLY`

说明：

- 当前已实际落地的节点为：
  - `EVENT_EXTRACTOR`
  - `STRUCTURED_FACT_REFINEMENT`
  - `CASE_JUDGE`
- `FINAL_AUDIT_PLANNING_ONLY` 仅作为预留枚举
- 当前阶段不实际运行

## 5.4 `normalized_output_payload` 当前结构

该字段当前统一承载：

- `stats`
- 节点级结果对象
- 可选错误信息

### `EVENT_EXTRACTOR`

当前结构：

```json
{
  "stats": {
    "raw_event_count": 3,
    "normalized_event_count": 2,
    "rejected_event_count": 1,
    "persisted_event_count": 2
  },
  "events": [
    {
      "eventKey": "..."
    }
  ],
  "error_message": "..."
}
```

说明：

- `raw_event_count` 表示 LLM 原始输出中 `events` 数组长度
- `normalized_event_count` 表示通过 `EventNormalizer` 的事件数
- `rejected_event_count` 表示 `raw - normalized`
- `persisted_event_count` 表示真正写入 `infection_event_pool` 的事件数
- 失败时 `events` 为空，并额外记录 `error_message`

### `STRUCTURED_FACT_REFINEMENT`

当前结构：

```json
{
  "stats": {
    "raw_section_count": 1,
    "raw_candidate_count": 4,
    "promoted_candidate_count": 1,
    "kept_reference_count": 1,
    "dropped_candidate_count": 2,
    "changed_section_count": 1
  },
  "refinements": [
    "doctor_orders"
  ],
  "error_message": "..."
}
```

说明：

- `raw_section_count` 表示本次参与 refinement 的 section 数
- `raw_candidate_count` 表示全部 candidate 数
- `promoted_candidate_count / kept_reference_count / dropped_candidate_count` 表示模型返回的动作数量
- `changed_section_count` 表示真正发生 `priority_facts/reference_facts` 变更的 section 数

### `CASE_JUDGE`

当前结构：

```json
{
  "stats": {
    "raw_group_count": 5,
    "support_group_count": 2,
    "against_group_count": 1,
    "risk_group_count": 1,
    "new_group_count": 2,
    "referenced_key_count": 8,
    "selected_supporting_key_count": 2,
    "selected_against_key_count": 1,
    "selected_risk_key_count": 1,
    "dismissed_key_count": 0,
    "fallback_used": false
  },
  "decision": {
    "decisionStatus": "candidate"
  },
  "error_message": "..."
}
```

说明：

- `raw_group_count` 表示法官输入中的 `evidenceGroups` 数
- `support_group_count / against_group_count / risk_group_count / new_group_count` 来自 `decisionBuckets`
- `referenced_key_count` 表示法官可引用的合法 `eventKey` 数
- `selected_*_key_count` 与 `dismissed_key_count` 表示法官实际输出采用的 key 数
- `fallback_used=true` 表示本次法官调用失败，最终写入的是确定性 fallback 结果

## 6. `infection_case_snapshot`

## 6.1 表职责

保存每个病例当前最新的院感状态快照。

作用：

- 避免每次从全历史重建状态
- 支持局部重算
- 支持页面快速展示

## 6.2 当前落地字段

- `id`
- `reqno`
- `case_state`
- `warning_level`
- `primary_site`
- `nosocomial_likelihood`
- `current_new_onset_flag`
- `current_after_48h_flag`
- `current_procedure_related_flag`
- `current_device_related_flag`
- `current_infection_polarity`
- `active_event_keys_json`
- `active_risk_keys_json`
- `active_against_keys_json`
- `last_judge_time`
- `last_result_version`
- `last_event_pool_version`
- `last_candidate_since`
- `last_warning_since`
- `judge_debounce_until`
- `created_at`
- `updated_at`

说明：

- 当前代码已按这组最小字段落地
- 其目标是先支撑：
  - 患者级防抖
  - 增量裁决
  - 当前态维护
- Entity：`InfectionCaseSnapshotEntity`
- Mapper：`InfectionCaseSnapshotMapper`
- Service：`InfectionCaseSnapshotService` / `InfectionCaseSnapshotServiceImpl`
- 建表 SQL：`src/main/resources/sql/infection_case_snapshot.sql`
- 当前未落地 `snapshot_json`、`last_raw_id`、`last_summary_id` 字段；病例当前态主要通过 active key JSON、最近裁决时间和事件池版本维护。
- 更丰富的展示字段后续可再扩展

## 6.3 当前受控值

### `case_state`

- `no_risk`
- `candidate`
- `warning`
- `resolved`

### `warning_level`

- `none`
- `low`
- `medium`
- `high`

### `nosocomial_likelihood`

- `low`
- `medium`
- `high`

## 7. `infection_alert_result`

## 7.1 表职责

记录每次院感预警分析输出的版本化结果。

作用：

- 解释今天为什么报警
- 与上个版本做比较
- 支持页面展示和人工复核

## 7.2 当前落地字段

当前代码已先落最小版本：

- `id`
- `reqno`
- `data_date`
- `result_version`
- `alert_status`
- `overall_risk_level`
- `primary_site`
- `new_onset_flag`
- `after_48h_flag`
- `procedure_related_flag`
- `device_related_flag`
- `infection_polarity`
- `result_json`
- `diff_json`
- `source_snapshot_id`
- `create_time`

说明：

- `result_json` 当前保存法官节点完整输出
- `diff_json` 当前仍是占位，用于先保留 packet/差异上下文
- Entity：`InfectionAlertResultEntity`
- Mapper：`InfectionAlertResultMapper`
- Service：`InfectionAlertResultService` / `InfectionAlertResultServiceImpl`
- 建表 SQL：`src/main/resources/sql/infection_alert_result.sql`
- 后续可再扩展：
  - `main_judge_node_run_id`
  - `doctor_readable_explanation`
  - 更细的 diff 结构

## 7.3 `alert_status` 当前枚举

- `no_risk`
- `candidate`
- `warning`
- `resolved`

说明：

- 当前 `alert_status` 直接写入法官输出的 `decisionStatus`，与 `infection_case_snapshot.case_state` 同口径。

## 7.4 `overall_risk_level` 当前枚举

- `none`
- `low`
- `medium`
- `high`

## 8. `infection_daily_job_log`

## 8.1 表职责

保存每日批处理执行情况，用于任务运维和失败定位。

当前已落地，执行日志写入仍保持轻量用途，不承载业务结果。

## 8.2 当前落地字段

- `id`
- `job_date`
- `reqno`
- `stage`
- `status`
- `message`
- `create_time`

当前实现说明：

- Entity：`InfectionDailyJobLogEntity`
- Mapper：`InfectionDailyJobLogMapper`
- Service：`InfectionDailyJobLogService` / `InfectionDailyJobLogServiceImpl`
- 建表 SQL：`src/main/resources/sql/infection_daily_job_log.sql`

### `stage` 当前枚举

- `load`
- `normalize`
- `llm`
- `finalize`
- `snapshot`

### `status` 当前枚举

- `success`
- `skip`
- `error`

## 9. `infection_pre_review_demo`

## 9.1 表职责

`infection_pre_review_demo` 是 AI 智能预审临时演示快照表。

作用：

- 按 `reqno` 保存患者时间线 HTML 快照
- 按 `reqno` 保存 AI 智能预审 JSON
- 供外部演示程序直接查询展示

说明：

- 该表不承载正式业务状态
- 不参与现有院感结果版本化
- 不进入院感预警正式主链路

## 9.2 当前落地字段

- `reqno`
- `timeline_html`
- `ai_pre_review_json`

当前实现说明：

- Entity：`InfectionPreReviewDemoEntity`
- Mapper：`InfectionPreReviewDemoMapper`
- Service：`InfectionPreReviewDemoSnapshotService`
- 建表 SQL：`src/main/resources/sql/infection_pre_review_demo.sql`

## 9.3 `infection_node_result` 当前状态

`infection_node_result` 当前未落地：

- 没有 Entity
- 没有 Mapper
- 没有 Service
- 没有 SQL

原因：

- 当前 `infection_llm_node_run.normalized_output_payload` 已经承载节点级归一化输出和统计信息
- `infection_alert_result.result_json` 已经承载病例法官完整结果
- 当前阶段还没有必须为节点结果单独建表的查询场景

如后续页面或评估链路需要高频按节点结果查询，再单独设计 `infection_node_result`，不作为当前主链路的一部分。

## 10. 当前对象模型状态

除数据库表外，当前已在代码中定义一批中间对象，降低实现耦合。

已落地对象：

- `EvidenceBlock`
- `NormalizedInfectionEvent`
- `InfectionEvidencePacket`
- `InfectionJudgeInput`
- `InfectionJudgeContext`
- `InfectionJudgePrecompute`
- `InfectionRecentChanges`
- `JudgeDecisionResult`
- `JudgeDecisionBuckets`
- `JudgeEvidenceGroup`
- `JudgeCatalogEvent`
- `LlmEventExtractorResult`

已落地构建类：

- `StructuredFactBlockBuilder`
- `ClinicalTextBlockBuilder`
- `MidSemanticBlockBuilder`
- `TimelineContextBlockBuilder`

当前补充：

- `InfectionEvidencePacket` 已改为法官压缩输入模型
- 不再在 packet 中重复展开 `newEvents / activeEvents / supportingEvidence / againstEvidence / riskContext`
- 当前 packet 主结构为：
  - `eventCatalog`
  - `evidenceGroups`
  - `decisionBuckets`
  - `backgroundSummary`

## 11. Mapper / Service 当前落地状态

### `domain/entity`

- `InfectionEventPoolEntity`
- `InfectionLlmNodeRunEntity`
- `InfectionCaseSnapshotEntity`
- `InfectionAlertResultEntity`
- `InfectionDailyJobLogEntity`
- `InfectionPreReviewDemoEntity`

说明：

- 正式院感主链路表已具备 Entity。
- `InfectionPreReviewDemoEntity` 只服务临时演示。
- `InfectionNodeResultEntity` 当前不存在。

### `mapper`

- `InfectionEventPoolMapper`
- `InfectionLlmNodeRunMapper`
- `InfectionCaseSnapshotMapper`
- `InfectionAlertResultMapper`
- `InfectionDailyJobLogMapper`
- `InfectionPreReviewDemoMapper`

### `service`

- `InfectionEventPoolService`
- `InfectionLlmNodeRunService`
- `InfectionCaseSnapshotService`
- `InfectionAlertResultService`
- `InfectionDailyJobLogService`
- `InfectionEventIngestionService`

### `service/impl`

- `InfectionEventPoolServiceImpl`
- `InfectionLlmNodeRunServiceImpl`
- `InfectionCaseSnapshotServiceImpl`
- `InfectionAlertResultServiceImpl`
- `InfectionDailyJobLogServiceImpl`
- `InfectionEventIngestionServiceImpl`
- `InfectionPreReviewDemoSnapshotService`

说明：

- 第一阶段的 Mapper 和基础 Service 已经建出。
- 当前复杂业务编排主要落在 `EventExtractHandler`、`CaseRecomputeHandler`、`LlmEventExtractorServiceImpl`、`InfectionJudgeServiceImpl` 和对应 support 类中。
- 后续新增持久化对象时，仍优先沿 Entity / Mapper / Service / ServiceImpl 小步扩展。

## 12. 与现有表的关系

## 12.1 `patient_raw_data` 与 `infection_event_pool`

当前关系：

- 一条 `patient_raw_data` 可产生多条 `infection_event_pool`

当前保留字段：

- `raw_data_id`
- `source_ref`

## 12.2 `infection_case_snapshot` 与 `infection_alert_result`

当前关系：

- 快照保存“当前态”
- 结果表保存“历史版本”

## 13. 当前表结构状态

已落地：

- `infection_event_pool`
- `infection_llm_node_run`
- `infection_case_snapshot`
- `infection_alert_result`
- `infection_daily_job_log`

临时演示表：

- `infection_pre_review_demo`

未落地：

- `infection_node_result`

当前说明：

- `infection_event_pool` 与 `infection_llm_node_run` 的建表 SQL 在 `src/main/resources/sql/infection_refactor_init.sql`。
- `infection_case_snapshot`、`infection_alert_result`、`infection_daily_job_log`、`infection_pre_review_demo` 已有独立 SQL 文件。
- 后续如果要补 `infection_node_result`，应先明确查询场景，避免和 `infection_llm_node_run.normalized_output_payload` 重复。

## 14. 当前不落地的模型

以下对象当前只做规划，不做正式实现：

- 最终审核 Agent 运行结果表
- 多级人工复核流程表
- 多角色审核记录表

原因：

- 当前阶段优先完善已经落地的院感预警基础链路，不进入最终审核和人工复核闭环的正式建模

## 15. 代码与配置一致性要求

- 表中受控值要优先落为枚举类
- Java 枚举、数据库枚举、Prompt 枚举要保持一致
- 如与 `src/main/resources/timeline-view-rules.yaml`、`src/main/java/com/zzhy/yg_ai/config/TimelineViewRuleProperties.java` 存在共用语义，应保持命名一致或建立清晰映射

## 16. Codex 开发建议

后续让 Codex agent 开发时，建议按以下顺序拆任务：

1. 不再重复创建已落地的 5 张正式表骨架。
2. 优先补齐 `infection_event_pool` Canonical Schema 与代码枚举 / Prompt / Normalizer 的一致性。
3. 继续完善 `infection_llm_node_run` 的运行统计和失败定位能力。
4. 完善 `infection_case_snapshot` 与 `infection_alert_result` 的 diff 语义和页面联动字段。
5. 只有出现明确查询场景时，再考虑 `infection_node_result`。

每一步都要保证：

- 幂等
- 可追踪
- 可回放
- 可扩展

## 17. 一句话结论

当前数据模型建设已经完成院感预警基础表骨架，后续重点不是继续新增表，而是治理 `infection_event_pool` 的 Canonical Schema、完善 `infection_case_snapshot` 当前态语义、细化 `infection_alert_result` 版本 diff，并让 `infection_llm_node_run` 成为稳定的可观测支撑。
