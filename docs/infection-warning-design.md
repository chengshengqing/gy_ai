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

当前补充：

- `patient_raw_data.event_json` 已作为单日时间轴摘要来源
- 7 天窗口上下文通过 `patient_raw_data.event_json` 按需拼装

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
每日定时拉取 patient_raw_data
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
  单日时间轴摘要与窗口上下文源
- `clinical_notes`
  轻量文本辅助

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

当前纳入的结构化 section：

- `diagnosis`
- `vital_signs`
- `lab_results`
- `imaging`
- `doctor_orders`
- `use_medicine`
- `transfer`
- `operation`

说明：

- 每个 `patient_raw_data` 仅生成 `1` 个 `StructuredFactBlock`
- 不再按 section 拆成多个结构化事实 block
- 对外为单 block，对内保留 section 边界
- 当前无独立 `devices` section
- 设备暴露主识别通道放在 `ClinicalTextBlock + MidSemanticBlock`
- 若 `doctor_orders` 或 `operation` 中出现明确器械或暴露线索，可作为补充证据抽取

### 7.1.1 payload 结构

```json
{
  "section": "structured_fact_bundle",
  "source": "filter_data_json",
  "dataDate": "yyyy-MM-dd",
  "data": {
    "diagnosis": {
      "priority_facts": [],
      "reference_facts": [],
      "raw": []
    },
    "vital_signs": {
      "priority_facts": [],
      "reference_facts": [],
      "raw": []
    },
    "lab_results": {
      "priority_facts": [],
      "reference_facts": [],
      "raw": {
        "test_panels": [],
        "microbe_panels": []
      }
    },
    "imaging": {
      "priority_facts": [],
      "reference_facts": [],
      "raw": []
    },
    "doctor_orders": {
      "priority_facts": [],
      "reference_facts": [],
      "raw": {
        "long_term": [],
        "temporary": [],
        "sg": []
      }
    },
    "use_medicine": {
      "priority_facts": [],
      "reference_facts": [],
      "raw": []
    },
    "transfer": {
      "priority_facts": [],
      "reference_facts": [],
      "raw": []
    },
    "operation": {
      "priority_facts": [],
      "reference_facts": [],
      "raw": []
    }
  }
}
```

### 7.1.2 `priority_facts / reference_facts / raw` 设计

每个 section 拆成三层：

- `priority_facts`
  优先给 LLM 阅读的高密度事实
- `reference_facts`
  少量对照或参考事实
- `raw`
  受限保留的 compact 原始事实

设计目标不是做自由文本摘要，而是完成结构压缩和信息分层。

生成原则：

- `change-first`
  优先本次新增或变更内容
- `anomaly-first`
  优先结构上已标记异常、阳性、非正常项
- `schema-native`
  只做字段投影，不做医学结论预判

实现约束：

- 不引入额外 LLM 预摘要步骤
- 不做关键词硬编码医学判断
- 保留原始 compact 结构，确保可回放、可审计

当前第一版实现由于尚未直接接入 diff 上下文，`priority_facts` 先以“结构异常优先 + schema 投影”为主，后续可再补 `change-first`。

### 7.1.3 临床优先级锚点

`StructuredFactBlock` 的优先级判断，不允许只根据示例数据、文本长度、字段完整度或当前样本分布决定。

后续所有 `priority_facts / reference_facts / raw` 的设计，必须优先服从真实医学场景下医生的判断习惯。

本系统采用以下临床优先级锚点：

- 微生物结果、影像结论，优先视为更接近病原学或感染灶证据
- 常规实验室异常、生命体征异常，优先视为提示性或支持性证据
- 医嘱、用药、手术、转科，更接近管理行为、风险背景或暴露线索，不应直接等同于感染成立
- 单一发热、单一常规异常指标通常不应独立压过微生物或影像结论
- 阴性微生物结果、影像未见感染灶、指标恢复正常等信息，允许作为反证或减弱感染怀疑的参考事实

这组锚点的目标不是机械复刻某一条院感判定标准，而是让预警系统在输入组织和 LLM 阅读顺序上更接近临床医生的思维顺序。

### 7.1.4 指南约束

本系统在“证据优先级”和“事件抽取护栏”层面，优先参考以下医学与监测指南：

- CDC NHSN Patient Safety Component Manual
  作用：
  - 作为院感监测事件窗口、客观要素、部位定义的监测参考
  - 强调监测定义应依赖可追溯、可审计的客观要素
- NICE Sepsis: Recognition, Diagnosis and Early Management
  作用：
  - 强调不能仅凭单一体征、单一生物标志物完成严重感染判断
  - 强调需要结合感染源寻找、实验室检查和影像判断
- ATS/IDSA HAP/VAP Guideline
  作用：
  - 强调微生物学取材和培养结果对医院获得性肺炎判断的重要性
  - 强调 CRP/PCT 等生物标志物不能单独替代临床判断
  - 强调抗菌药物使用更多是治疗管理线索，不应被误当成感染成立的直接硬证据

落实到本项目的约束如下：

- `lab_results.microbe_panels` 与 `imaging` 的优先级应高于普通 `doctor_orders`
- `lab_results.test_panels` 和 `vital_signs` 应主要作为支持证据，而不是直接替代病原学或影像学证据
- `doctor_orders / use_medicine / operation` 更适合作为“语义相关性待判断”的弱结构化输入，不应长期依赖纯结构排序
- EventNormalizer 和 Prompt 的护栏，应允许反证存在，不得把所有阴性结果都当作无价值背景数据

指南链接：

- CDC NHSN Patient Safety Component Manual: https://stacks.cdc.gov/view/cdc/83964
- NICE Sepsis Guideline: https://www.ncbi.nlm.nih.gov/books/NBK553314/
- ATS/IDSA HAP/VAP Guideline: https://www.idsociety.org/practice-guideline/hap_vap/

### 7.1.5 各 section 建议

#### `diagnosis`

- `priority_facts`
  优先阅读的诊断事实
- `reference_facts`
  少量代表性诊断
- `raw`
  受限保留的诊断数组

#### `vital_signs`

- `priority_facts`
  优先阅读的体征事实
- `reference_facts`
  少量代表性正常或对照记录
- `raw`
  受限保留的体征数组

#### `lab_results`

- `priority_facts`
  优先阅读的检验与微生物事实，优先正向微生物结果与异常结果
- `reference_facts`
  少量对照或反证结果
- `raw`
  受限保留的普通检验面板与微生物面板

#### `imaging`

- `priority_facts`
  优先阅读的影像结论
- `reference_facts`
  少量对照影像结论
- `raw`
  受限保留的影像数组

#### `doctor_orders`

- `priority_facts`
  当前第一版允许为空，避免无任务导向的普通医嘱污染主输入
- `reference_facts`
  少量参考医嘱
- `raw`
  受限保留的 `long_term / temporary / sg`

#### `use_medicine`

- `priority_facts`
  优先阅读的用药事实
- `reference_facts`
  少量参考用药
- `raw`
  受限保留的用药数组

#### `transfer`

- `priority_facts`
  优先阅读的转科节点
- `reference_facts`
  少量参考转科事实
- `raw`
  受限保留的转科数组

#### `operation`

- `priority_facts`
  优先阅读的手术事实
- `reference_facts`
  少量参考手术事实
- `raw`
  受限保留的手术数组

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

定位：

- `ClinicalTextBlock` 主要补足结构化数据难以表达的“判断语义”
- 重点抽取医生如何解释事实，而不是重复抽取普通数值结果

优先抽取：

- 支持感染的明确判断
- 反对感染的明确判断
- 待排、可疑、不能除外感染
- 污染、定植、临床意义有限等解释
- 感染来源、感染部位、归因线索
- 器械、手术、侵入性操作相关归因线索
- 临床动作的理由，例如“因发热送培养”“因考虑感染升级抗菌药”
- 新发、加重、好转、持续等病情演变

默认不重点抽取：

- 与感染无关的流水账
- 单纯重复结构化层已明确表达的普通检验值
- 无判断意义的常规治疗描述

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

- 最近窗口内 `patient_raw_data.event_json`

作用：

- 为第一层抽取提供“变化背景”，而不是完整时间轴复述
- 不直接入主事件池

### 7.4.1 recent_change_context

第一阶段不再默认把完整 timeline JSON 直接传给第一层 LLM。

改为使用：

- `recent_change_context`

建议结构：

```json
{
  "reqno": "xxx",
  "anchorDate": "2026-03-01",
  "windowDays": 7,
  "summaryType": "EVENT_EXTRACTOR_CONTEXT",
  "changes": [
    "2026-03-01 稽留流产确诊",
    "2026-03-01 B超示宫腔残留伴无胎心",
    "2026-03-01 警惕感染风险"
  ]
}
```

设计原则：

- 第一版不引入额外 LLM 总结
- 基于最近窗口内 `event_json` 做确定性压缩
- 只保留少量关键变化
- 优先保留：
  - 新发事件
  - 升级事件
  - 明确支持证据
  - 明确反证
  - 暴露/操作关键节点

### 7.4.2 SummaryContext 缓存

新增：

- `SummaryContextCacheService`

第一阶段设计：

- Redis 缓存 `recent_change_context`
- 当前只支持：
  - `SummaryContextType.EVENT_EXTRACTOR_CONTEXT`
- key 形如：

```text
yg_ai:infection:summary_context:{summaryType}:{reqno}:{anchorDate}:{windowDays}
```

缓存策略：

- cache miss 时不调用 LLM
- 直接基于窗口内 `event_json` 生成确定性小摘要

失效策略：

- 只要患者任一 `event_json` 发生变化
- 先按患者粒度失效该患者所有 summary context 缓存

## 7.5 StructuredFact 枚举定义

### `source_section`

固定枚举：

- `diagnosis`
- `vital_signs`
- `lab_results`
- `imaging`
- `doctor_orders`
- `use_medicine`
- `transfer`
- `operation`

### `evidence_tier`

- `hard`
- `moderate`
- `weak`

定义：

- `hard`
  强客观证据
- `moderate`
  中等支持证据
- `weak`
  弱支持、弱反驳或筛查性证据

### `evidence_role`

- `support`
- `against`
- `risk_only`
- `background`

定义：

- `support`
  支持感染判断
- `against`
  反对感染判断
- `risk_only`
  只表示风险或暴露，不表示感染已发生
- `background`
  背景信息，不参与强判断

## 7.6 StructuredFact 证据层级建议

| source_section | 典型内容 | 默认 tier | 默认 role | 说明 |
| --- | --- | --- | --- | --- |
| `lab_results` `microbe_panels` | 培养阳性、病原检出 | `hard` | `support` | 污染或定植可降级或转 `against` |
| `imaging` | 明确感染灶 | `hard` / `moderate` | `support` | “明确感染灶”优先 `hard` |
| `lab_results` `test_panels` | WBC/CRP/PCT 异常 | `moderate` | `support` | 单项轻度异常可降 `weak` |
| `use_medicine` | 抗菌药启动或升级 | `moderate` | `support` | 预防性用药可降级 |
| `doctor_orders` | 抗感染医嘱、送检医嘱 | `moderate` / `weak` | `support` / `risk_only` | 普通医嘱为 `background` |
| `operation` | 手术、围术期用药 | `moderate` | `risk_only` | 单独不表示感染成立 |
| `vital_signs` | 发热、心率快等 | `weak` / `moderate` | `support` | 单次发热通常不应 `hard` |
| `diagnosis` | 感染相关诊断 | `weak` / `moderate` | `support` | 不建议直接 `hard` |
| `transfer` | 转入高风险科室 | `weak` | `risk_only` | 仅作背景风险变化 |

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

## 8.1 StructuredFactRefinementService

为避免 `doctor_orders / use_medicine / operation` 长期处于“纯结构排序噪声高”与“完全静音”之间摇摆，增加一个轻量语义上浮层：

- `StructuredFactRefinementService`

设计目标：

- 不直接替代最终事件抽取
- 只对弱结构化 section 做“院感相关性”轻判断
- 把高相关项上浮回 `priority_facts`
- 保留低相关项在 `reference_facts / raw`

### 8.1.1 适用 section

- `doctor_orders`
- `use_medicine`
- `operation`

必要时后续可扩展：

- `transfer`

不优先纳入该层的 section：

- `lab_results`
- `imaging`
- `vital_signs`

原因：

- 这些 section 已具备较强结构化或结论化特征，更适合直接进入最终事件抽取层

### 8.1.2 链路位置

建议插入位置：

```text
StructuredFactBlockBuilder
    -> StructuredFactRefinementService
    -> LlmEventExtractor
    -> EventNormalizer
```

职责边界：

- Builder 负责结构压缩、去重、分层
- Refinement 负责弱结构化事实的院感相关性分类
- Final Extractor 负责最终事件抽取、证据层级与事件类型输出

### 8.1.3 输入

对每个弱结构化 section，输入：

- `priority_facts`
- `reference_facts`
- 受限 `raw`
- 当前 `reqno`
- `dataDate`
- 可选 `timelineContext`

第一版建议：

- 主输入使用 `reference_facts + raw`
- `timelineContext` 只作为弱辅助

### 8.1.4 输出

Refinement 层不直接输出最终事件，只输出“是否值得上浮”的分类结果。

建议输出 schema：

```json
{
  "items": [
    {
      "source_section": "doctor_orders",
      "source_text": "血培养",
      "infection_relevance": "high|medium|low",
      "suggested_role": "support|risk_only|background",
      "promotion": "promote|keep_reference|drop",
      "reason": "送检行为与感染评估直接相关"
    }
  ]
}
```

字段含义：

- `infection_relevance`
  只判断与院感任务的相关性，不判断最终事件类型
- `suggested_role`
  只提供方向建议，不直接入池
- `promotion`
  决定该条是否回填到 `priority_facts`
- `reason`
  仅用于调试和回放

### 8.1.5 Prompt 目标

该层 Prompt 必须是轻任务，不允许承担最终事件裁决。

应聚焦：

- 这条事实是否与院感判断直接相关
- 它更像支持证据、风险背景，还是普通背景
- 是否值得提升到最终事件抽取层的主视野

不应要求：

- 输出完整 `event_type / event_subtype`
- 输出最终 `evidence_tier`
- 进行复杂跨 section 综合判断

### 8.1.6 回填策略

Refinement 完成后：

- `promotion=promote` 的项，回填到对应 section 的 `priority_facts`
- `promotion=keep_reference` 的项，留在 `reference_facts`
- `promotion=drop` 的项，不进入主输入，但可保留在 `raw`

这样可以避免：

- `doctor_orders` 长期全静音
- 普通生理盐水、无关筛查医嘱污染主优先层
- 把第一层 LLM 变成不可逆的强裁剪器

### 8.1.7 设计原则

该层判断必须继续服从“医生视角”的真实临床优先级：

- 微生物和影像仍然高于管理行为线索
- 医嘱和用药更接近临床意图与暴露背景
- 手术更多是风险背景，不应轻易直接上浮为感染成立证据

因此，Refinement 的职责不是“代替医生下结论”，而是：

- 让弱结构化事实是否值得进入主视野，更接近医生的阅读顺序

### 8.1.8 当前实际事件抽取链路

当前代码中的一层事件抽取链路已经落成，实际顺序如下：

```text
SummaryWarningScheduler.processPendingEventTasks
    -> claim infection_event_task(EVENT_EXTRACT)
    -> PatientService.buildSummaryWindowJson(reqno, dataDate, 7)
    -> InfectionEvidenceBlockService.buildBlocks(rawData, summaryWindowJson)
        -> TimelineContextBlockBuilder
        -> StructuredFactBlockBuilder
        -> StructuredFactRefinementService
        -> ClinicalTextBlockBuilder
        -> MidSemanticBlockBuilder
    -> LlmEventExtractorService.extractAndSave(buildResult)
        -> 对 primaryBlocks 逐块调用 WarningAgent
        -> EventNormalizer.normalize
        -> InfectionEventPoolService.saveNormalizedEvents
    -> 如有新增事件，追加 infection_event_task(CASE_RECOMPUTE)
```

各节点职责如下：

- `PatientService.buildSummaryWindowJson`
  - 不再返回完整 timeline
  - 当前返回基于最近窗口 `event_json` 的确定性 `recent_change_context`
  - 结果通过 Redis 缓存

- `TimelineContextBlockBuilder`
  - 将 `recent_change_context` 包装成单个 context block
  - 仅作为第一层抽取的弱背景

- `StructuredFactBlockBuilder`
  - 从 `filter_data_json` 构建单个 `StructuredFactBlock`
  - 输出 `priority_facts / reference_facts / raw`

- `StructuredFactRefinementService`
  - 仅对 `doctor_orders / use_medicine / operation` 做轻量语义上浮
  - 不直接输出事件
  - 只决定是否把候选上浮回 `priority_facts`

- `ClinicalTextBlockBuilder`
  - 每条病程文书构建一个 `ClinicalTextBlock`
  - 当前不再依赖完整 timeline

- `LlmEventExtractorService`
  - 对 `primaryBlocks` 逐块抽取
  - `STRUCTURED_FACT` 和 `MID_SEMANTIC` 继续使用 `timelineContext`
  - `CLINICAL_TEXT` 改为使用：
    - `blockPayload`
    - `structuredContext`
    - `recentChangeContext`
    - `timelineContext = null`

- `EventNormalizer`
  - 负责枚举校验、字段校验、组合规则校验、`source_text` 溯源校验
  - 当前仍是第一层抽取的最后一道稳定器

说明：

- 当前链路属于“一层轻抽取层”
- 当前候选层不使用 LLM 做是否入队判断
- `ILLNESS_COURSE` 新增会直接进入 `EVENT_EXTRACT`
- 第二层 LLM 法官/归因节点不在本链路内
- 第二层用于做院感成立、医院获得性归因、跨事件综合裁决

### 8.1.9 当前可改进点

结合当前样本回放，现阶段链路的主要改进点如下：

1. `STRUCTURED_FACT`
   - 已基本完成从“全量结构化输入”向“分层输入”的收敛
   - 后续重点不是继续放大 prompt，而是继续校正 `priority_facts` 的任务相关性

2. `CLINICAL_TEXT`
   - 当前已经从“广泛误报”收敛到“少量固定误标”
   - 该层现阶段应停止继续扩写 prompt
   - 后续少量顽固误标，优先交给第二层法官节点做最终裁决

3. `recent_change_context`
   - 当前已经替代完整 timeline，方向正确
   - 后续可继续提升摘要质量，但不建议重新回退为长时间轴背景

4. `EventNormalizer`
   - 当前仍有进一步承接低风险纠偏的空间
   - 但应只处理“明显错误输出”，不应替代第二层法官做语义裁决

5. 第二层法官节点
   - 后续最值得投入的能力不再是一层 prompt 微调
   - 而是构建“跨 block、跨事件、跨时间”的裁决层
   - 该层负责：
     - 院感成立与否
     - 医院获得性归因
     - 污染 / 定植 / 真感染区分
     - 弱支持事件的收敛或驳回

## 8.2 统一事件输出 Schema

所有 LLM 抽取都应输出同一结构：

```json
{
  "status": "success|skipped",
  "confidence": 0.0,
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
      "source_section": "diagnosis|vital_signs|lab_results|imaging|doctor_orders|use_medicine|transfer|operation|null",
      "source_text": "患者自诉未取中段尿，考虑污染所致",
      "evidence_tier": "hard|moderate|weak",
      "evidence_role": "support|against|risk_only|background"
    }
  ]
}
```

要求：

- 严格 JSON
- 字段固定
- 枚举受控
- 可被 `EventNormalizer` 稳定处理

## 8.3 4 个 Block Builder

第一阶段只实现 4 个 block builder，不做大量零散 extractor。

### `StructuredFactBlockBuilder`

来源：

- `filter_data_json`

规则：

- 每个 `patient_raw_data` 仅生成一个 `StructuredFactBlock`
- 不再按 section 拆成多个结构化事实 block
- payload 内部保留 section 边界
- 使用 `priority_facts / reference_facts / raw` 提供分层输入
- 保留 compact 原始结构，供 LLM 回看与溯源

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
  其中结构化事实列表长度固定为 `1`

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

- 最近 7 天窗口 JSON

规则：

- 只供 LLM 使用
- 不直接落事件池

## 8.4 Prompt 规划

Prompt 统一放入：

- `WarningAgentPrompt`

要求：

- Prompt 中的类型与枚举类对应
- Prompt 枚举、数据库枚举、Java 枚举保持一致

### Prompt A：结构化事实块抽取

输入：

- 当天结构化硬事实总包
- 内含 `diagnosis / vital_signs / lab_results / imaging / doctor_orders / use_medicine / transfer / operation`
- `timelineContext` 仅作辅助背景

输出：

- 事实事件列表

适用：

- `filter_data_json` 的结构化部分

关键要求：

- 必须输出 `source_section`
- 必须输出 `evidence_tier`
- 必须输出 `evidence_role`
- 结构化事实抽取必须优先遵循“微生物/影像优先、普通实验室与生命体征次之、医嘱/用药/手术/转科作为辅助线索”的临床顺序
- 当前层只负责事实抽取，不负责“院感成立/医院获得性归因”最终裁决
- 为降低小模型负担，StructuredFact Prompt 采用“短规则 + 默认映射”：
  1. 当前层只做事实抽取，不做院感归因
  2. 固定判断顺序：`source_section -> event_type -> event_subtype -> body_site -> clinical_meaning/evidence_role`
  3. 不允许为了贴合语义而跨 `source_section` 改写 `event_type`
  4. 拿不准时优先跳过，不要强行解释
- `lab_results`
  - 微生物结果可直接抽取
  - 一般异常结果优先 `lab_abnormal`
  - 普通凝血、生化、电解质、局部镜检非特异性异常，不得仅因“异常”就直接输出 `infection_support`
  - 若保留但不直接支持感染，优先：
    - `clinical_meaning=baseline_problem`
    - `evidence_role=background`
    - `infection_related=false`
- `imaging`
  - 支持感染时可用 `imaging_infection_hint`
  - 反证时优先 `event_subtype=null`
- `doctor_orders / use_medicine / operation`
  - 默认更接近 `risk_only / background`
  - 只有明确与感染评估、抗感染处理、侵入性暴露相关时才输出
- `STRUCTURED_FACT` 中一般不要使用 `infection_positive_statement / infection_negative_statement`
- `body_site` 必须保守填写：只有部位证据明确时才填具体部位；局部样本不自动等于感染部位；拿不准时优先 `unknown`
- `source_text` 必须来自 `blockPayload`
- 不得仅凭 `timelineContext` 产出事件
- 已有完成结果的微生物检查，不要再输出 `culture_ordered`
- `culture_ordered` 仅用于“已送检但尚无结果”的事实
- 影像结果若不支持感染，不要使用 `imaging_infection_hint`
- 对影像反证，可使用 `event_type=imaging`、`event_subtype=null`、`evidence_role=against`

### Prompt B：临床文本块抽取

输入：

- `pat_illnessCourse` 中某条文书全文
- 当前 `StructuredFactBlock` 的高密度摘要
- `recent_change_context`

输出：

- `assessment` 事件
- `procedure / device` 事件

关键要求：

- 文本层重点抽“判断语义”，不是“客观数值事实”
- 优先抽：
  - `infection_positive_statement`
  - `infection_negative_statement`
  - `contamination_statement / contamination_possible`
  - `colonization_statement / colonization_possible`
  - `device_exposure`
  - `procedure_exposure`
- 重点保留：
  - 否定
  - 怀疑
  - 污染/定植解释
  - 感染来源/部位线索
  - 器械/手术相关归因线索
  - 临床动作的理由
  - 新发/加重/好转
- 不要重复抽取结构化层已经明确表达的普通检验值、普通影像结果、普通医嘱事实
- `structuredContext` 只能辅助理解当前病程文本，不得把其中原句、检验值、影像结论、医嘱内容直接作为 `source_text`
- `source_section` 必须输出 JSON `null`，不得填写 `diagnosis / vital_signs / lab_results / imaging / doctor_orders / use_medicine / transfer / operation`
- 如果一个事件只能由 `structuredContext / recent_change_context` 支撑，而当前病程正文没有对应原句，优先 `skipped`
- 没有明确感染相关判断语义时，优先 `skipped`

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

- 最近 7 天窗口 JSON

输出：

- 不进入 `infection_event_pool`
- 只生成给后续院感法官节点使用的 `timeline_context`

## 9. EventNormalizer 设计

LLM 输出不能直接入库，必须经过标准化。

建议新增组件：

- `EventNormalizer`

职责：

- 输出结构校验
- 时间标准化
- 字段枚举校验
- 跨字段一致性校验
- `source_text` 溯源校验
- `event_key` 生成
- 幂等去重
- 非法输出兜底
- 源数据引用补齐

不承担的职责：

- 不根据 `sourceRef` 猜 `eventType`
- 不根据 block 类型猜 `eventSubtype`
- 不做自由语义纠偏

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

第一阶段必须实现以下护栏：

### 护栏 1：块类型固定

- 不允许把整份 JSON 直接扔给 LLM
- 必须按 EvidenceBlock 分块送入
- `StructuredFactBlock` 固定为单总包模式

### 护栏 2：输出 Schema 强约束

- 必须严格 JSON
- 字段固定
- 枚举固定
- 结构化事实块必须输出 `source_section / evidence_tier / evidence_role`

### 护栏 3：timeline 只作辅助背景

- 第一层默认不再使用完整 timeline JSON
- 第一层若需要背景，只使用极短 `recent_change_context`
- `recent_change_context` 只能帮助判断新发、升级、时间关系
- 不允许仅凭背景摘要产出事件
- `source_text` 必须来自主 block payload 或主文本

### 护栏 4：严格规范化校验

- 非法 JSON 整体拒绝
- 非法事件类型整条拒绝
- `source_section` 与 `event_type` 不匹配时拒绝
- `source_text` 无法在对应 section 中命中时拒绝
- 当全部事件被拒绝时，必须输出拒绝原因摘要，便于回放定位

### 护栏 5：原始结构化事实优先

- 如果结构化事实与文本语义冲突：
- 原始结构化事实不丢
- 语义事件只作为补充层

## 9.2 EventNormalizer v2 字段校验矩阵

| 字段 | 必填 | 规则 | 动作 | 拒绝条件 |
| --- | --- | --- | --- | --- |
| `status` | 是 | `success` / `skipped` | 小写化 | 非法拒绝整个响应 |
| `confidence` | 是 | `0~1` 数字 | 转 decimal | 非法拒绝整个响应 |
| `events` | 是 | 数组 | 允许空数组 | 非数组拒绝整个响应 |
| `event_time` | 否 | 合法时间或空 | 标准化 | 非法可置空 |
| `event_type` | 是 | 枚举 | 小写化、少量别名修正 | 非法拒绝该事件 |
| `event_subtype` | 建议是 | 枚举或空 | 小写化、少量别名修正 | 非法拒绝该事件 |
| `body_site` | 是 | 枚举 | 小写化 | 非法拒绝该事件 |
| `event_name` | 是 | 非空短文本 | trim | 空值拒绝该事件 |
| `event_value` | 否 | 文本 / 数字 / `null` | 保留 | 无 |
| `event_unit` | 否 | 文本 / `null` | trim | 无 |
| `abnormal_flag` | 否 | 固定集合 | 小写化 | 非法可置空 |
| `infection_related` | 是 | `bool` | 布尔化 | 非法拒绝该事件 |
| `negation_flag` | 是 | `bool` | 布尔化 | 非法拒绝该事件 |
| `uncertainty_flag` | 是 | `bool` | 布尔化 | 非法拒绝该事件 |
| `clinical_meaning` | 是 | `infection_support / infection_against / infection_uncertain / screening / baseline_problem` | 小写化；过渡期仅允许少量软修正，修正记录写入 `normalizer_fallbacks` | 非法且不可修正时拒绝该事件 |
| `source_section` | STRUCTURED_FACT 必填 | 固定 8 值；其它 block 仅允许 `null` | 小写化 | 非法拒绝该事件 |
| `source_text` | 是 | 非空且必须来自对应 section 或 block payload | trim + 溯源校验 | 命中失败拒绝该事件 |
| `evidence_tier` | 是 | `hard` / `moderate` / `weak` | 小写化 | 非法拒绝该事件 |
| `evidence_role` | 是 | `support` / `against` / `risk_only` / `background` | 小写化 | 非法拒绝该事件 |

## 9.3 EventNormalizer v2 组合校验规则

| 规则编号 | 规则 | 处理 |
| --- | --- | --- |
| `R1` | `status=skipped` 时 `events` 必须为空 | 否则拒绝整个响应 |
| `R2` | `source_text` 必须能在 `blockPayload[source_section]` 中命中；非结构化 block 需能在主 payload 命中 | 否则拒绝该事件 |
| `R3` | `source_text` 不能只在 `timelineContext` 中命中 | 否则拒绝该事件 |
| `R4` | `source_section=diagnosis` 时 `event_type` 只能是 `diagnosis` | 否则拒绝该事件 |
| `R5` | `source_section=vital_signs` 时 `event_type` 只能是 `vital_sign` | 否则拒绝该事件 |
| `R6` | `source_section=lab_results` 时 `event_type` 只能是 `lab_result` / `lab_panel` / `microbiology` | 否则拒绝该事件 |
| `R7` | `source_section=imaging` 时 `event_type` 只能是 `imaging` | 否则拒绝该事件 |
| `R8` | `source_section=doctor_orders` 时 `event_type` 只能是 `order` / `device` / `procedure` | 否则拒绝该事件 |
| `R9` | `source_section=use_medicine` 时 `event_type` 只能是 `order` | 否则拒绝该事件 |
| `R10` | `source_section=transfer` 时 `event_type` 只能是 `problem` 或 `assessment` | 否则拒绝该事件 |
| `R11` | `source_section=operation` 时 `event_type` 只能是 `procedure` 或 `order` | 否则拒绝该事件 |
| `R12` | `event_subtype=device_exposure` 时 `event_type` 必须是 `device` | 否则拒绝该事件 |
| `R13` | `event_subtype=procedure_exposure` 时 `event_type` 必须是 `procedure` | 否则拒绝该事件 |
| `R14` | `event_subtype=lab_abnormal` 时 `event_type` 必须是 `lab_result` 或 `lab_panel` | 否则拒绝该事件 |
| `R15` | `event_subtype=culture_positive` 时 `event_type` 必须是 `microbiology` | 否则拒绝该事件 |
| `R16` | `evidence_role=background` 时 `infection_related` 必须为 `false` | 过渡期修正为 `false` 并记录 fallback |
| `R17` | `source_section=vital_signs` 时 `evidence_tier` 不允许 `hard` | 否则拒绝该事件 |
| `R18` | `source_section=diagnosis` 时 `evidence_tier` 不允许 `hard` | 否则拒绝该事件 |
| `R19` | `source_section=transfer` 时 `evidence_role` 不允许 `support` | 否则拒绝该事件 |
| `R20` | `source_section=operation` 且 `evidence_role=support` 时，需有明确感染相关 `source_text` | 否则拒绝该事件 |
| `R21` | 完全重复事件键出现多次 | 去重保留一条 |
| `R22` | `clinical_meaning` 被误写为 `risk_only/support/against/uncertain` 等方向词时 | 仅在 evidence_role、subtype、uncertainty_flag 可约束的少量场景下修正；否则拒绝 |
| `R23` | `evidence_role=against` 但文本只是“预防感染/预防肺部感染/预防误吸”等管理语句 | 作为软丢弃事件跳过；若整批仅此类软丢弃事件，返回空列表而非任务失败 |
| `R24` | `evidence_role=against` 与 `clinical_meaning` 不一致，但 source_text 有明确“不支持感染/排除感染/无感染证据”等反证语义 | 修正 `clinical_meaning=infection_against` 并记录 fallback |

## 9.4 EventNormalizer 过渡期弱化策略

当前 `EventNormalizer` 采用“硬校验 + 软修正 + 软丢弃”的过渡策略，目标是降低少量模型字段漂移导致的任务重试卡死。

硬校验仍然保留：

- JSON 根结构、`status`、`confidence`、`events`
- `event_type`、`event_subtype`、`body_site`、`evidence_tier`、`evidence_role` 基础枚举
- `source_text` 非空与溯源命中
- `source_section` 在 `STRUCTURED_FACT / CLINICAL_TEXT` 下的边界规则
- `event_subtype -> event_type` 匹配规则

软修正只覆盖语义字段漂移：

- `clinical_meaning=risk_only` 且 `evidence_role=risk_only`、`event_subtype=device_exposure/procedure_exposure` 时，修正为 `baseline_problem`
- `clinical_meaning=support/against/uncertain` 在 evidence_role 或 uncertainty_flag 能约束时，修正为 canonical 值
- `evidence_role=background` 但 `infection_related=true` 时，修正 `infection_related=false`
- `evidence_role=against` 但 `clinical_meaning` 不一致时，若 source_text 有明确反证语义，修正为 `infection_against`

软丢弃只覆盖“预防感染/预防肺部感染/预防误吸”等预防性管理语句被误抽成 `infection_negative_statement` 或 `against` 的场景。若整个响应只有这类软丢弃事件，`normalize` 返回空列表，不触发任务级失败。

所有软修正会写入 `attributes_json.normalizer_fallbacks`。该策略不是新增 alias 协议，也不表示 `risk_only` 可作为 `clinical_meaning` 标准值。后续应通过 prompt、schema 化输出与校验反馈重试减少 fallback 命中，稳定后收敛或删除软修正逻辑。

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

当前实现状态：

- 已新增 `infection_case_snapshot` 表结构草案
- 已新增 `InfectionEvidencePacket` / `JudgeDecisionResult` 模型
- 已新增 `InfectionEvidencePacketBuilder` / `InfectionJudgeService`
- 已将第二层最小执行链接入 `CASE_RECOMPUTE`
- 已新增 `infection_alert_result` 最小结果表草案

当前第二层状态是：

- 调度已接线
- snapshot / packet / result 已回写
- 法官节点已切换为单节点 LLM 裁决
- 当法官输出非法或调用失败时，使用确定性 fallback 保底
- 当前仍缺：
  - 更细的 nosocomial 裁决
  - 结果 diff 生成
  - 多节点拆分裁决

当前法官节点的已落地约束：

- 只允许基于 `InfectionEvidencePacket` 裁决
- 输出中的 `newSupportingKeys / newAgainstKeys / newRiskKeys / dismissedKeys`
  必须引用 packet 中真实存在的 `event_key`
- 非法枚举值、缺失关键字段、非法时间会触发 fallback
- fallback 只给出保守的 `no_risk / candidate` 结果，不直接生成激进 warning

## 12.1 基础判断节点

当前已收敛为“确定性预处理 + LLM 法官裁决”两段式。

### 12.1.1 确定性基础判断预处理

以下 4 个基础判断不再交给第二层 LLM 计算，而是在 `InfectionEvidencePacketBuilder` 组包阶段确定性计算，并写入 `InfectionEvidencePacket.precomputed`：

- `newOnsetFlag`
- `after48hFlag`
- `procedureRelatedFlag`
- `deviceRelatedFlag`

当前规则：

- `newOnsetFlag`
  - 基于 `decisionBuckets.newGroupIds` 中新增支持证据组/风险证据组判断
- `after48hFlag`
  - 基于可信入院时间与关键事件时间比较
  - 当前可信入院时间来源：`filter_data_json.admission_time`
  - 无法解析时输出 `unknown`
- `procedureRelatedFlag`
  - 当前第一版按“存在 `procedure_exposure` 风险背景 + 存在支持证据”判断
- `deviceRelatedFlag`
  - 当前第一版按“存在 `device_exposure` 风险背景 + 存在支持证据”判断

这样做的目的：

- 把时间关系、新旧比较、暴露存在性这类结构逻辑从 27B 模型中拿出来
- 降低第二层 prompt 复杂度
- 降低法官节点的输出漂移和 fallback 率

### 12.1.2 第二层法官语义裁决

当前第二层 LLM 法官只负责以下语义裁决字段：

- `infectionPolarity`
- `decisionStatus`
- `warningLevel`
- `primarySite`
- `nosocomialLikelihood`
- `decisionReason`

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
- 不应早于事件入池介入
- 不应等待“当天全部数据结束”后才运行

### 12.4 介入时机

法官节点的合理介入点是：

1. `EVENT_EXTRACT` 已完成
2. 新事件已写入 `infection_event_pool`
3. 命中 `CASE_RECOMPUTE` 触发条件
4. 患者级防抖窗口结束，或达到最大等待阈值

不采用：

- 每落一条事件立即裁决
- 等自然日结束后统一裁决

建议的第一版调度参数：

- `high` 强触发：`5` 分钟防抖
- `normal` 普通触发：`10` 分钟防抖
- `max_wait`：`30` 分钟强制执行

当前代码已落地的第一版行为：

- `CASE_RECOMPUTE` 任务已改成患者级 `merge_key`
- 已写入：
  - `first_triggered_at`
  - `last_event_at`
  - `debounce_until`
  - `trigger_priority`
  - `event_pool_version_at_enqueue`
- 当前强/弱触发仍主要依赖任务优先级映射，后续可再细化

## 13. 硬事实证据包设计

建议新增统一构造对象：

- `InfectionEvidencePacket`

输入来源：

- `infection_event_pool`
- `infection_case_snapshot`
- 最近 7 天 `patient_raw_data.event_json` 构造的窗口 JSON

建议内容：

- `eventCatalog`
- `evidenceGroups`
- `decisionBuckets`
- `backgroundSummary`
- `recentChanges`
- `judgeContext`
- `precomputed`

其中：

- `eventCatalog`
  - 法官可见的原子事件目录，每条事件只出现一次
- `evidenceGroups`
  - 同类证据折叠后的证据簇
- `decisionBuckets`
  - 只保存 `new/support/against/risk` 的 group 引用，不再重复展开事件对象
- `backgroundSummary`
  - `background` 事件统计摘要，不再把全部弱背景明细直接送给法官
- `recentChanges` 使用小型变化摘要，不再使用完整 timeline
- `judgeContext` 只保留少量聚合背景：
  - `recentOperations`
  - `recentDevices`
  - `recentAntibiotics`
  - `majorSites`
- `precomputed` 当前包括：
  - `newOnsetFlag`
  - `after48hFlag`
  - `procedureRelatedFlag`
  - `deviceRelatedFlag`
  - `precomputeReasonJson`

作用：

- 作为法官节点统一输入
- 降低 Prompt 不稳定性
- 便于调试与回放

### 13.1 当前模型结构

当前已落地模型：

- `InfectionEvidencePacket`
- `JudgeCatalogEvent`
- `JudgeEvidenceGroup`
- `JudgeDecisionBuckets`
- `JudgeBackgroundSummary`
- `InfectionRecentChanges`
- `InfectionJudgeContext`
- `InfectionJudgePrecompute`
- `JudgeDecisionResult`

### 13.1.1 当前压缩策略

当前代码已切换为“单份事件目录 + 证据簇 + 引用桶”：

- 原子事件只在 `eventCatalog` 中出现一次
- `evidenceGroups` 负责折叠同类证据
- `decisionBuckets` 只保存 group 引用
- `background` 事件降级为 `backgroundSummary`

这样做的目标：

- 减少法官输入重复
- 保留所有原子事件的追溯关系
- 降低长 `source_text` 和多数组重复展开造成的 token 膨胀

第一版字段收敛目标：

- 法官只看“与裁决有关的事件”
- 不再重新抽取原始事实
- 不再默认回读原始大 JSON
- 不再要求 LLM 计算 48 小时、新发、procedure/device 关联这类结构逻辑

### 13.2 快照表

当前已规划快照表：

- `infection_case_snapshot`

最小字段集：

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

作用：

- 支持法官节点做增量裁决
- 支持 `CASE_RECOMPUTE` 防抖与最大等待控制
- 支持状态迁移，而不是每次全量重建

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

### 14.1 第一版触发分级

强触发：

- `microbiology.culture_positive`
- 明确感染灶影像
- 明确 `infection_positive_statement`
- 明确 `infection_negative_statement`
- `device_exposure`
- `procedure_exposure`
- `antibiotic_upgraded`

普通触发：

- 一般 `lab_abnormal`
- 一般 `antibiotic_started`
- 一般 `risk_only`
- 一般文本判断语义

### 14.2 第一版任务合并规则

`CASE_RECOMPUTE` 应按患者合并，而不是按单条事件重复建任务。

推荐 `merge_key`：

- `CASE_RECOMPUTE:{reqno}`

目标：

- 同一患者同一时间窗只保留一条有效重算任务
- 新触发只刷新：
  - `last_event_at`
  - `debounce_until`
  - `trigger_priority`
  - `event_pool_version_at_enqueue`

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

### `event_json`

- 生成逻辑：`com.zzhy.yg_ai.ai.agent.SummaryAgent`
- 语义：单日时间轴摘要

## 18. 验收标准

- 输入某个 `reqno + data_date`，能成功生成事件并写入 `infection_event_pool`
- 同一批重复执行，不产生重复事件，`event_key` 保持幂等
- 至少覆盖 4 类 block：
  - 结构化事实
  - 临床文本
  - 中间层语义
  - 摘要上下文
- LLM 返回非法 JSON 时，写入 `infection_llm_node_run` 错误记录，并保底入 `assessment` 事件
- 低置信度时，语义事件能正确标记或跳过
- 窗口 JSON 不直接落入事件池，只生成上下文对象

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
