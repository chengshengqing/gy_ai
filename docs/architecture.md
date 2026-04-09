# yg_ai 架构设计

## 1. 文档目标

本文档描述 `yg_ai` 项目的系统边界、模块职责、核心运行链路、数据依赖和当前架构状态，作为后续功能设计、Codex 开发协作和文档维护的基础。

## 2. 系统定位

`yg_ai` 是一个面向医疗住院业务数据的 AI 辅助分析后端，目标包括：

- 采集患者住院原始数据
- 按天生成可用于模型处理的病程语义块
- 用大模型生成结构化摘要与时间线
- 提供时间线展示接口
- 对病程内容进行传染病症候群监测

当前系统是单体架构，不区分独立微服务。

当前采集主链路已经从“定时任务直接采集并继续做摘要”演进为“任务驱动的两段式流水线”：

- 扫描阶段只负责识别需要采集的 `reqno` 并写入采集任务表
- 采集执行阶段只负责查询业务源表并更新 `patient_raw_data`
- 下游摘要、预警或扩展任务通过独立任务表解耦，不再直接耦在原始采集流程里

当前已经完成的业务能力：

- 医疗住院数据采集
- 病程结构化摘要
- 时间线展示
- 传染病症候群监测的 AI 辅助分析

## 2.1 当前实现口径（2026-04）

当前代码已经完成以下收敛：

- `SummaryAgent` 只生成单日结果，不再维护全量 timeline
- `patient_raw_data.struct_data_json` 保存单日结构化中间结果
- `patient_raw_data.event_json` 保存单日时间轴摘要
- 时间线展示直接分页读取 `patient_raw_data.event_json`
- 事件抽取上下文通过公共方法按 `reqno + anchorDate` 查询最近 7 天窗口 JSON
- `patient_raw_data_change_task` 只负责结构化链路
- `infection_event_task` 负责事件抽取与病例重算链路
- `enqueuePendingPatients()` 已切换为按上游批次时间推进，而不是每轮按当前时间强制重扫
- 结构化调度前会巡检并补建 `appendChanges()` 漏掉的 change task

下一阶段主任务：

- 院感预警分析

规划任务：

- 最终审核 Agent
  当前仅做规划，不进入开发实现

## 3. 总体架构

```text
               +-------------------+
               |   SQL Server      |
               |  住院业务数据源     |
               +---------+---------+
                         |
                         v
      +---------------------------------------------+
      | InfectionMonitorScheduler                   |
      | 1. 按 source_batch_time 扫描入队              |
      | 2. 执行采集任务                               |
      +----------------------+----------------------+
                             |
                             v
                 +-----------+------------+
                 | patient_raw_data_      |
                 | collect_task           |
                 +-----------+------------+
                             |
                             v
                 +-----------+------------+
                 | InfectionPipeline      |
                 | processPendingRawData  |
                 +-----------+------------+
                             |
                             v
                 +-----------+------------+
                 | PatientService         |
                 | 原始数据查询与按天组装   |
                 +-----------+------------+
                             |
                             v
                 +-----------+------------+
                 | patient_raw_data       |
                 | 原始每日快照落库         |
                 +-----------+------------+
                             |
              +--------------+---------------+
              |                              |
              v                              v
  +-----------+------------+    +------------+-----------+
  | patient_raw_data_      |    | infection_event_task   |
  | change_task            |    | EVENT_EXTRACT / CASE   |
  | 仅结构化链路             |    | 仅预警链路              |
  +-----------+------------+    +------------+-----------+
              |                              |
              v                              v
  +-----------+------------+    +------------+-----------+
  | StructDataFormat       |    | SummaryWarning         |
  | Scheduler              |    | Scheduler              |
  +-----------+------------+    +------------+-----------+
              |                              |
              v                              v
  +-----------+------------+    +------------+-----------+
  | SummaryAgent           |    | LlmEventExtractor      |
  | 单日 struct / event    |    | EventNormalizer        |
  +-----------+------------+    +------------+-----------+
              |                              |
              v                              v
  +-----------+------------+    +------------+-----------+
  | patient_raw_data       |    | infection_event_pool   |
  | struct_data_json       |    | infection_llm_node_run |
  | event_json             |    +------------+-----------+
  +-----------+------------+                 |
              |                              v
              v                  +-----------+------------+
  +-----------+------------+     | CASE_RECOMPUTE 占位任务 |
  | TimelineViewService    |     | 后续接法官节点           |
  +------------------------+     +------------------------+
                 +------------+------------+
                 | REST API / Static UI    |
                 +-------------------------+
```

## 3.1 当前采集设计原则

### 1. 原始数据与规则数据分层存储

`patient_raw_data` 当前按以下原则落库：

- `data_json` 只保存原始采集块，不做规则加工
- `filter_data_json` 保存规则处理后的可读事实块
- `data_json` 与 `filter_data_json` 当前都额外包含顶层字段 `admission_time`、`patient_summary`
- `struct_data_json` 保存 LLM 结构化结果
- `event_json` 保存单日时间轴摘要，是时间线展示和窗口上下文的直接来源
- `is_del` 用作逻辑删除标记，查询 `patient_raw_data` 时默认只消费 `is_del = 0` 的快照
- 字段级结构说明见 `docs/patient-raw-json-structure.md`

处理顺序为：

1. 查询业务源表
2. 按天聚合为 `DailyPatientRawData`
3. 原样写入 `data_json`
4. 规则处理写入 `filter_data_json`
5. 后续再由摘要或预警链路消费

### 2. 原始采集只做事实更新，不做下游业务耦合

当前采集阶段的职责被限制为：

- 识别新患者 / 增量患者
- 识别本轮 `changedTypes`
- 查询需要的业务源表
- 更新 `patient_raw_data`
- 更新 `last_time`
- 写入 `patient_raw_data_change_task`

当前不在这一阶段直接决定摘要、预警、审核等后续业务执行。

### 3. 现有患者按变更类型查询

对于已有患者：

- 先基于各业务表 `last_time` 识别 `changedTypes`
- 再按 `changedTypes` 定向查询对应业务表
- 能按主键命中时做增量 merge
- 只有旧 `data_json` 缺少对应原始块时，才回退到单日全量重建

并行存在的第二条链路：

```text
PatillnessCourseInfo -> SurveillanceAgent -> 校验 -> ai_process_log / items_infor_zhq
```

下一阶段将新增第三条核心链路：

```text
patient_raw_data
    -> 增量差异识别
    -> EvidenceBlock 切分
    -> LlmEventExtractor
    -> EventNormalizer
    -> infection_event_pool
    -> infection_case_snapshot
    -> 院感法官节点
    -> infection_alert_result
```

## 3.2 架构总目标

系统目标不是单次全量分析器，而是：

> 以住院病例为单位、基于每日增量数据持续更新的院感监测系统

其核心形态是：

> 增量接入 + 事件驱动 + 状态快照 + LLM 判决 + 结果版本化

## 3.3 总体设计原则

### 1. 增量模式，而不是每日全量重跑

系统按日定时拉取增量数据，但内部分析不能退化成“每天把整个病例历史重新分析一遍”。

正确处理方式是：

- 接收新的病例快照
- 和历史快照比较
- 识别新增 / 更正 / 撤销
- 将变化转成标准化事件
- 基于事件更新病例状态
- 只对受影响病例做局部重算

### 2. 工作流骨架保留，但复杂判断交给 LLM

## 3.4 运行监控边界

当前项目新增的 Pipeline 监控能力保持轻量实现，不引入独立监控平台，也不新增数据库监控表。

实现约束：

- 监控数据写入 Redis，只保留近实时和近 24 小时窗口聚合
- 监控切面固定在 `WorkUnitExecutor`、`ModelCallGuard`、`AbstractTaskHandler`、`LoadEnqueueHandler`
- 不把监控统计逻辑耦合进 `PatientServiceImpl`、`LlmEventExtractorServiceImpl`、`InfectionJudgeServiceImpl` 等业务编排
- backlog 通过只读 Mapper 周期性聚合现有任务表，再写入 Redis 快照

当前监控页聚焦四类信息：

- 共享线程池和模型并发令牌实时状态
- 当前活跃 `work unit`
- `LOAD_PROCESS / NORMALIZE / EVENT_EXTRACT / CASE_RECOMPUTE` backlog
- 近 `3h / 12h / 24h` 的任务吞吐和 LLM 延迟统计

整体架构仍然是工作流主架构：

- 工作流负责调度
- 数据层负责证据组织
- LLM 负责复杂医学判断
- 规则只负责约束、校验、兜底

### 3. 事件池是院感分析中枢

后续院感预警分析不应直接围绕原始 JSON 反复重做判断，而应围绕标准化事件池展开。

中枢对象是：

- `infection_event_pool`

### 4. Summary 只做上下文，不做最终事实源

在后续院感预警分析中：

- `filter_data_json` 是主事实源
- `struct_data_json` 是中间层语义增强源
- 时间轴窗口 JSON 是病程背景上下文源

最终分析仍以“原始事实 + 标准化事件”为主。

## 4. 模块划分

### 4.1 调度层 `task`

职责：

- 驱动周期性任务
- 作为主流程入口
- 避免承载业务细节

模块：

- `InfectionMonitorScheduler`
  定时扫描患者并写入 `patient_raw_data_collect_task`
- `InfectionMonitorScheduler.processPendingCollectTasks`
  定时消费原始采集任务，执行详情查询与落库
- `StructDataFormatScheduler`
  定时消费待结构化病程记录
- `InfectiousSyndromeSurveillanceTask`
  传染病症候群监测任务
- `SummaryWarningScheduler`
  预留任务

设计说明：

- 调度层只负责触发，不做复杂数据拼装。
- 原始采集已拆分为“扫描入队”和“执行采集”两个独立任务。
- 当前症候群监测任务默认未启用自动调度。

### 4.2 编排层 `ai/orchestrator`

核心类：

- `InfectionPipeline`

职责：

- 组织原始采集任务消费与结构化摘要任务消费
- 协调 `PatientService`、`SummaryAgent`
- 负责待处理批次的并发消费

设计说明：

- 该类是院感主链路的统一业务编排入口。
- 原始采集阶段的过滤规则构建已下沉到 `PatientService`，不再在编排层兜底构建 `filter_data_json`。
- 当前类中已经注入 `WarningAgent`、`AuditAgent`、`StringRedisTemplate`，但主流程尚未完整使用这些能力。
- 下一阶段可继续作为上游编排入口之一，但不应直接承担全部事件池与法官节点逻辑。

### 4.3 AI 层 `ai/agent`

职责：

- 封装大模型调用
- 维护 Prompt 输入输出结构
- 将原始文本转为可落库或可展示的结构化结果

核心模块：

- `AbstractAgent`
  提供统一 `ChatModel` 调用能力
- `FormatAgent`
  当前主要负责面向 LLM 的格式化与规整，原始采集阶段的规则过滤已下沉到 `PatientService`
- `SummaryAgent`
  负责每日病程摘要抽取与时间线追加
- `SurveillanceAgent`
  负责症候群监测分析

预留模块：

- `WarningAgent`
- `AuditAgent`

后续规划模块：

- `LlmEventExtractor`
- 院感法官节点
- 最终审核 Agent

设计原则：

- Agent 不直接持有过多业务状态
- 输入输出尽量 JSON 化、结构化
- 复杂业务协调尽量放在编排层或 Service 层
- 最终审核 Agent 当前只保留在规划层

### 4.4 业务服务层 `service`

职责：

- 负责核心业务处理
- 承接 Controller / Task 与 Mapper / AI 之间的桥接
- 负责数据装配、持久化和结果转换

核心服务：

- `PatientServiceImpl`
  原始数据采集、按天聚合、`data_json/filter_data_json` 落库、增量 merge、变更任务写入
- `PatientTimelineViewServiceImpl`
  单日摘要转时间线展示数据
- `AiProcessLogServiceImpl`
  AI 处理日志记录

设计说明：

- `PatientServiceImpl` 是当前数据加工主中心。
- `PatientTimelineViewServiceImpl` 是展示输出层中心。

### 4.5 数据访问层 `mapper`

职责：

- 统一数据库读写入口
- 保持 SQL 与实体映射清晰

核心 Mapper：

- `PatientRawDataMapper`
- `PatillnessCourseInfoMapper`
- `ItemsInforZhqMapper`
- `AiProcessLogMapper`

设计说明：

- `PatientRawDataMapper.xml` 包含最重的查询逻辑，负责从业务库抽取患者多维住院数据。
- 当前项目优先依赖 MyBatis-Plus 与 XML 映射，不应在 Service 中直接堆 JDBC 逻辑。

后续建议新增的数据对象：

- `infection_event_pool`
- `infection_llm_node_run`
- `infection_case_snapshot`
- `infection_alert_result`

### 4.6 展示转换层

核心类：

- `PatientTimelineViewServiceImpl`
- `TimelineViewRuleProperties`

职责：

- 读取分页 `event_json`
- 识别主问题、次问题、风险项
- 生成时间线展示字段
- 将规则配置与业务数据结合

设计说明：

- 展示逻辑采用“Java 规则逻辑 + YAML 规则配置”的混合方式。
- 扩展展示策略时优先考虑规则配置，而不是直接写死在 Java 代码中。

## 5. 核心运行链路

### 5.1 原始数据采集链路

触发入口：

- `InfectionMonitorScheduler.enqueuePendingPatients()`
- `InfectionMonitorScheduler.processPendingCollectTasks()`

执行过程：

1. 从上游表读取本轮 `source_batch_time`
2. 若 `source_batch_time` 未超过已入队批次，则跳过本轮扫描
3. 查询待处理患者 `reqno`
4. 将 `reqno + previousSourceLastTime + sourceBatchTime` 写入 `patient_raw_data_collect_task`
5. 采集执行任务 claim 原始采集任务
6. `PatientServiceImpl.collectAndSaveRawDataResult(reqno, previousSourceLastTime, sourceBatchTime)` 识别：
   - 是否为新患者
   - 上一批次时间
   - 本轮 `changedTypes`
7. 按查询类型读取业务源表：
   - 新患者：`PatientCourseDataType.fullSnapshot()`
   - 已有患者：按 `changedTypes` 定向查询
8. 将结果按天分组
9. 原始块写入 `patient_raw_data.data_json`
10. 规则处理结果写入 `patient_raw_data.filter_data_json`
11. 更新 `patient_raw_data.last_time`
12. 为每条变更快照写入 `patient_raw_data_change_task`
13. 若 `changedTypes` 命中事件来源规则，则同时写入 `infection_event_task(EVENT_EXTRACT)`

架构特征：

- 采用“源库读取 + 本地中间表缓存”的模式
- 原始采集与下游摘要已解耦
- 原始采集与预警主链路、结构化链路通过两张任务表并行解耦
- 为后续 LLM、预警和扩展任务提供统一事实输入
- 数据输入模型属于每日定时增量拉取
- 新患者限制为最近 30 天入院
- 增量患者候选必须已经存在于 `patient_raw_data`

### 5.2 结构化摘要链路

触发入口：

- `StructDataFormatScheduler.formatPendingStructData()`

执行过程：

1. claim `patient_raw_data_change_task` 中可执行的变更任务
2. 按 `reqno` 聚合本轮 change 行
3. 对每个患者过滤 stale change，只保留 `raw_data_last_time` 仍等于 `patient_raw_data.last_time` 的记录
4. 逐条处理仍有效的单日变更行
5. 清空该日的 `struct_data_json / event_json`
6. 调用 `SummaryAgent.extractDailyIllness()` 生成单日结果
7. 回写当前行 `patient_raw_data`
8. 将本轮 change 行标记为 `SUCCESS` 或 `FAILED`

当前补充：

- 不再做“从最早变更日开始”的整段重放
- 缺失的 change task 会在调度前巡检补建

架构特征：

- 摘要采用增量构建，而不是每次整份重算
- 摘要调度已经完全建立在 `patient_raw_data_change_task` 上
- 结构化链路与事件链路已经分离，结构化不再阻塞预警主链路
- `pat_illnessCourse` 的过滤规则已经前移到采集落库阶段
- 时间线在落库前会按时间排序

### 5.3 事件抽取链路

触发入口：

- `SummaryWarningScheduler.processPendingEventTasks()`

执行过程：

1. claim `infection_event_task` 中 `task_type=EVENT_EXTRACT` 的任务
2. 按 `patient_raw_data_id + raw_data_last_time` 读取最新有效快照
3. 调用 `PatientService.buildSummaryWindowJson(reqno, dataDate, 7)` 生成最近窗口上下文
4. 调用 `InfectionEvidenceBlockService.buildBlocks(rawData, timelineWindowJson)` 构建证据块
5. 调用 `LlmEventExtractorService.extractAndSave(buildResult)` 完成事件抽取、规范化与事件池写入
6. 若本轮产生新的事件池结果，则合并写入 `infection_event_task(CASE_RECOMPUTE)`
7. 当前 `EVENT_EXTRACT` 任务标记为 `SUCCESS / FAILED / SKIPPED`

架构特征：

- 事件链路由 `infection_event_task` 独立承载
- 候选层不额外调用 LLM，只按变更来源路由到事件抽取
- `ILLNESS_COURSE` 一旦新增，直接进入事件抽取任务
- 结构化数据是事件抽取的增强背景，而不是前置阻塞条件
### 5.4 病例重算链路

触发入口：

- `SummaryWarningScheduler.processPendingCaseTasks()`

执行过程：

1. claim `infection_event_task` 中 `task_type=CASE_RECOMPUTE` 的任务
2. 当前实现先以占位任务形式记录病例级重算入口
3. 后续会在该节点接入法官节点、快照更新和结果版本输出

当前状态：

- 任务表与调度入口已落地
- 法官节点尚未接入，当前只保留占位执行与重试框架

### 5.5 时间线展示链路

触发入口：

- `/api/patient-summary/timeline-view`

执行过程：

1. 分页查询某患者 `patient_raw_data.event_json`
2. 逐条解析单日摘要
3. 生成时间线节点
4. 应用规则配置：
   - 主问题识别
   - 风险项识别
   - 标签生成
   - 徽章生成
   - 严重级别映射
5. 输出 `PatientTimelineViewData`

架构特征：

- 展示模型与存储模型解耦
- 对前端暴露的是稳定视图对象，而不是原始摘要 JSON

### 5.6 症候群监测链路

触发入口：

- `InfectiousSyndromeSurveillanceTask.executeClassify()`

执行过程：

1. 分页查询病程信息
2. 构造监测输入文本
3. 调用 `SurveillanceAgent` 触发模型分析
4. 校验输出 JSON
5. 成功写入 `ai_process_log`
6. 对中高风险结果写入 `items_infor_zhq`

架构特征：

- 这条链路和主时间线链路基本并行
- 当前更接近“独立分析任务”，尚未完全并入统一院感编排

### 5.7 下一阶段院感预警分析链路

这是下一阶段的主开发任务。

目标主流程：

```text
每日定时拉取 patient_raw_data
    -> 按 reqno + data_date 读取病例快照
    -> 与历史快照做差异识别
    -> 切分 4 类 EvidenceBlock
    -> 调用统一 LLM 事件抽取器
    -> EventNormalizer 规范化
    -> 写入 infection_event_pool
    -> 更新 infection_case_snapshot
    -> 判断是否需要触发局部重算
    -> 构建硬事实证据包
    -> 调用院感法官节点
    -> 产出结果版本
    -> 更新 infection_alert_result / infection_case_snapshot
```

#### 4 类 EvidenceBlock

- `StructuredFactBlock`
  来自 `filter_data_json` 的结构化事实块
- `ClinicalTextBlock`
  来自病程、查房、会诊、申请等临床文本块
- `MidSemanticBlock`
  来自 `struct_data_json` 的中间层语义块
- `TimelineContextBlock`
  来自最近 7 天 `event_json` 现拼窗口 JSON 的时间轴背景块，仅供 LLM 参考

#### EventNormalizer 作用

统一事件抽取结果入库前必须经过规范化，至少完成：

- 时间标准化
- 枚举校验
- 默认值填充
- `event_key` 生成
- 幂等去重
- 非法输出兜底

#### LLM 运行记录

后续所有院感预警相关模型调用都应记录到：

- `infection_llm_node_run`

#### 触发局部重算原则

建议触发完整分析的关键事件：

- 新发热
- WBC / CRP / PCT 异常
- 培养送检 / 阳性
- 抗菌药新开或升级
- 新手术 / 导尿 / 置管 / 插管
- 文本出现“感染 / 排除感染 / 污染 / 定植”
- 影像提示感染灶

仅更新事件池但不触发完整分析的情况：

- 与感染无关的重复数据
- 非关键字段修订
- 纯背景摘要变化

## 6. 数据模型与存储角色

### 6.1 SQL Server

角色：

- 原始业务数据源

特征：

- 保存住院业务主数据
- 当前通过 Mapper XML 进行复杂查询拼装

### 6.2 `patient_raw_data`

角色：

- 中间层原始语义块存储表

典型字段用途：

- `data_json`
  每日聚合后的原始病程语义块
- `filter_data_json`
  去重过滤后的病程文本
- `struct_data_json`
  AI 结构化处理结果

### 6.3 `ai_process_log`

角色：

- AI 调用日志

用途：

- 记录成功与失败
- 保存原始响应和错误信息

### 6.4 `items_infor_zhq`

角色：

- 症候群监测结果业务落表

用途：

- 保存风险等级
- 保存症候群类型
- 保存关键证据与建议动作

### 6.5 Redis

当前角色：

- 已完成配置
- 预期用于业务缓存或 AI 记忆

当前状态：

- 主链路中未形成强依赖

### 6.6 下一阶段新增核心表

#### `infection_event_pool`

角色：

- 院感分析中枢事件池

#### `infection_llm_node_run`

角色：

- 院感预警相关 LLM 节点运行记录

#### `infection_case_snapshot`

角色：

- 每个病例当前最新的院感状态快照

#### `infection_alert_result`

角色：

- 每次院感预警分析后的结果版本记录

## 7. 配置架构

### 7.1 主配置 `application.yaml`

负责：

- 服务端口
- 数据源
- Redis
- 模型服务
- MyBatis Mapper 路径
- 调度线程池

问题：

- 当前存在较强的环境耦合
- 敏感信息与环境信息需要后续拆分

### 7.2 规则配置 `timeline-view-rules.yaml`

负责：

- 状态识别
- 风险识别
- 发热、手术、会诊等模式匹配
- 徽章和标签映射

优点：

- 部分展示逻辑配置化

限制：

- 高复杂判断仍在 Java 代码中

## 8. 已实现能力与预留能力

### 8.1 已实现能力

- 原始住院数据采集
- 病程按天聚合
- AI 结构化摘要
- 时间线视图转换
- 症候群监测单链路逻辑
- 传染病症候群监测 AI 辅助分析

### 8.2 预留能力

- Warning 能力
- Audit 能力
- SummaryWarning 调度
- Redis 记忆增强
- ReactAgent 深度接入
- 最终审核 Agent

### 8.3 当前进入开发的下一阶段能力

- 增量差异识别
- 标准化事件入池
- 院感法官基础节点
- 病例状态快照
- 结果版本化

## 9. 当前架构问题

### 9.1 主链路与预留能力混杂

`InfectionPipeline` 注入的部分组件尚未实际参与核心流程，容易让阅读者误判系统已具备完整告警与审计能力。

建议：

- 在代码和文档中明确“已启用”和“预留”模块边界

### 9.2 数据扫描策略仍偏开发态

当前患者列表查询存在硬编码白名单。

建议：

- 尽快改为配置化或业务规则驱动

### 9.3 配置管理不够环境化

当前配置文件中保留强环境依赖信息。

建议：

- 引入 `application-local.yaml`
- 使用环境变量或配置中心

### 9.4 自动化测试不足

系统对：

- 数据正确性
- Prompt 输出结构稳定性
- 时间线转换稳定性

缺少有效回归测试。

## 10. 后续演进建议

### 10.1 短期

- 标准化事件入池
- 建立 `infection_event_pool`
- 建立 `infection_llm_node_run`
- 梳理增量差异识别层
- 明确院感法官节点输入输出契约

### 10.2 中期

- 建立 `infection_case_snapshot`
- 事件驱动局部重算
- 院感法官基础节点
- 建立 `infection_alert_result`
- 页面联动与解释输出

### 10.3 长期

- 将 Prompt、规则、校验进一步标准化
- 引入人工复核闭环
- 规划并设计最终审核 Agent
- 形成 AI 处理链路的可观测性和回放能力

## 11. 架构结论

`yg_ai` 当前是一个以“住院病程增量采集与 AI 时间线摘要”为已完成主链路、以“院感预警分析”为下一阶段主任务的单体后端系统；系统后续将围绕标准化事件池、病例状态快照、LLM 法官节点和结果版本化展开演进，而最终审核 Agent 当前仅保留在规划层。
