# 院感预警分析实施文档

## 1. 文档目标

本文档用于指导 `yg_ai` 下一阶段“院感预警分析”能力建设，重点回答以下问题：

- 这一阶段到底要做什么
- 实施边界是什么
- 应该新增哪些模块
- 数据如何从现有表进入院感分析链路
- Codex agent 后续应按什么顺序推进开发

本文档只覆盖：

- 增量模式下的院感预警分析
- 标准化事件入池
- 病例状态快照
- 院感法官节点
- 结果版本化

本文档不覆盖正式实现：

- 最终审核 Agent

## 2. 当前基础能力

当前项目已经具备以下基础能力：

- 医疗住院数据采集
- 病程结构化摘要
- 时间线展示
- 传染病症候群监测的 AI 辅助分析

现有数据基础：

- `patient_raw_data`
  每日病例快照输入源
- `patient_summary`
  时间轴摘要上下文源

这意味着下一阶段不需要从零搭建数据采集系统，而是要在已有增量输入链路之上，增加院感预警分析能力。

## 3. 设计目标

院感预警分析的目标不是“每天全量重新分析所有历史病例”，而是构建：

> 基于每日增量数据持续更新的院感监测系统

核心目标：

1. 接收每日增量病例快照
2. 识别相对历史快照的变化
3. 将变化转成标准化事件
4. 基于事件更新病例状态
5. 仅对受影响病例触发局部重算
6. 输出版本化的院感预警结果

## 4. 实施边界

### 本阶段必须做

- 创建表结构及对应代码骨架
- 增量差异识别
- EvidenceBlock 切分
- 统一 LLM 事件抽取器
- EventNormalizer
- `infection_event_pool`
- `infection_llm_node_run`
- `infection_case_snapshot`
- 院感法官基础节点
- `infection_alert_result`

对应代码骨架包括：

- Entity
- 枚举类
- Mapper
- XML
- Service 接口
- Service 实现

本阶段不创建：

- Controller

### 本阶段可以预留

- 预警页面联动字段
- 人工复核接口位
- 最终审核 Agent 的扩展点
- `infection_daily_job_log`
- `infection_node_result`

### 本阶段不做

- 最终审核 Agent 的正式开发
- 完整人工复核闭环
- 高复杂多节点仲裁体系

## 5. 总体实施架构

```text
每日定时拉取 patient_raw_data / patient_summary
    -> 读取指定 reqno + data_date 快照
    -> 与历史快照比较
    -> 识别新增 / 更正 / 撤销
    -> 切分 EvidenceBlock
    -> 调用 LlmEventExtractor
    -> EventNormalizer 标准化
    -> 写入 infection_event_pool
    -> 更新 infection_case_snapshot
    -> 判断是否需要触发局部重算
    -> 组装硬事实证据包
    -> 调用院感法官节点
    -> 写入 infection_alert_result
    -> 更新 infection_case_snapshot
```

## 6. 输入层设计

## 6.1 输入来源

### `patient_raw_data`

主要字段角色：

- `filter_data_json`
  主事实源
- `struct_data_json`
  中间语义增强源
- `event_json`
  可作为已有辅助事件源
- `clinical_notes`
  轻量文本辅助

### `patient_summary`

主要字段角色：

- `summary_json`
  时间轴上下文源

说明：

- `summary_json` 只用于病程背景和解释生成
- 不作为最终事实判定主来源

## 6.2 增量模式约束

系统必须按“快照差异”工作，而不是按“整份重算”工作。

差异识别至少要区分：

- 新增
- 更正
- 撤销

差异识别输出应形成统一对象，供后续切块和事件抽取使用。

建议对象：

- `SnapshotDiffResult`

建议字段：

- `reqno`
- `dataDate`
- `previousVersionRef`
- `currentVersionRef`
- `addedBlocks`
- `updatedBlocks`
- `removedBlocks`
- `changeSummary`

## 7. EvidenceBlock 设计

为了让后续 LLM 抽取统一化，不直接对整份 JSON 做处理，而是切分成 4 类 EvidenceBlock。

## 7.1 StructuredFactBlock

来源：

- `filter_data_json`

内容：

- 检验
- 生命体征
- 影像
- 医嘱
- 诊断
- 设备
- 操作

## 7.2 ClinicalTextBlock

来源：

- 病程记录
- 查房记录
- 会诊记录
- 申请文本

内容特点：

- 自由文本
- 临床判断表达
- 排除、怀疑、建议、否定等语义密集

## 7.3 MidSemanticBlock

来源：

- `struct_data_json`

内容：

- `core_problems`
- `differential_diagnosis`
- `risk`
- `lab_summary`
- `orders_summary`

作用：

- 为 LLM 事件抽取提供中间层语义增强

## 7.4 TimelineContextBlock

来源：

- `summary_json`

作用：

- 仅供 LLM 理解病程演进背景
- 不直接入主事件池

## 8. 统一 LLM 事件抽取器

建议新增组件：

- `LlmEventExtractor`

职责：

- 接收 EvidenceBlock
- 选择对应 Prompt
- 调用模型
- 返回统一 `events[]` 结构
- 将调用过程记录到 `infection_llm_node_run`

建议输入：

- `reqno`
- `dataDate`
- `blockType`
- `blockPayload`
- `timelineContext`
- `promptVersion`

建议输出：

- `rawOutput`
- `events`
- `confidence`
- `status`

说明：

- 这里不要求一次把所有医学逻辑都抽干净
- 第一阶段重点是得到稳定、可规范化、可回放的标准事件输出

## 8.1 统一事件输出 Schema

所有 LLM 抽取都应输出同一结构：

```json
{
  "events": [
    {
      "event_time": "2026-01-24 09:25:55",
      "event_type": "assessment",
      "event_subtype": "contamination_possible",
      "body_site": "urinary",
      "event_name": "考虑污染",
      "event_value": null,
      "event_unit": null,
      "abnormal_flag": null,
      "infection_related": true,
      "negation_flag": false,
      "uncertainty_flag": true,
      "clinical_meaning": "infection_uncertain",
      "source_text": "患者自诉未取中段尿，考虑污染所致"
    }
  ]
}
```

要求：

- 严格 JSON
- 字段固定
- 枚举受控
- 可被 `EventNormalizer` 稳定处理

## 8.2 4 个 Block Builder

第一阶段只实现 4 个 block builder，不做大量零散 extractor。

### `StructuredFactBlockBuilder`

来源：

- `filter_data_json`

规则：

- 除 `PatInfor`
- 除 `patIllnessCourseList`
- 聚焦结构化事实部分

输入示例：

- 检验
- 生命体征
- 影像
- 医嘱
- 诊断
- 转科
- 用药
- 手术
- 微生物

输出：

- `List<EvidenceBlock>`

### `ClinicalTextBlockBuilder`

来源：

- `patIllnessCourseList`
- `pat_illnessCourse`

规则：

- 每条文书一个 block

### `MidSemanticBlockBuilder`

来源：

- `struct_data_json`

规则：

- 输出 problem / differential / risk block

### `TimelineContextBlockBuilder`

来源：

- `summary_json`

规则：

- 只供 LLM 使用
- 不直接落事件池

## 8.3 Prompt 规划

Prompt 统一放入：

- `WarningAgentPrompt`

要求：

- Prompt 中的类型与枚举类对应
- Prompt 枚举、数据库枚举、Java 枚举保持一致

### Prompt A：结构化事实块抽取

输入：

- 检验、影像、生命体征、医嘱等结构化块

输出：

- 事实事件列表

适用：

- `filter_data_json` 的结构化部分

### Prompt B：临床文本块抽取

输入：

- `pat_illnessCourse` 中某条文书全文

输出：

- `note` 事件
- `assessment` 事件
- `consult` 事件
- `procedure / device / symptom` 事件

### Prompt C：中间层语义块抽取

输入：

- `struct_data_json` 的某个结构化语义块

输出：

- `problem` 事件
- `differential` 事件
- `risk` 事件
- 语义标签事件

### Prompt D：摘要上下文抽取

输入：

- `summary_json`

输出：

- 不进入 `infection_event_pool`
- 只生成给后续院感法官节点使用的 `timeline_context`

## 9. EventNormalizer 设计

LLM 输出不能直接入库，必须经过标准化。

建议新增组件：

- `EventNormalizer`

职责：

- 时间标准化
- 枚举校验
- 默认值填充
- `event_key` 生成
- 幂等去重
- 非法输出兜底
- 源数据引用补齐

建议标准化后的事件最少包含：

- `reqno`
- `eventKey`
- `eventType`
- `eventTime`
- `site`
- `polarity`
- `certainty`
- `sourceType`
- `sourceRef`
- `content`
- `evidence`
- `status`
- `confidence`

## 9.1 LLM 护栏与规范化要求

第一阶段必须实现 4 个护栏：

### 护栏 1：块类型固定

- 不允许把整份 JSON 直接扔给 LLM
- 必须按 EvidenceBlock 分块送入

### 护栏 2：输出 Schema 强约束

- 必须严格 JSON
- 字段固定
- 枚举固定

### 护栏 3：低置信度回退

当某块置信度较低时：

- 原文 `note` 事件照样入池
- 语义事件可不落库或标记为 `uncertain`

### 护栏 4：原始事实优先

如果结构化事实与文本语义冲突：

- 原始结构化事实不丢
- 语义事件只作为补充层

## 10. 事件池设计

建议新增主表：

- `infection_event_pool`

这是院感预警分析的中枢，不再反复直接围绕原始 JSON 做分析。

事件分为两类：

### 10.1 事实事件

主要来自：

- 检验结果
- 生命体征
- 影像
- 医嘱
- 手术
- 器械
- 文档原文事实

### 10.2 语义事件

主要来自：

- `struct_data_json`
- 临床文本 LLM 抽取结果

典型内容：

- 考虑污染
- 排除泌尿系感染
- 无尿频尿急尿痛
- 待排感染
- 拟手术
- 风险标签

## 11. 病例状态快照设计

建议新增主表：

- `infection_case_snapshot`

职责：

- 保存每个 `reqno` 的当前最新院感认知状态
- 避免每次从全历史重建
- 支持局部重算
- 支持页面快速查询当前状态

建议快照至少包含：

- 当前活跃感染事件链
- 当前主可疑部位
- 当前总体风险状态
- 最近一次分析日期
- 最近一次结果版本
- 当前 watch 状态
- 当前 triggered 状态

## 12. 院感法官节点设计

事件池建成后，进入“判决层”。

建议第一阶段不要设计成大而全的多 Agent 系统，而是从“单一职责法官节点”开始。

## 12.1 基础判断节点

建议优先实现：

- 新发性判断
- 48 小时后新发判断
- 术后 / 器械暴露关联判断
- 感染极性判断

## 12.2 第二阶段复杂判断节点

后续可扩展：

- 污染 / 定植 / 真感染判断
- 部位归因
- 证据强度分级

## 12.3 第三阶段结果建议节点

后续可扩展：

- 部位级风险评分
- 总体预警建议
- 医生可读解释生成

说明：

- 法官节点输入应是“硬事实证据包”
- 不应再直接吃原始大 JSON

## 13. 硬事实证据包设计

建议新增统一构造对象：

- `InfectionEvidencePacket`

输入来源：

- `infection_event_pool`
- `infection_case_snapshot`
- `patient_summary.summary_json`

建议内容：

- 当前活跃事件
- 新增关键事件
- 已排除事件
- 时间线背景
- 手术 / 器械暴露信息
- 检验 / 影像 / 体征摘要

作用：

- 作为法官节点统一输入
- 降低 Prompt 不稳定性
- 便于调试与回放

## 14. 触发局部重算策略

不是所有增量都触发完整院感分析。

### 触发完整分析的关键事件

- 新发热
- WBC / CRP / PCT 异常
- 培养送检 / 阳性
- 抗菌药新开或升级
- 新手术 / 导尿 / 置管 / 插管
- 文本出现“感染 / 排除感染 / 污染 / 定植”
- 影像提示感染灶

### 只更新事件池但不触发完整分析

- 与感染无关的重复数据
- 非关键字段修订
- 纯背景摘要变化

## 15. 结果输出设计

建议新增主表：

- `infection_alert_result`

输出不是静态标签，而是版本化结果。

每次结果至少回答：

- 今天为什么报警
- 和昨天比发生了什么变化
- 是新报警、升级还是解除

输出层建议包含：

### 15.1 当前状态快照

- 是否正在 watch
- 是否 triggered
- 当前主可疑部位
- 当前风险等级

### 15.2 结果版本记录

- 本次新增证据
- 本次排除证据
- 风险变化原因
- 解除原因

### 15.3 医生可读解释

- 当前院感风险摘要
- 时间链
- 支持证据
- 排除证据
- 建议复核点

## 16. LLM 运行记录设计

建议新增主表：

- `infection_llm_node_run`

所有院感预警相关模型调用都要写运行记录。

建议记录内容：

- `reqno`
- `nodeType`
- `promptVersion`
- `modelName`
- `inputPayload`
- `outputPayload`
- `status`
- `confidence`
- `errorCode`
- `errorMessage`
- `createdAt`

作用：

- 调试
- 评估
- 回放
- Prompt 版本对比

## 17. 开发任务清单

## 17.1 代码生成范围

本阶段需创建：

- 表结构
- 实体类
- 枚举类
- Service 接口
- Service 实现类
- Mapper
- Mapper XML

本阶段不创建：

- Controller

## 17.2 枚举类要求

- 生成严格枚举
- 数据表、代码、Prompt 保持一致
- 文档中提到的受控值，应优先沉淀为枚举类
- 如与 `timeline-view-rules.yaml`、`TimelineViewRuleProperties.java` 存在交叉语义，应保持命名和表达一致

## 17.3 真实数据结构参考

### `data_json` / `filter_data_json`

- 结构定义：`com.zzhy.yg_ai.domain.entity.PatientCourseData`
- 生成逻辑：`com.zzhy.yg_ai.service.impl.PatientServiceImpl#buildSemanticBlockJson`
- 实际输出结构说明：`docs/patient-raw-json-structure.md`

### `struct_data_json`

- 生成逻辑：`com.zzhy.yg_ai.ai.agent.SummaryAgent`

### `summary_json`

- 生成逻辑：`com.zzhy.yg_ai.ai.agent.SummaryAgent`

## 18. 验收标准

- 输入某个 `reqno + data_date`，能成功生成事件并写入 `infection_event_pool`
- 同一批重复执行，不产生重复事件，`event_key` 保持幂等
- 至少覆盖 4 类 block：
  - 结构化事实
  - 临床文本
  - 中间层语义
  - 摘要上下文
- LLM 返回非法 JSON 时，写入 `infection_llm_node_run` 错误记录，并保底入 `note` 事件
- 低置信度时，语义事件能正确标记或跳过
- `summary_json` 不直接落入事件池，只生成上下文对象

## 19. 实施顺序

### Phase 1

- 设计差异识别对象
- 设计 EvidenceBlock
- 实现 `LlmEventExtractor`
- 实现 `EventNormalizer`
- 建立 `infection_event_pool`
- 建立 `infection_llm_node_run`

交付标准：

- 能把每日增量快照稳定转为标准化事件

### Phase 2

- 建立 `infection_case_snapshot`
- 实现局部重算触发策略
- 实现院感法官基础节点

交付标准：

- 能对受影响病例输出当前院感状态

### Phase 3

- 建立 `infection_alert_result`
- 产出结果版本
- 生成医生可读解释
- 对接页面展示字段

交付标准：

- 能输出可展示、可回溯、可比较的预警结果

### Planning

- 设计最终审核 Agent

交付标准：

- 完成架构规划
- 明确输入输出契约
- 不进入正式编码实现

## 20. Codex 开发建议

后续用 Codex agent 模式开发时，建议按以下任务粒度拆分：

1. 先建数据模型和表结构
2. 再建差异识别与 EvidenceBlock
3. 再建事件抽取与标准化
4. 再建事件池写入和状态快照
5. 再建院感法官基础节点
6. 最后建结果版本和解释输出

每个子任务都应明确：

- 输入来源
- 输出对象
- 幂等策略
- 错误处理
- 运行记录

## 21. 一句话结论

下一阶段院感预警分析的实施核心，不是再造一条新的全量分析链路，而是在现有增量采集和摘要能力之上，建设“标准化事件池 + 病例状态快照 + LLM 法官节点 + 结果版本化”的增量式院感判决系统。
