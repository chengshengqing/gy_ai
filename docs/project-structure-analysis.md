# yg_ai 项目结构与依赖分析

## 1. 项目定位

`yg_ai` 是一个基于 `Spring Boot 3 + Java 17 + MyBatis-Plus + Spring AI` 的单体后端项目，主要面向医疗住院数据处理与 AI 辅助分析。

当前代码可以归纳为两条主要业务链：

1. 院感时间线链路
   从 SQL Server 采集患者住院病程等原始数据，按天聚合后写入本地结构化表，再通过大模型生成增量摘要，最终提供时间线展示接口和静态演示页面。
2. 症候群监测链路
   对病程内容做传染病症候群分析，通过大模型输出 JSON 结果，再经过校验、记录日志，并将高风险结果写入业务表。

项目当前已经完成的任务包括：

1. 医疗住院数据采集
2. 病程结构化摘要
3. 时间线展示
4. 传染病症候群监测的 AI 辅助分析

项目当前更完整、可运行的主链路是第一条，即“患者原始数据采集 -> 结构化摘要 -> 时间线展示”。

当前实现口径：

- 时间线展示与事件窗口上下文统一来自 `patient_raw_data.event_json`
- 展示层类名统一使用 `PatientTimeline*`

系统的数据输入模式是：

- 每日定时拉取
- 增量接入
- 不采用全量历史重跑

同时，下一阶段将进入“院感预警分析”主任务；最终审核 Agent 当前只做规划，不在当前阶段开发。

## 2. 技术栈

### 2.1 核心框架

- `Spring Boot 3.5.7`
- `Java 17`
- `MyBatis-Plus 3.5.15`
- `Spring AI 1.1.3`
- `Spring AI Alibaba 1.1.2.0`

### 2.2 数据与中间件

- `SQL Server`
- `Redis`
- `HikariCP`

### 2.3 其他依赖

- `Stanford CoreNLP`
- `Lombok`
- `Spring Boot Test`

## 3. 目录结构

项目是标准 Maven 单模块结构：

```text
yg_ai/
├── pom.xml
├── AGENT.md
├── src/
│   ├── main/
│   │   ├── java/com/zzhy/yg_ai/
│   │   │   ├── ai
│   │   │   ├── common
│   │   │   ├── config
│   │   │   ├── controller
│   │   │   ├── domain
│   │   │   ├── mapper
│   │   │   ├── service
│   │   │   ├── task
│   │   │   └── YgAiApplication.java
│   │   └── resources/
│   │       ├── application.yaml
│   │       ├── mapper/
│   │       ├── sql/
│   │       ├── static/
│   │       └── timeline-view-rules.yaml
│   └── test/
│       └── java/com/zzhy/yg_ai/YgAiApplicationTests.java
└── target/
```

## 4. 包结构说明

### 4.1 `ai`

该目录是 AI 相关实现的核心区域，共包含 16 个文件，分为以下子模块：

#### `ai/agent`

负责与模型交互的 Agent 层：

- `AbstractAgent`
  封装统一的 `ChatModel` 调用能力。
- `FormatAgent`
  当前主要负责面向 LLM 的格式化、规整和分段处理；原始采集阶段的规则过滤已下沉到 `PatientServiceImpl`。
- `SummaryAgent`
  负责将某一天的病程内容提炼成结构化摘要，并追加到时间线。
- `SurveillanceAgent`
  负责传染病症候群监测。
- `WarningAgent`
  目前为占位类，尚未实现核心逻辑。
- `AuditAgent`
  目前为占位类，尚未实现核心逻辑。
- `AgentUtils`
  提供 JSON 解析、文本拆分、模型输入处理等工具方法。

#### `ai/orchestrator`

- `InfectionPipeline`
  当前最关键的业务编排类，负责：
  `patient_raw_data_collect_task` 任务消费、
  `patient_raw_data_change_task` 任务消费、
  `PatientService -> SummaryAgent -> 单日 struct/event 持久化`

#### `ai/prompt`

存放 Prompt 模板：

- `FormatAgentPrompt`
- `SummaryAgentPrompt`
- `WarningAgentPrompt`
- `PromptTemplateManager`

其中真正被主链路直接使用的是 `FormatAgentPrompt` 和 `PromptTemplateManager`。

#### `ai/tools`

- `LoadDataTool`
  作为预留的 `@Tool` 适配层保留，当前不再承担主采集链路职责。

#### `ai/validator`

- `SurveillanceResponseValidator`
- `SurveillanceValidationEnums`

用于校验症候群监测模型输出是否合法。

### 4.2 `common`

通用工具类：

- `FilterTextUtils`
  使用 `Stanford CoreNLP` 对病程文本分句并做相似内容过滤。
- `JsonUtil`
  JSON 处理工具。

### 4.3 `config`

项目配置层：

- `MybatisPlusConfig`
  配置 MyBatis-Plus 分页插件和 SQL Server 方言。
- `ScheduleConfig`
  目前基本为空实现，仅保留调度扩展入口。
- `TimelineViewRuleProperties`
  加载时间线渲染规则配置。
- `YamlPropertySourceFactory`
  支持读取 YAML 作为属性源。

### 4.4 `controller`

当前只有两个对外接口：

- `PatientRawDataController`
  提供原始病程时间线演示接口。
- `PatientTimelineController`
  提供时间线视图接口。

整体上控制器层很薄，业务逻辑主要集中在 Service 与 AI 层。

### 4.5 `domain`

领域模型层，共 16 个文件：

- `base`
  通用基础对象，如 `BaseDTO`、`BaseEntity`、`BaseResult`。
- `dto`
  面向接口或查询结果的数据结构，如：
  `PatientRawDataTimelineGroup`、`PatientTimelineViewData`、`PatillnessCourseInfo`
- `entity`
  面向数据库表的数据实体，如：
  `PatientRawDataEntity`、`AiProcessLog`、`ItemsInforZhq`
- `enums`
  枚举定义，如 `IllnessRecordType`
- `model`
  用于 Agent 间传递或临时建模的对象，如 `PatientContext`

### 4.6 `mapper`

数据访问层，共 5 个 Mapper 接口：

- `PatientRawDataMapper`
- `PatillnessCourseInfoMapper`
- `ItemsInforZhqMapper`
- `AiProcessLogMapper`

其中 `PatientRawDataMapper` 是最复杂的 Mapper，承担住院患者原始数据聚合查询的主体工作。

### 4.7 `service`

业务服务层，共 10 个文件：

- 接口层
  - `PatientService`
  - `PatientTimelineViewService`
  - `AiProcessLogService`
  - `IItemsInforZhqService`
  - `IPatillnessCourseInfoService`
- 实现层
  - `PatientServiceImpl`
  - `PatientTimelineViewServiceImpl`
  - `AiProcessLogServiceImpl`
  - `ItemsInforZhqServiceImpl`
  - `PatillnessCourseInfoServiceImpl`

这里的核心是：

- `PatientServiceImpl`
  负责从 SQL Server 查询患者数据、按天组装、写入 `patient_raw_data`、维护 `data_json/filter_data_json`、执行增量 merge、写入原始数据变更任务。
- `PatientTimelineViewServiceImpl`
  负责把 `event_json` 转成前端可直接渲染的 timeline 数据。

### 4.8 `task`

定时任务层，共 4 个类：

- `InfectionMonitorScheduler`
  已拆分为“扫描入队”和“执行采集任务”两段。
- `StructDataFormatScheduler`
  定时处理待结构化的病程数据。
- `SummaryWarningScheduler`
  当前为空壳，未实现。
- `InfectiousSyndromeSurveillanceTask`
  症候群监测任务，但调度注解当前被注释，默认不会自动执行。

## 5. 主要运行链路

### 5.1 患者原始数据采集链路

入口：

- `InfectionMonitorScheduler.enqueuePendingPatients()`
- `InfectionMonitorScheduler.processPendingCollectTasks()`

处理过程：

1. 通过 `PatientService.listActiveReqnos()` 获取患者列表
2. 将 `reqno` 写入 `patient_raw_data_collect_task`
3. `InfectionPipeline.processPendingRawDataTasks()` claim 原始采集任务
4. `PatientServiceImpl.collectAndSaveRawDataResult(reqno)` 识别新患者、增量患者和 `changedTypes`
5. 按查询类型读取业务源表并按天聚合
6. 原始块写入 `patient_raw_data.data_json`
7. 规则处理结果写入 `patient_raw_data.filter_data_json`
8. 更新 `patient_raw_data.last_time`
9. 将变更行写入 `patient_raw_data_change_task`

说明：

- 新患者限制为最近 30 天入院。
- 增量患者候选识别依赖各业务表 `last_time`，且要求 `patient_raw_data` 中已存在对应 `reqno`。
- 原始采集与下游摘要链已经完成解耦。

### 5.2 结构化摘要链路

入口：

- `StructDataFormatScheduler.formatPendingStructData()`

处理过程：

1. `InfectionPipeline.processPendingStructData()` claim `patient_raw_data_change_task`
2. claim 结果按 `reqno` 聚合，形成患者级本轮消费集合
3. 对每个患者校验变更行版本，只保留 `raw_data_last_time == patient_raw_data.last_time` 的有效 change
4. 对每条有效 change 仅处理对应单日 `patient_raw_data`
5. 调用 `resetDerivedDataForRawData(rawDataId)` 清空当日派生字段
6. 用 `SummaryAgent.extractDailyIllness()` 生成当天结构化结果与单日摘要
7. 将结构化内容写回 `patient_raw_data.struct_data_json`
8. 将单日摘要写回 `patient_raw_data.event_json`
9. 本轮患者级 change 行统一标记为 `SUCCESS` 或 `FAILED`

说明：

- `pat_illnessCourse` 的过滤规则已经前移到原始采集落库阶段
- 当前摘要链已经切换为消费 `patient_raw_data_change_task`

### 5.3 时间线展示链路

入口接口：

- `/api/patient-raw-data/timeline-demo`
- `/api/patient-summary/timeline-view`

处理过程：

1. 分页查询 `patient_raw_data.event_json`
2. 读取单日摘要
3. 根据 `timeline-view-rules.yaml` 中的规则进行分类、标签、风险、徽章生成
4. 输出 `PatientTimelineViewData`

### 5.4 症候群监测链路

核心类：

- `SurveillanceAgent`

处理过程：

1. 分页查询患者病程信息
2. 构造监测输入内容
3. 调用模型输出 JSON
4. 使用 `SurveillanceResponseValidator` 校验
5. 成功则写 `ai_process_log`
6. 若风险较高，则写 `items_infor_zhq`

说明：

- 该链路逻辑相对完整，但默认没有开启自动定时执行。

## 6. 资源文件说明

### 6.1 `application.yaml`

包含以下核心配置：

- 服务端口
- SQL Server 数据源
- Redis 配置
- Spring AI 模型服务地址
- MyBatis Mapper 路径
- 调度线程池参数

### 6.2 `resources/mapper`

包含 3 个主用 MyBatis XML：

- `PatientRawDataMapper.xml`
- `PatillnessCourseInfoMapper.xml`
- `ItemsInforZhqMapper.xml`

其中 `PatientRawDataMapper.xml` 是最重要的数据库查询映射文件。

### 6.3 `resources/static`

包含 3 个静态 HTML 页面：

- `html4.html`
- `patient_raw_data_timeline.html`
- `patient_summary_timeline_view.html`

说明项目内置了简易演示页面，而不只是纯后端接口。

### 6.4 `timeline-view-rules.yaml`

用于定义时间线展示规则，包括：

- 主问题状态
- 关键日徽章
- 风险匹配规则
- 发热、手术、会诊等模式匹配
- 标签映射

## 7. 外部依赖使用情况分析

### 7.1 已明确使用的依赖

- `spring-boot-starter-web`
  用于 REST 接口。
- `mybatis-plus-spring-boot3-starter`
  用于 ORM 与分页查询。
- `spring-boot-starter-jdbc`
  用于数据源与 JDBC。
- `mssql-jdbc`
  连接 SQL Server。
- `spring-ai-starter-model-openai`
  提供统一 `ChatModel` 接口。
- `stanford-corenlp`
  用于病程文本分句过滤。
- `spring-boot-starter-test`
  用于基础启动测试。

### 7.2 已声明但当前使用较弱或未真正使用的依赖

- `spring-boot-starter-webflux`
  未发现 `WebClient`、`Mono`、`Flux` 等实际用法。
- `spring-ai-alibaba-agent-framework`
  仅看到 `ReactAgent` import，未看到实际集成使用。
- `spring-ai-alibaba-starter-memory-redis`
  配置存在，但未看到明显使用链路。
- `spring-boot-starter-data-redis`
  注入了 `StringRedisTemplate`，但当前主流程中未见实际调用。
- `redisson-spring-boot-starter`
  未发现显式使用。

## 8. 当前代码成熟度判断

### 8.1 已成型部分

- SQL Server 数据采集与按天聚合
- 患者原始数据落库
- LLM 摘要增量生成
- 时间线 JSON 转前端视图
- 基础静态演示页

### 8.2 半成品或占位部分

- `WarningAgent`
- `AuditAgent`
- `SummaryWarningScheduler`
- `InfectiousSyndromeSurveillanceTask` 的自动调度
- Redis / Redisson / ReactAgent 相关扩展能力

## 9. 当前项目中的关键问题与风险

### 9.1 配置安全风险

`application.yaml` 中直接写入了数据库地址、账号、密码以及模型服务地址。后续如果要沉淀成团队规范，应拆到：

- 环境变量
- `application-local.yaml`
- 配置中心

### 9.2 患者扫描范围硬编码

`PatientRawDataMapper.xml` 中 `selectActiveReqnos` 现在是固定患者清单，只适合开发或演示环境。

### 9.3 调度与主链路不完全一致

项目中已经注入了 `WarningAgent`、`AuditAgent`、Redis，但主链路实际上并未用到这些模块，说明设计目标比当前交付状态更大。

### 9.4 测试覆盖很弱

当前只有一个启动测试，没有：

- Service 层单测
- Mapper 层集成测试
- Prompt / Agent 输出校验测试
- 时间线转换测试

## 10. 下一阶段架构演进方向

下一阶段将进入院感预警分析，建议新增并围绕以下对象设计：

- `infection_event_pool`
- `infection_llm_node_run`
- `infection_case_snapshot`
- `infection_alert_result`

建议主流程：

1. 对每日增量快照做差异识别
2. 切分 EvidenceBlock
3. 使用统一 LLM 事件抽取器生成标准化事件
4. 事件规范化后写入事件池
5. 维护病例状态快照
6. 触发院感法官节点做局部重算
7. 产出结果版本和医生可读解释

当前阶段不进入开发实现的内容：

- 最终审核 Agent

## 11. 后续文档拆分建议

你下一步如果要面向 Codex 或团队继续建设文档，建议从这份分析文档拆成以下几类。

### 10.1 `README.md`

适合放：

- 项目简介
- 技术栈
- 目录结构简述
- 本地启动方式
- 配置说明
- 主要接口
- 主要任务调度说明

### 10.2 `AGENT.md`

适合放：

- Codex 在本项目中的角色设定
- 技术栈约束
- 编码规范
- 项目模块边界
- 禁止随意改动的部分
- 开发优先级与架构原则

例如应明确：

- 这是 `Java 17 + Spring Boot 3 + MyBatis-Plus + Spring AI` 项目
- 优先复用现有 `service / mapper / ai / task` 分层
- 新能力优先沿 `InfectionPipeline` 或 `SurveillanceAgent` 现有链路扩展
- 避免绕过 Mapper 直接拼 JDBC

### 10.3 设计文档

建议拆成多个专题文档，而不是一份大而全：

- `docs/architecture.md`
  讲整体架构、模块关系、运行链路
- `docs/ai-pipeline-design.md`
  讲 Agent、Prompt、结构化摘要流程
- `docs/data-model-design.md`
  讲核心表、实体、DTO、Mapper 关系
- `docs/scheduler-design.md`
  讲定时任务职责与调度策略
- `docs/timeline-view-design.md`
  讲时间线视图转换规则与配置

## 12. 建议作为后续文档母版的信息

如果后面要让 Codex 持续参与开发，建议优先把以下信息单独沉淀出来：

1. 项目目标边界
   当前项目到底是“住院病程摘要系统”还是“院感监测平台”，需要明确主线。
2. 数据库表说明
   尤其是：
   `patient_raw_data`、`ai_process_log`、`items_infor_zhq`
3. 关键调用链
   从定时任务到 Service、Mapper、Agent 的标准入口。
4. Prompt 管理原则
   哪些 Prompt 已上线，哪些仍在实验阶段。
5. 依赖收敛策略
   当前未使用的依赖是否保留，是否后续会启用。

## 13. 一句话总结

`yg_ai` 当前是一个以住院患者病程增量采集、AI 摘要生成、时间线展示和症候群监测为已完成能力的 Spring Boot 单体项目，下一阶段将围绕院感预警分析展开，而最终审核 Agent 当前只作为规划项存在。
