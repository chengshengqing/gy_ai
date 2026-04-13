# yg_ai 架构设计

## 1. 文档目标

本文档描述 `yg_ai` 项目的系统边界、模块职责、核心运行链路、数据依赖和当前架构状态，作为后续功能设计、Codex 开发协作和文档维护的主文档。

本文档以当前代码和配套文档为准，重点同步 2026-04 架构重构后的状态：

- 调度主链路统一收敛到 `pipeline`
- 模型调用外部边界统一收敛到 `ai.gateway.AiGateway`
- `domain.normalize`、`domain.format` 已按职责拆分
- warning 侧 helper 不继续平铺进 `domain`，按 use case 收敛到 `service.evidence`、`service.event`、`service.casejudge`
- 院感预警基础链路已围绕 `infection_event_pool`、`infection_llm_node_run`、`infection_case_snapshot`、`infection_alert_result` 展开

## 2. 系统定位

`yg_ai` 是一个面向医疗住院业务数据的 AI 辅助分析后端，当前是单体架构，不拆分独立微服务。

系统当前已成型的主链路是：

```text
SQL Server 原始住院数据
  -> patient_raw_data
  -> LLM 单日结构化摘要
  -> patient_raw_data.struct_data_json / patient_raw_data.event_json
  -> timeline API / 静态展示页
```

当前已经完成的业务能力：

- 医疗住院数据采集
- 病程结构化摘要
- 时间线展示
- 传染病症候群监测的 AI 辅助分析
- 标准化事件入池基础链路
- 病例级院感法官基础节点
- 病例状态快照和预警结果版本落库

下一阶段主任务仍是继续完善院感预警分析：

- 增量差异识别和标准化事件入池稳定性
- `infection_event_pool` 事件治理
- `infection_llm_node_run` 模型运行审计
- `infection_case_snapshot` 病例状态快照
- `infection_alert_result` 结果版本化
- 页面联动与人工复核闭环

规划任务：

- 最终审核 Agent

当前只做架构规划或接口预留，不进入正式实现。

## 2.1 当前实现口径

当前代码口径如下：

- `@Scheduled` 只负责触发，不直接承载重业务
- 调度主链路统一为 `@Scheduled -> InfectionPipelineFacade -> StageDispatcher -> StageCoordinator -> WorkUnitExecutor -> Handler`
- `InfectionPipelineFacade` 是具体类，不再保留接口 + `Default*` 实现双层壳
- 旧的大而全 `InfectionPipeline` 已退出当前代码主链路
- 旧 `SummaryAgent`、`FormatAgent` 已退出当前代码主链路
- `NormalizeHandler` 负责结构化任务编排、单行处理、任务状态收尾
- `NormalizeStructDataService` 负责串联 normalize 三层调用
- `NormalizeContextBuilder` 负责 normalize 输入准备、day context 构造、fusion 输入组装
- `DailyFusionInputCompactor` 负责 daily fusion 输入预算判断；默认保留完整输入，仅在超过配置阈值时分级压缩
- `NormalizeResultAssembler` 负责 note / daily fusion 的模型结果校验、重试与最终 JSON 组装
- `patient_raw_data.struct_data_json` 保存单日结构化中间结果
- `patient_raw_data.event_json` 保存单日时间轴摘要，是时间线展示和事件抽取窗口上下文的直接来源
- `patient_raw_data_change_task` 只负责结构化链路
- `infection_event_task` 负责 `EVENT_EXTRACT` 与 `CASE_RECOMPUTE` 链路
- `SummaryWarningScheduler` 当前作为事件抽取和病例重算触发入口，已经接入 `InfectionPipelineFacade`
- 真实模型调用统一通过 `AiGateway -> ModelCallGuard -> Spring AI / vLLM`

## 3. 总体架构

```text
                         +---------------------+
                         |     SQL Server      |
                         |   住院业务数据源       |
                         +----------+----------+
                                    |
                                    v
       +----------------------------+----------------------------+
       | @Scheduled Trigger Layer                                |
       | InfectionMonitorScheduler / StructDataFormatScheduler   |
       | SummaryWarningScheduler                                 |
       +----------------------------+----------------------------+
                                    |
                                    v
       +----------------------------+----------------------------+
       | InfectionPipelineFacade -> StageDispatcher              |
       | LOAD_ENQUEUE / LOAD_PROCESS / NORMALIZE                 |
       | EVENT_EXTRACT / CASE_RECOMPUTE                          |
       +----------------------------+----------------------------+
                                    |
                                    v
       +----------------------------+----------------------------+
       | StageCoordinator -> WorkUnitExecutor -> Handler          |
       | 共享线程池调度 + 阶段策略 + 模型并发限流                  |
       +----------------------------+----------------------------+
                     |                         |                  |
                     v                         v                  v
       +-------------+-------------+   +-------+------+   +-------+------+
       | LOAD_PROCESS              |   | NORMALIZE    |   | EVENT_EXTRACT|
       | PatientServiceImpl        |   | Normalize*   |   | EvidenceBlock|
       +-------------+-------------+   +-------+------+   +-------+------+
                     |                         |                  |
                     v                         v                  v
       +-------------+-------------+   +-------+------+   +-------+------+
       | patient_raw_data          |   | struct/event |   | infection_  |
       | data_json/filter_data_json|   | json         |   | event_pool  |
       +-------------+-------------+   +-------+------+   +-------+------+
                     |                         |                  |
                     |                         v                  v
                     |              +----------+-------+   +------+-------+
                     |              | Timeline API     |   | CASE_RECOMPUTE|
                     |              | Static UI        |   | Case Judge    |
                     |              +------------------+   +------+-------+
                     |                                            |
                     v                                            v
       +-------------+-------------+                 +------------+------------+
       | patient_raw_data_change_  |                 | infection_case_snapshot |
       | task                      |                 | infection_alert_result  |
       +---------------------------+                 +-------------------------+
```

## 3.1 调度执行架构

当前调度执行架构分为 5 层：

1. Trigger Layer
   接收 `@Scheduled` 触发，只发起阶段触发信号。

2. Pipeline Facade Layer
   由 `InfectionPipelineFacade` 暴露统一触发入口，不负责任务 claim、线程调度或业务执行。

3. Scheduling Layer
   由 `pipeline.scheduler` 维护共享线程池、阶段策略、运行态和模型调用并发令牌。

4. Stage Coordination Layer
   由 `pipeline.stage` 负责每个阶段的 claim 和 work unit 组装，只拉一小批任务并投递。

5. Business Execution Layer
   由 `pipeline.handler` 负责采集、结构化、事件抽取、病例重算等具体业务。

当前阶段枚举：

- `LOAD_ENQUEUE`
- `LOAD_PROCESS`
- `NORMALIZE`
- `EVENT_EXTRACT`
- `CASE_RECOMPUTE`

当前 `pipeline.scheduler` 已按职责拆为：

- `executor`
  - `StageDispatcher`
  - `WorkUnit`
  - `WorkUnitExecutor`
- `limiter`
  - `ModelCallGuard`
- `policy`
  - `PipelineStage`
  - `StagePolicy`
  - `StagePolicyRegistry`
- `runtime`
  - `StageRuntimeRegistry`

当前 `StagePolicyRegistry` 已支持普通时段与 `NORMALIZE` 优先窗口切换：

- 普通时段：`LOAD_PROCESS`、`EVENT_EXTRACT` 保持较高并发，`NORMALIZE` 使用常规配额
- `NORMALIZE` 优先窗口：`NORMALIZE` 提升优先级和 in-flight 配额，`LOAD_PROCESS`、`EVENT_EXTRACT`、`CASE_RECOMPUTE` 保留较低配额

配置项：

- `infection.pipeline.normalize-window-start-hours`
- `infection.pipeline.normalize-window-duration-hours`
- `infection.pipeline.model-permits`

## 3.2 模型调用边界

当前真实模型调用统一收敛为：

```text
Handler / Service
  -> AiGateway
  -> ModelCallGuard
  -> Spring AI ChatModel / vLLM
```

职责边界：

- `AiGateway` 负责构造 Spring AI `Prompt`、调用模型、归一化 JSON 输出
- `ModelCallGuard` 负责模型并发 permit、模型调用耗时和监控埋点
- 业务 service 不直接维护模型 permit，也不直接控制共享线程池

当前重点调用点包括：

- `NormalizeResultAssembler`
- `StructuredFactRefinementServiceImpl`
- `LlmEventExtractorServiceImpl`
- `InfectionJudgeServiceImpl`

## 3.3 增量模式

系统目标不是单次全量分析器，而是：

> 以住院病例为单位、基于每日增量数据持续更新的院感监测系统

核心形态是：

> 增量接入 + 事件驱动 + 状态快照 + LLM 判决 + 结果版本化

后续院感预警分析必须遵守：

- 每日定时拉取增量数据
- 比较新旧快照
- 识别新增 / 更正 / 撤销
- 将差异转为标准化事件
- 基于事件更新病例状态
- 只对受影响病例做局部重算

不得退化为每日全量重跑整个病例历史。

## 3.4 事件池中枢

后续院感预警分析不应围绕原始 JSON 反复重做判断，而应围绕标准化事件池展开。

核心对象：

- `infection_event_pool`
- `infection_llm_node_run`
- `infection_case_snapshot`
- `infection_alert_result`

事实源分工：

- `patient_raw_data.data_json`
  每日聚合后的原始事实块
- `patient_raw_data.filter_data_json`
  规则过滤后的主事实源
- `patient_raw_data.struct_data_json`
  LLM 结构化中间语义增强源
- `patient_raw_data.event_json`
  时间线展示和最近窗口上下文源
- `infection_event_pool`
  院感分析的标准化事件池

## 3.5 运行监控边界

当前 Pipeline 监控保持轻量实现，不引入独立监控平台，也不新增数据库监控表。

实现约束：

- 监控数据写入 Redis，只保留近实时和近 24 小时窗口聚合
- 监控切面固定在 `WorkUnitExecutor`、`ModelCallGuard`、`AbstractTaskHandler`、`LoadEnqueueHandler`
- backlog 通过只读 Mapper 周期性聚合现有任务表，再写入 Redis 快照
- 不把监控统计逻辑耦合进 `PatientServiceImpl`、`LlmEventExtractorServiceImpl`、`InfectionJudgeServiceImpl` 等业务编排

监控入口：

- 接口：`/api/pipeline-monitor/dashboard`
- 页面：`/pipeline_monitor_dashboard.html`

监控范围：

- 共享线程池总线程 / 活跃线程 / 空闲线程 / 队列长度
- 当前活跃 `work unit`
- `LOAD_PROCESS / NORMALIZE / EVENT_EXTRACT / CASE_RECOMPUTE` backlog
- 近 `3h / 12h / 24h` 的任务吞吐和 LLM 延迟统计

## 4. 模块划分

### 4.1 `task`

职责：

- 驱动周期性任务
- 作为主流程触发入口
- 避免承载业务细节

核心类：

- `InfectionMonitorScheduler`
  - `enqueuePendingPatients()` 触发 `LOAD_ENQUEUE`
  - `processPendingCollectTasks()` 触发 `LOAD_PROCESS`
- `StructDataFormatScheduler`
  - `formatPendingStructData()` 触发 `NORMALIZE`
- `SummaryWarningScheduler`
  - `processPendingEventTasks()` 触发 `EVENT_EXTRACT`
  - `processPendingCaseTasks()` 触发 `CASE_RECOMPUTE`
- `InfectiousSyndromeSurveillanceTask`
  传染病症候群监测任务，和当前 patient_raw_data 主链路基本并行

设计说明：

- 调度层只调用 `InfectionPipelineFacade`
- 调度层不直接依赖 handler 或 service

### 4.2 `pipeline`

职责：

- 共享线程池调度
- 阶段策略和运行态管理
- claim 与 work unit 投递
- 业务 handler 执行入口
- 轻量监控埋点

包结构：

```text
pipeline
├── facade
├── handler
├── model
├── monitor
├── scheduler
│   ├── executor
│   ├── limiter
│   ├── policy
│   └── runtime
└── stage
```

当前主链路：

```text
InfectionPipelineFacade
  -> StageDispatcher
  -> StageCoordinator
  -> WorkUnitExecutor
  -> Handler
```

核心 handler：

- `LoadEnqueueHandler`
- `LoadProcessHandler`
- `NormalizeHandler`
- `EventExtractHandler`
- `CaseRecomputeHandler`

### 4.3 `service`

职责：

- 承载业务编排、数据组装、状态更新
- 承接 Controller / Task / Handler 与 Mapper / AI 之间的桥接
- 不直接维护线程池调度状态

核心服务：

- `PatientServiceImpl`
  原始数据采集、按天聚合、`data_json/filter_data_json` 落库、增量 merge、变更任务写入
- `NormalizeStructDataService`
  结构化摘要三层调用编排
- `NormalizeContextBuilder`
  normalize 输入准备
- `PatientTimelineViewServiceImpl`
  时间线展示转换
- `InfectionEvidenceBlockServiceImpl`
  构建结构化事实、临床文本、中间语义和时间线上下文 evidence block
- `StructuredFactRefinementServiceImpl`
  结构化事实精炼入口
- `LlmEventExtractorServiceImpl`
  事件抽取用例入口
- `EventNormalizerServiceImpl`
  标准化事件归一化
- `InfectionEvidencePacketBuilderImpl`
  构建病例法官输入证据包
- `InfectionJudgeServiceImpl`
  院感法官基础节点
- `InfectionEventPoolServiceImpl`
  事件池持久化与查询
- `InfectionCaseSnapshotServiceImpl`
  病例状态快照
- `InfectionAlertResultServiceImpl`
  预警结果版本落库
- `InfectionLlmNodeRunServiceImpl`
  LLM 节点运行审计

warning 侧 helper 当前按 use case 收敛：

- `service.evidence`
  - `StructuredFactBlockBuilder`
  - `ClinicalTextBlockBuilder`
  - `MidSemanticBlockBuilder`
  - `TimelineContextBlockBuilder`
  - `StructuredFactRefinementSupport`
- `service.event`
  - `LlmEventExtractionSupport`
- `service.casejudge`
  - `InfectionCaseJudgeSupport`

### 4.4 `domain`

职责：

- 承接 DTO、Entity、Model、Enum、稳定规则和轻量领域结构
- 不作为 warning 业务 Spring 组件的默认落点

当前 `domain.normalize` 已按职责拆为：

```text
domain.normalize
├── assemble
├── facts
│   └── candidate
├── prompt
├── support
└── validation
```

当前 `domain.format` 已按职责拆为：

- `FormatContextComposer`
- `FormatSectionFormatter`
- `FinalMergePromptBuilder`
- `FilteredRawDataBuilder`

当前抽象治理原则：

- 不新增“单实现接口 + Default 实现”双层壳
- 不为了“看起来分层”新增只做命名转发的包装类
- 复杂目录不继续平铺 enum、record、helper、validator、builder、impl
- 稳定重复逻辑优先沉淀为模板父类
- 单实现主职责类直接使用业务职责命名

### 4.5 `ai`

职责：

- 负责 Agent、Prompt、模型调用和 AI 编排边界
- Prompt 输入输出尽量 JSON 化、结构化、可校验

当前重点：

- `ai.gateway.AiGateway`
  当前模型调用统一门面
- `ai.prompt.WarningPromptCatalog`
  warning 侧事件抽取和法官节点 Prompt 目录
- `ai.agent.SurveillanceAgent`
  传染病症候群监测分析

说明：

- 旧 `SummaryAgent`、`FormatAgent` 已不再作为当前结构化主链路入口
- 最终审核 Agent 只做规划，不进入正式实现

### 4.6 `mapper`

职责：

- 统一数据库读写入口
- 优先沿用 MyBatis-Plus 与既有 XML
- 保持 SQL 与实体映射清晰

核心 Mapper：

- `PatientRawDataMapper`
- `PatientRawDataCollectTaskMapper`
- `PatientRawDataChangeTaskMapper`
- `InfectionEventTaskMapper`
- `InfectionEventPoolMapper`
- `InfectionLlmNodeRunMapper`
- `InfectionCaseSnapshotMapper`
- `InfectionAlertResultMapper`
- `InfectionDailyJobLogMapper`
- `AiProcessLogMapper`
- `ItemsInforZhqMapper`

约束：

- 不在 Service 中绕过 Mapper 直接拼 JDBC
- 新增数据库访问优先扩展 Mapper / XML

### 4.7 `controller`

职责：

- 仅暴露 HTTP 接口
- 不承载复杂业务逻辑

当前重点接口：

- `GET /api/patient-summary/timeline-view?reqno={reqno}`
- `GET /api/patient-raw-data/timeline-demo?reqno={reqno}`
- `GET /api/pipeline-monitor/dashboard`

## 5. 核心运行链路

### 5.1 原始数据采集链路

触发入口：

- `InfectionMonitorScheduler.enqueuePendingPatients()`
- `InfectionMonitorScheduler.processPendingCollectTasks()`

主链路：

```text
enqueuePendingPatients()
  -> InfectionPipelineFacade.triggerLoadEnqueue()
  -> StageDispatcher
  -> LoadEnqueueCoordinator
  -> LoadEnqueueHandler
  -> patient_raw_data_collect_task

processPendingCollectTasks()
  -> InfectionPipelineFacade.triggerLoadProcess()
  -> StageDispatcher
  -> LoadProcessCoordinator
  -> LoadProcessHandler
  -> PatientServiceImpl.collectAndSaveRawDataResult(...)
  -> patient_raw_data
```

执行过程：

1. 读取上游最新 `source_batch_time`
2. 若上游批次时间未超过已入队批次，则跳过扫描
3. 正式模式按在院 / 近期出院规则分页查询患者；调试模式按 `debugReqnos` 查询
4. 将患者写入 `patient_raw_data_collect_task`
5. `LOAD_PROCESS` claim 采集任务
6. `PatientServiceImpl.collectAndSaveRawDataResult(reqno, previousSourceLastTime, sourceBatchTime)` 识别新患者、上一批次时间和本轮 `changedTypes`
7. 新患者按 `PatientCourseDataType.fullSnapshot()` 查询
8. 已有患者按 `changedTypes` 定向查询业务源表
9. 按天聚合为 `DailyPatientRawData`
10. 原始块写入 `patient_raw_data.data_json`
11. 规则处理结果写入 `patient_raw_data.filter_data_json`
12. 更新 `patient_raw_data.last_time`
13. 为变更快照写入 `patient_raw_data_change_task`
14. 命中事件来源规则时写入 `infection_event_task(EVENT_EXTRACT)`

架构特征：

- 原始采集只做事实更新，不在采集阶段直接决定摘要、预警、审核等下游业务
- 原始采集与结构化链路通过 `patient_raw_data_change_task` 解耦
- 原始采集与预警链路通过 `infection_event_task` 解耦
- 新患者限制为近期入院范围；增量患者候选必须已经存在于 `patient_raw_data`

### 5.2 结构化摘要链路

触发入口：

- `StructDataFormatScheduler.formatPendingStructData()`

主链路：

```text
formatPendingStructData()
  -> InfectionPipelineFacade.triggerNormalize()
  -> StageDispatcher
  -> NormalizeCoordinator
  -> NormalizeHandler
  -> NormalizeStructDataService
  -> NormalizeContextBuilder / NormalizeResultAssembler
  -> AiGateway
  -> patient_raw_data.struct_data_json / patient_raw_data.event_json
```

执行过程：

1. claim `patient_raw_data_change_task` 中可执行的变更任务
2. 按 `reqno` 聚合本轮 change 行
3. 对每个患者过滤 stale change，只保留 `raw_data_last_time` 仍等于 `patient_raw_data.last_time` 的记录
4. 逐条处理仍有效的单日变更行
5. 调用 `NormalizeStructDataService.compose(rawData)` 生成单日结构化结果
6. `NormalizeContextBuilder` 构造 note prompt 输入、day context 和 fusion 输入；daily fusion 输入先按完整结构生成，超过 `infection.normalize.daily-fusion.max-input-chars` 后才由 `DailyFusionInputCompactor` 分级压缩
7. `NormalizeResultAssembler` 完成模型结果校验、重试与最终 JSON 组装
8. 回写 `patient_raw_data.struct_data_json`
9. 回写 `patient_raw_data.event_json`
10. 将 change task 标记为 `SUCCESS / FAILED / SKIPPED`

架构特征：

- 摘要采用增量构建，不再从最早变更日开始整段重放
- 结构化链路完全建立在 `patient_raw_data_change_task` 上，不再依赖单纯扫描 `struct_data_json is null`
- 结构化链路与事件链路分离，结构化不阻塞预警主链路
- `event_json` 是时间线展示和最近窗口上下文的直接来源
- daily fusion 压缩只作用于模型输入，不改变 `patient_raw_data.event_json` 的输出字段约定

### 5.3 时间线展示链路

触发入口：

- `GET /api/patient-summary/timeline-view?reqno={reqno}`

主链路：

```text
PatientTimelineController
  -> PatientTimelineViewServiceImpl
  -> patient_raw_data.event_json
  -> timeline-view-rules.yaml
  -> PatientTimelineViewData
```

执行过程：

1. 分页查询某患者 `patient_raw_data.event_json`
2. 逐条解析单日摘要
3. 生成时间线节点
4. 应用 `timeline-view-rules.yaml` 配置：
   - 主问题识别
   - 风险项识别
   - 标签生成
   - 徽章生成
   - 严重级别映射
5. 输出 `PatientTimelineViewData`

架构特征：

- 展示模型与存储模型解耦
- 对前端暴露稳定视图对象，而不是原始摘要 JSON
- 标签、风险、徽章规则优先在 YAML 规则配置层扩展

### 5.4 事件抽取链路

触发入口：

- `SummaryWarningScheduler.processPendingEventTasks()`

主链路：

```text
processPendingEventTasks()
  -> InfectionPipelineFacade.triggerEventExtract()
  -> StageDispatcher
  -> EventExtractCoordinator
  -> EventExtractHandler
  -> InfectionEvidenceBlockService
  -> LlmEventExtractorServiceImpl
  -> EventNormalizerServiceImpl
  -> infection_event_pool
  -> infection_llm_node_run
```

执行过程：

1. claim `infection_event_task` 中 `task_type=EVENT_EXTRACT` 的任务
2. 按 `patient_raw_data_id + raw_data_last_time` 读取最新有效快照
3. 调用 `PatientService.buildSummaryWindowJson(reqno, dataDate)` 生成最近窗口上下文
4. 调用 `InfectionEvidenceBlockService.buildBlocks(rawData, timelineWindowJson)` 构建证据块
5. 按触发原因和 `changedTypes` 选择 primary blocks
6. 调用 `LlmEventExtractorService.extractAndSave(buildResult, primaryBlocks)`
7. `LlmEventExtractorServiceImpl` 为每个 block 创建 `infection_llm_node_run` 待处理记录
8. 通过 `AiGateway` 调用事件抽取模型
9. 调用 `EventNormalizerService` 完成事件归一化
10. 写入 `infection_event_pool`
11. 若产生新事件池结果，则 upsert `infection_event_task(CASE_RECOMPUTE)`
12. 当前任务标记为 `SUCCESS / FAILED / SKIPPED`

4 类 EvidenceBlock：

- `StructuredFactBlock`
  来自 `filter_data_json` 的结构化事实块
- `ClinicalTextBlock`
  来自病程、查房、会诊、申请等临床文本块
- `MidSemanticBlock`
  来自 `struct_data_json` 的中间层语义块
- `TimelineContextBlock`
  来自最近窗口 `event_json` 现拼时间轴背景块，仅供 LLM 参考

架构特征：

- 事件链路由 `infection_event_task` 独立承载
- 候选层不额外调用 LLM，只按变更来源路由到事件抽取
- 结构化数据是事件抽取的增强背景，不是前置阻塞条件
- 模型运行记录写入 `infection_llm_node_run`

### 5.5 病例重算与院感法官链路

触发入口：

- `SummaryWarningScheduler.processPendingCaseTasks()`

主链路：

```text
processPendingCaseTasks()
  -> InfectionPipelineFacade.triggerCaseRecompute()
  -> StageDispatcher
  -> CaseRecomputeCoordinator
  -> CaseRecomputeHandler
  -> InfectionCaseSnapshotService
  -> InfectionEvidencePacketBuilder
  -> InfectionJudgeService
  -> infection_alert_result
  -> infection_case_snapshot
```

执行过程：

1. claim `infection_event_task` 中 `task_type=CASE_RECOMPUTE` 的任务
2. 获取或初始化 `infection_case_snapshot`
3. 查询 `infection_event_pool` 当前最新 active 事件版本
4. 若无新增事件版本，则跳过本轮病例重算
5. 若仍在病例重算防抖窗口内，则延后任务
6. 调用 `InfectionEvidencePacketBuilder.build(reqno, now)` 构建硬事实证据包
7. 调用 `InfectionJudgeService.judge(packet, now)` 执行院感法官基础节点
8. `InfectionJudgeServiceImpl` 创建 `infection_llm_node_run` 待处理记录并通过 `AiGateway` 调用模型
9. `InfectionCaseJudgeSupport` 解析裁决输出、构造 fallback、执行裁决护栏
10. 写入 `infection_alert_result`
11. 更新 `infection_case_snapshot`
12. 当前任务标记为 `SUCCESS / FAILED / SKIPPED`，或延后执行

架构特征：

- 病例重算当前已经是病例级裁决入口
- 法官节点失败时使用确定性 fallback，避免整条 `CASE_RECOMPUTE` 链路失效
- 结果以版本形式写入 `infection_alert_result`
- 最新病例状态写入 `infection_case_snapshot`
- 最终审核 Agent 不在该链路中正式实现

### 5.6 传染病症候群监测链路

触发入口：

- `InfectiousSyndromeSurveillanceTask.executeClassify()`

主链路：

```text
PatillnessCourseInfo
  -> SurveillanceAgent
  -> 输出校验
  -> ai_process_log / items_infor_zhq
```

执行过程：

1. 分页查询病程信息
2. 构造监测输入文本
3. 调用 `SurveillanceAgent` 触发模型分析
4. 校验输出 JSON
5. 成功写入 `ai_process_log`
6. 对中高风险结果写入 `items_infor_zhq`

架构特征：

- 这条链路与 `patient_raw_data -> timeline` 主链路基本并行
- 更接近独立分析任务，不是当前院感预警事件池的唯一入口

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

字段用途：

- `data_json`
  每日聚合后的原始采集块，不做规则加工
- `filter_data_json`
  规则处理后的可读事实块
- `struct_data_json`
  LLM 单日结构化中间结果
- `event_json`
  单日时间轴摘要，是时间线展示和窗口上下文的直接来源
- `last_time`
  当前快照对应的业务源最后更新时间
- `is_del`
  逻辑删除标记，主链路默认只消费 `is_del = 0` 的快照

### 6.3 `patient_raw_data_collect_task`

角色：

- 原始数据采集任务表

用途：

- 解耦扫描阶段和采集执行阶段
- 保存 `reqno`、批次时间、任务状态和重试信息

### 6.4 `patient_raw_data_change_task`

角色：

- 结构化摘要链路任务表

用途：

- 驱动 `NORMALIZE`
- 保存变更快照对应的 `patient_raw_data_id`、`raw_data_last_time`、任务状态

### 6.5 `infection_event_task`

角色：

- 院感预警事件抽取和病例重算任务表

任务类型：

- `EVENT_EXTRACT`
- `CASE_RECOMPUTE`

用途：

- 解耦原始采集、事件抽取和病例裁决
- 支持病例重算防抖、跳过、失败重试

### 6.6 `infection_event_pool`

角色：

- 院感分析中枢事件池

用途：

- 保存标准化院感相关事件
- 通过 `event_key` 做幂等去重
- 为病例重算提供 active 事件版本

### 6.7 `infection_llm_node_run`

角色：

- 院感预警相关 LLM 节点运行记录

用途：

- 记录事件抽取、病例法官等节点的输入、输出、状态、置信度、耗时和错误信息

### 6.8 `infection_case_snapshot`

角色：

- 每个病例当前最新的院感状态快照

用途：

- 保存病例状态、风险等级、主要部位、当前 active evidence keys、最近裁决时间、最近结果版本和最近事件池版本

### 6.9 `infection_alert_result`

角色：

- 每次院感预警分析后的结果版本记录

用途：

- 保存病例法官裁决结果
- 保存结果 JSON 和证据包 diff JSON
- 支持后续页面联动、复核和追溯

### 6.10 `ai_process_log` 与 `items_infor_zhq`

角色：

- 传染病症候群监测链路的日志和业务结果表

用途：

- `ai_process_log` 记录症候群监测模型调用成功 / 失败
- `items_infor_zhq` 保存高风险业务结果

### 6.11 Redis

当前角色：

- Pipeline 轻量监控数据存储
- 预留业务缓存或 AI 记忆能力

说明：

- 当前监控依赖 Redis
- Redis 记忆相关能力仍属于预留或半成品，不应默认视为完整可用

## 7. 配置架构

### 7.1 主配置 `application.yaml`

负责：

- 服务端口
- 数据源
- Redis
- 模型服务
- MyBatis Mapper 路径
- Pipeline 调度策略
- 模型并发 permit
- Pipeline 监控配置

配置原则：

- 共享基线配置使用 `application.yaml`
- 本地开发使用 `application-local.yaml`
- 测试环境使用 `application-test.yaml`
- 生产环境使用 `application-prod.yaml`
- 默认 profile 为 `local`
- 真实数据库、Redis、模型服务地址和账号口令优先通过环境变量注入

常用环境变量：

- `YG_AI_DB_URL`
- `YG_AI_DB_USERNAME`
- `YG_AI_DB_PASSWORD`
- `YG_AI_REDIS_HOST`
- `YG_AI_REDIS_PORT`
- `YG_AI_OPENAI_BASE_URL`
- `YG_AI_OPENAI_API_KEY`
- `YG_AI_MONITOR_DEBUG_MODE`
- `YG_AI_MONITOR_DEBUG_REQNOS`

### 7.2 规则配置 `timeline-view-rules.yaml`

负责：

- 状态识别
- 风险识别
- 发热、手术、会诊等模式匹配
- 徽章和标签映射

扩展原则：

- 展示规则优先在 YAML 规则配置层扩展
- 只有规则无法表达时，再修改 `PatientTimelineViewServiceImpl`

### 7.3 SQL 初始化脚本

当前相关脚本位于 `src/main/resources/sql`：

- `patient_raw_data.sql`
- `patient_raw_data_collect_task.sql`
- `patient_raw_data_change_task.sql`
- `infection_event_task.sql`
- `infection_refactor_init.sql`
- `infection_case_snapshot.sql`
- `infection_alert_result.sql`
- `infection_daily_job_log.sql`
- `ai_process_log.sql`

其中 `infection_refactor_init.sql` 当前包含：

- `infection_event_pool`
- `infection_llm_node_run`

## 8. 已实现能力与预留能力

### 8.1 已实现能力

- 原始住院数据采集
- 共享线程池调度主链路
- 病程按天聚合
- AI 单日结构化摘要
- 时间线视图转换
- 传染病症候群监测 AI 辅助分析
- 标准化事件入池基础链路
- LLM 节点运行审计
- 病例状态快照
- 院感法官基础节点
- 预警结果版本落库
- Pipeline 轻量监控看板

### 8.2 预留或需谨慎判断的能力

- `WarningAgent`
- Redis 记忆相关能力
- ReactAgent 相关能力
- 最终审核 Agent

说明：

- 这些模块不要默认视为已完整可用
- 最终审核 Agent 当前只做规划，不进入正式实现

### 8.3 当前仍在演进的能力

- 增量差异识别
- 标准化事件治理与撤销 / 更正语义
- 院感法官节点输出护栏增强
- 病例重算反饥饿与防抖策略优化
- 页面联动与人工复核闭环
- 单元测试、集成测试和压测场景补齐

## 9. 抽象治理规则

新增 `interface`、`abstract class`、`*Registry`、`*Factory` 之前，必须先明确要隔离的变化点，并且满足以下条件之一：

1. 当前迭代或下一迭代内，确定会出现第二个实现。
2. 该抽象用于隔离外部边界，例如模型调用、线程调度、持久化、第三方 SDK。
3. 已经出现 3 处以上稳定重复逻辑，可以沉淀为统一模板。

如果三条都不满足，默认先使用具体类。

禁止事项：

- 禁止新增“单实现接口 + Default 实现”双层壳
- 禁止为了“看起来分层”新增只做命名转发的包装类
- 禁止在复杂目录中继续平铺 enum、record、helper、validator、builder、impl
- 禁止保留 `LegacyCompatible`、`Default` 这类过渡态命名作为长期主实现名称

当前已主动收敛的草案类型包括：

- `ModelConcurrencyLimiter`
- `ResourceProfile`
- `WorkUnitFactory`

这些不是漏实现，而是按抽象治理规则被主动收敛。若后续出现第二实现或真实变化点，再决定是否恢复独立抽象。

## 10. 当前架构风险

### 10.1 主链路与预留能力仍需明确区分

代码和文档中仍可能出现历史预留模块名称，阅读时应区分：

- 已接入当前主链路的 `pipeline`、`Normalize*`、事件抽取、病例法官节点
- 仅保留规划或预留语义的最终审核 Agent、Redis 记忆、ReactAgent 等能力

### 10.2 自动化测试不足

系统对以下内容缺少足够回归测试：

- 数据正确性
- Prompt 输出结构稳定性
- 时间线转换稳定性
- 事件标准化
- 病例重算与法官节点输入输出

后续优先补齐：

- Service 层单测
- 时间线转换测试
- JSON 解析与校验测试
- Mapper 集成测试
- 事件标准化测试
- 增量差异识别测试
- 院感预警节点输入输出测试

### 10.3 配置与环境安全

项目依赖 SQL Server、Redis 和兼容 OpenAI Chat API 的模型服务。

风险点：

- 部署环境强依赖外部资源
- 真实连接信息必须环境化
- 不应扩大 `application.yaml` 中明文敏感信息范围

### 10.4 调度策略仍需观察

当前 `StagePolicyRegistry` 已支持 `NORMALIZE` 优先窗口，但仍需继续观察：

- `CASE_RECOMPUTE` 是否出现尾部饥饿
- 模型 permit 长时间满载时的任务延迟
- `NORMALIZE` backlog 在窗口期是否能被稳定消化
- 事件抽取和病例重算在高峰期是否需要更细粒度配额

## 11. 后续演进建议

### 11.1 短期

- 补齐增量差异识别语义
- 完善 `infection_event_pool` 的撤销 / 更正 / 幂等治理
- 增强 `EventNormalizerService` 输出护栏
- 明确院感法官节点输入输出契约和错误降级边界
- 为事件抽取和病例重算补核心单测

### 11.2 中期

- 优化 `infection_case_snapshot` 的状态演进规则
- 完善事件驱动局部重算和防抖策略
- 增强 `infection_alert_result` 的解释输出
- 建立页面联动和人工复核闭环
- 补充 Pipeline 监控告警和压测场景

### 11.3 长期

- 将 Prompt、规则、校验进一步标准化
- 建立 AI 处理链路的回放能力
- 规划最终审核 Agent，但不提前进入正式实现
- 形成院感预警的完整复核和版本追溯闭环

## 12. 架构结论

`yg_ai` 当前是一个以“住院病程增量采集与 AI 单日结构化摘要”为稳定主链路、以“标准化事件池 + 病例状态快照 + 院感法官节点 + 结果版本化”为下一阶段核心演进方向的单体后端系统。

当前最重要的架构边界是：

- 调度统一走 `pipeline`
- 模型调用统一走 `AiGateway`
- 结构化结果稳定写入 `patient_raw_data.struct_data_json / event_json`
- 时间线视图作为稳定输出层，不轻易破坏 API 返回结构
- 院感预警围绕 `infection_event_pool` 和 `infection_case_snapshot` 做局部重算
- 最终审核 Agent 当前只做规划，不进入正式实现
