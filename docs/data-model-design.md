# 数据模型实施文档

## 1. 文档目标

本文档用于定义院感预警分析阶段的核心数据模型，明确：

- 现有表在新架构中的角色
- 需要新增哪些表
- 每张表的职责是什么
- 表之间如何关联
- 应优先落哪些字段

本文档优先服务 Codex agent 模式下的后续开发，不追求一次性覆盖所有最终字段。

说明：

- 本文档的概念设计用于说明模型职责与关系
- 若与后续“院感预警分析需求文档”存在字段级差异，应优先以需求文档约定的表字段为准
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

在新架构中的定位：

- 每日增量事实源
- 差异识别输入源
- EvidenceBlock 的主要来源

当前字段边界补充：

- `data_json`
  只保存原始采集块，包含 `pat_diagInfor`、`pat_bodySurface`、`pat_doctorAdvice_*`、`pat_illnessCourse`、`pat_testSam`、`pat_useMedicine`、`pat_videoResult`、`pat_transfer`、`pat_opsCutInfor`、`pat_test`
- `filter_data_json`
  保存规则处理后的事实块，当前包含 `patient_info`、`diagnosis`、`vital_signs`、`lab_results`、`imaging`、`doctor_orders`、`use_medicine`、`transfer`、`operation`、`clinical_notes`、过滤后的 `pat_illnessCourse`
- `struct_data_json`
  保存单日结构化中间结果，不再重复存放单日摘要内容
- `event_json`
  当前已切换为“单日时间轴摘要”字段，不再保存事件抽取结果
- `last_time`
  当前已重新启用，表示该日 `patient_raw_data` 最后一次更新时间

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
- 作为事件抽取链路的直接消费任务表

当前关键字段：

- `patient_raw_data_id`
- `reqno`
- `data_date`
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
- 后续预警或扩展任务也应优先考虑消费这张表，或沿着同类 outbox / task 模式继续扩展

当前实现补充：

- 采集阶段只负责写 `patient_raw_data` 与追加 `patient_raw_data_change_task`
- 结构化阶段与事件阶段均 claim 这张表
- 对同一患者本轮 claim 到的变更行，先校验 `raw_data_last_time` 是否仍与 `patient_raw_data.last_time` 一致
- 仅处理仍然有效的单日变更行，不再做“从最早变更日开始”的整段重放
- 结构化调度前会巡检最近窗口的 `patient_raw_data`，补建 `appendChanges()` 漏掉的任务

## 2.4 `ai_process_log`

角色：

- 现有 AI 调用日志表

在新架构中的定位：

- 可以继续用于已有链路日志
- 不建议直接复用为院感预警节点运行日志主表

原因：

- 院感预警后续会有更多节点类型
- 需要更细粒度的运行记录

## 2.5 `items_infor_zhq`

角色：

- 症候群监测业务结果表

在新架构中的定位：

- 保持独立用途
- 不与院感预警结果表混用

## 3. 新增数据模型总览

下一阶段建议新增 6 张院感分析主表，其中 4 张为核心表，2 张为运维 / 结果辅助表：

1. `infection_event_pool`
2. `infection_llm_node_run`
3. `infection_case_snapshot`
4. `infection_alert_result`
5. `infection_daily_job_log`
6. `infection_node_result`

建议关系：

```text
patient_raw_data
    -> infection_event_pool
    -> infection_llm_node_run
    -> infection_case_snapshot

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

这 2 张表属于当前采集与下游处理的任务层，不纳入院感预警核心结果表统计。

- `infection_event_pool`
- `infection_llm_node_run`
- `infection_case_snapshot`
- `infection_alert_result`

这 4 张表是第一优先级建模对象。

## 4. `infection_event_pool`

## 4.1 表职责

这是院感预警分析的核心中枢表。

作用：

- 保存从每日快照中提取出的标准化事件
- 让后续分析围绕事件而不是原始 JSON 展开
- 作为状态快照和法官节点的主输入

## 4.1.1 需求文档优先字段

如果按你最新需求文档落库，建议优先采用以下字段命名：

- `id`
- `reqno`
- `data_date`
- `source_raw_id`
- `event_key`
- `parent_event_key`
- `event_time`
- `event_date`
- `event_type`
- `event_subtype`
- `body_site`
- `event_name`
- `event_value`
- `event_unit`
- `abnormal_flag`
- `infection_related`
- `negation_flag`
- `uncertainty_flag`
- `severity_level`
- `clinical_meaning`
- `source_layer`
- `source_module`
- `source_path`
- `source_text`
- `status`
- `payload_json`
- `create_time`
- `update_time`

## 4.2 建议主字段

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

## 4.3 字段说明建议

### `event_key`

用途：

- 做幂等
- 做去重
- 做版本比较

生成建议：

- `reqno + event_type + normalized_time + normalized_source + normalized_content_hash`

需求文档中的稳定规则建议：

- `reqno + "|" + data_date + "|" + source_layer + "|" + source_module + "|" + business_key`

### `source_type`

建议枚举：

- `structured_fact`
- `clinical_text`
- `mid_semantic`
- `manual_patch`

需求文档里对应字段名为：

- `source_layer`

第一版建议值：

- `raw`
- `mid`
- `summary`

### `event_type`

建议第一阶段先控制在有限集合：

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
- `colonization_statement`

需求文档中对一级大类的要求更偏“事实类别”，第一版至少要支持：

- `diagnosis`
- `vital_sign`
- `lab_panel`
- `lab_result`
- `microbiology`
- `imaging`
- `order`
- `device`
- `procedure`
- `note`
- `assessment`
- `consult`
- `problem`

建议做法：

- `event_type` 使用一级大类枚举
- `event_subtype` 使用受控二级枚举

### `status`

建议枚举：

- `active`
- `revoked`
- `superseded`
- `invalid`

## 4.4 索引建议

- `idx_reqno_event_time`
- `idx_reqno_event_type`
- `idx_event_key`
- `idx_reqno_active_status`
- `idx_raw_data_id`

如果严格按需求文档落库，索引建议调整为：

- `uk_infection_event_pool_event_key`
- `idx_infection_event_pool_reqno_date`
- `idx_infection_event_pool_reqno_type`
- `idx_infection_event_pool_reqno_time`

## 4.5 关键枚举补充

### `body_site`

需求文档要求第一版至少支持：

- `urinary`
- `respiratory`
- `bloodstream`
- `surgical_site`
- `abdominal`
- `joint`
- `systemic`
- `unknown`

并继续补齐更细粒度部位，例如：

- 上呼吸道
- 下呼吸道
- 胸膜腔
- 心脏瓣膜
- 心肌或心包
- 纵隔
- 动静脉
- 血液
- 胃肠道
- 腹（盆）腔内组织
- 中枢神经系统
- 泌尿道
- 表浅切口
- 深部切口
- 器官/腔隙
- 皮肤软组织
- 烧伤部位
- 骨和关节
- 生殖系统
- 眼/耳/口腔
- 其他

### `clinical_meaning`

需求文档建议至少支持：

- `infection_support`
- `infection_against`
- `infection_uncertain`
- `device_exposure`
- `procedure_exposure`
- `screening`
- `baseline_problem`

## 5. `infection_llm_node_run`

## 5.1 表职责

记录所有院感预警相关 LLM 节点运行过程。

作用：

- 回溯输入输出
- 对比 Prompt 版本
- 支持调试和评估
- 支持失败排查

## 5.1.1 需求文档优先字段

如果按需求文档落库，建议优先采用：

- `id`
- `reqno`
- `data_date`
- `node_name`
- `model_name`
- `prompt_version`
- `input_hash`
- `input_json`
- `output_json`
- `status`
- `confidence`
- `token_in`
- `token_out`
- `error_message`
- `create_time`

## 5.2 建议主字段

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

## 5.3 `node_type` 建议枚举

- `event_extractor`
- `new_onset_judge`
- `after_48h_judge`
- `procedure_association_judge`
- `infection_polarity_judge`
- `site_attribution_judge`
- `severity_judge`
- `explanation_generator`
- `final_audit_planning_only`

说明：

- `final_audit_planning_only` 仅作为预留枚举
- 当前阶段不实际运行

## 6. `infection_case_snapshot`

## 6.1 表职责

保存每个病例当前最新的院感状态快照。

作用：

- 避免每次从全历史重建状态
- 支持局部重算
- 支持页面快速展示

## 6.1.1 需求文档优先字段

如果按需求文档落库，建议优先采用：

- `id`
- `reqno`
- `latest_data_date`
- `snapshot_json`
- `last_raw_id`
- `last_summary_id`
- `overall_status`
- `overall_risk_level`
- `primary_site`
- `version_no`
- `create_time`
- `update_time`

## 6.2 建议主字段

### 主键与关联字段

- `id`
- `reqno`
- `latest_alert_result_id`

### 当前状态字段

- `current_status`
- `watch_flag`
- `triggered_flag`
- `current_risk_level`
- `current_suspected_site`
- `current_stage`

### 当前证据摘要字段

- `active_event_count`
- `key_event_summary`
- `supporting_evidence_json`
- `excluding_evidence_json`

### 时间字段

- `last_event_time`
- `last_analysis_time`
- `last_snapshot_date`

### 版本字段

- `last_result_version`
- `last_result_change_type`

### 审计字段

- `created_at`
- `updated_at`

## 6.3 `current_status` 建议枚举

- `idle`
- `watch`
- `triggered`
- `suppressed`
- `resolved`

## 6.4 `last_result_change_type` 建议枚举

- `new`
- `upgraded`
- `downgraded`
- `unchanged`
- `resolved`

需求文档中对快照表的受控值更明确，建议同步提供：

### `overall_status`

- `none`
- `watch`
- `triggered`
- `excluded`
- `resolved`

### `overall_risk_level`

- `high`
- `medium`
- `low`
- `none`

## 7. `infection_alert_result`

## 7.1 表职责

记录每次院感预警分析输出的版本化结果。

作用：

- 解释今天为什么报警
- 与上个版本做比较
- 支持页面展示和人工复核

## 7.1.1 需求文档优先字段

如果按需求文档落库，建议优先采用：

- `id`
- `reqno`
- `data_date`
- `result_version`
- `alert_status`
- `overall_risk_level`
- `primary_site`
- `result_json`
- `diff_json`
- `source_snapshot_id`
- `create_time`

## 7.2 建议主字段

### 主键与版本字段

- `id`
- `reqno`
- `result_version`
- `previous_result_id`

### 当前判决字段

- `alert_status`
- `risk_level`
- `suspected_site`
- `new_onset_flag`
- `after_48h_flag`
- `procedure_related_flag`
- `infection_polarity`

### 结果说明字段

- `summary`
- `doctor_readable_explanation`
- `supporting_evidence_json`
- `excluding_evidence_json`
- `delta_evidence_json`

### 来源字段

- `analysis_date`
- `case_snapshot_ref`
- `judge_packet_ref`

### 追踪字段

- `main_judge_node_run_id`
- `status`

### 审计字段

- `created_at`
- `updated_at`

## 7.3 `alert_status` 建议枚举

- `none`
- `watch`
- `triggered`
- `resolved`

## 7.4 `risk_level` 建议枚举

- `low`
- `medium`
- `high`
- `critical`

如果按需求文档落库，建议同步支持：

### `overall_risk_level`

- `high`
- `medium`
- `low`
- `none`

## 8. `infection_daily_job_log`

## 8.1 表职责

保存每日批处理执行情况，用于任务运维和失败定位。

## 8.2 建议字段

- `id`
- `job_date`
- `reqno`
- `stage`
- `status`
- `message`
- `create_time`

### `stage` 建议枚举

- `load`
- `normalize`
- `llm`
- `finalize`
- `snapshot`

### `status` 建议枚举

- `success`
- `skip`
- `error`

## 9. `infection_node_result`

## 9.1 表职责

用于汇总节点结果，避免每次都从 `infection_llm_node_run.output_json` 中二次解析。

## 9.2 建议字段

- `id`
- `reqno`
- `data_date`
- `node_name`
- `decision`
- `confidence`
- `result_json`
- `llm_run_id`
- `create_time`

## 10. 可选对象模型设计

除数据库表外，建议先在代码中定义一批中间对象，降低实现耦合。

建议对象：

- `SnapshotDiffResult`
- `EvidenceBlock`
- `StructuredFactBlock`
- `ClinicalTextBlock`
- `MidSemanticBlock`
- `TimelineContextBlock`
- `NormalizedInfectionEvent`
- `InfectionEvidencePacket`
- `InfectionJudgeResult`

## 11. Mapper / Service 落地建议

建议新增模块分层：

### `domain/entity`

- `InfectionEventPoolEntity`
- `InfectionLlmNodeRunEntity`
- `InfectionCaseSnapshotEntity`
- `InfectionAlertResultEntity`
- `InfectionDailyJobLogEntity`
- `InfectionNodeResultEntity`

要求：

- 实体类字段要写注释
- SQL Server 建表字段要写注释
- 枚举字段配套创建枚举类

### `mapper`

- `InfectionEventPoolMapper`
- `InfectionLlmNodeRunMapper`
- `InfectionCaseSnapshotMapper`
- `InfectionAlertResultMapper`
- `InfectionDailyJobLogMapper`
- `InfectionNodeResultMapper`

### `service`

- `InfectionEventPoolService`
- `InfectionLlmNodeRunService`
- `InfectionCaseSnapshotService`
- `InfectionAlertResultService`
- `InfectionDailyJobLogService`
- `InfectionNodeResultService`

### `service/impl`

- 对应实现类

说明：

- 第一阶段先把 Mapper 和基础 Service 建出来
- 再把复杂逻辑留给专用 orchestrator 或 analyzer service

## 12. 与现有表的关系建议

## 10.1 `patient_raw_data` 与 `infection_event_pool`

建议关系：

- 一条 `patient_raw_data` 可产生多条 `infection_event_pool`

建议保留字段：

- `raw_data_id`
- `source_ref`

## 10.2 `infection_case_snapshot` 与 `infection_alert_result`

建议关系：

- 快照保存“当前态”
- 结果表保存“历史版本”

## 13. 建表优先级

### 第一优先级

- `infection_event_pool`
- `infection_llm_node_run`

原因：

- 这是后续院感预警链路能否起步的基础

### 第二优先级

- `infection_case_snapshot`

原因：

- 支撑局部重算和当前态展示

### 第三优先级

- `infection_alert_result`

原因：

- 支撑版本化输出和页面联动

### 第四优先级

- `infection_daily_job_log`
- `infection_node_result`

原因：

- 支撑任务运维
- 支撑节点结果快速查询

## 14. 当前不落地的模型

以下对象当前只做规划，不做正式实现：

- 最终审核 Agent 运行结果表
- 多级人工复核流程表
- 多角色审核记录表

原因：

- 当前阶段目标是先把院感预警主链路跑通

## 15. 代码与配置一致性要求

- 表中受控值要优先落为枚举类
- Java 枚举、数据库枚举、Prompt 枚举要保持一致
- 如与 `src/main/resources/timeline-view-rules.yaml`、`src/main/java/com/zzhy/yg_ai/config/TimelineViewRuleProperties.java` 存在共用语义，应保持命名一致或建立清晰映射

## 16. Codex 开发建议

后续让 Codex agent 开发时，建议按以下顺序拆任务：

1. 建立 4 张表的 Entity / Mapper / Service 骨架
2. 先打通 `infection_event_pool` 写入
3. 再打通 `infection_llm_node_run` 留痕
4. 再补 `infection_case_snapshot`
5. 最后补 `infection_alert_result`

每一步都要保证：

- 幂等
- 可追踪
- 可回放
- 可扩展

## 17. 一句话结论

下一阶段的数据模型建设重点，不是继续堆叠原始 JSON，而是建立以 `infection_event_pool` 为中枢、以 `infection_case_snapshot` 为当前态、以 `infection_alert_result` 为版本输出、以 `infection_llm_node_run` 为可观测支撑的院感预警数据体系。
