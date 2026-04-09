# yg_ai

`yg_ai` 是一个基于 `Spring Boot 3 + Java 17 + MyBatis-Plus + Spring AI` 的单体后端项目，主要用于医疗住院数据采集、病程结构化摘要、时间线展示，以及传染病症候群监测的 AI 辅助分析。

当前已经完成的任务包括：

- 医疗住院数据采集
- 病程结构化摘要
- 时间线展示
- 传染病症候群监测的 AI 辅助分析

当前最完整的主链路是：

`SQL Server 原始住院数据 -> patient_raw_data -> LLM 单日结构化摘要 -> patient_raw_data.struct_data_json / event_json -> 时间线接口 / 静态展示页`

当前调度执行架构已经统一为：

`@Scheduled -> InfectionPipelineFacade(具体类) -> pipeline.scheduler -> pipeline.stage -> pipeline.handler`

系统的数据来源模式是：

- 每日定时拉取
- 增量接入
- 不采用每日全量重跑整个历史病例

## 技术栈

- Java 17
- Spring Boot 3.5.7
- MyBatis-Plus 3.5.15
- Spring AI 1.1.3
- Spring AI Alibaba 1.1.2.0
- SQL Server
- Redis
- Stanford CoreNLP

## 项目结构

```text
src/main/java/com/zzhy/yg_ai
├── ai          AI Agent、Prompt、编排、校验、工具
├── common      通用工具
├── config      Spring / MyBatis / 规则配置
├── controller  HTTP 接口
├── domain      DTO / Entity / Model / Enum
├── mapper      MyBatis Mapper 接口
├── pipeline    共享线程池调度、stage 编排、handler 执行链、轻量监控埋点
├── service     业务服务接口与实现
├── task        定时任务
└── YgAiApplication.java
```

资源目录：

```text
src/main/resources
├── application.yaml
├── mapper
├── sql
├── static
└── timeline-view-rules.yaml
```

其中 `src/main/resources/sql/patient_raw_data.sql` 用于补齐 `patient_raw_data.is_del` 逻辑删除字段，默认值为 `0`。

## 架构与抽象治理

当前代码约束已经明确为：

- 调度主链路统一收敛在 `pipeline`，不再回到“大而全”的 orchestrator
- `pipeline.scheduler`、`domain.normalize`、`domain.format` 等复杂目录必须按职责拆子包，避免 enum、record、helper、impl 平铺混放
- warning 侧业务 helper 按 use case 收敛到 `service.evidence`、`service.event`、`service.casejudge` 等目录，不继续塞进 `domain`
- `domain` 优先承接稳定规则和数据结构，不继续作为 warning 业务 Spring 组件的平铺容器
- 默认优先使用具体类，不新增“单实现接口 + Default 实现”双层壳
- 只有满足以下条件之一，才允许新增 `interface`、`abstract class`、`*Registry`、`*Factory`
  - 当前迭代或下一迭代内确定会出现第二实现
  - 用于隔离模型调用、线程调度、持久化、第三方 SDK 等外部边界
  - 已经出现 3 处以上稳定重复逻辑，可以沉淀为统一模板
- 如果是结构性重构，必须同步更新 `docs/shared-executor-architecture-design.md`、`docs/refactor-handover-status.md`、`README.md`、`AGENTS.md`

当前更详细的约束说明见：

- `docs/shared-executor-architecture-design.md`
- `docs/refactor-handover-status.md`

## 核心模块

### 1. 患者原始数据采集

- 入口任务：`InfectionMonitorScheduler`
- 核心服务：`PatientServiceImpl`
- 调度主链路：`InfectionPipelineFacade -> StageDispatcher -> LoadEnqueueCoordinator / LoadProcessCoordinator`
- 数据来源：SQL Server
- 输出表：`patient_raw_data`
- 任务表：`patient_raw_data_collect_task`
- 运维日志表：`infection_daily_job_log`

职责：

- 扫描待处理患者
- 从住院业务库聚合患者多维数据
- 按天组装病程语义块
- 将原始 JSON 与临床摘要落库
- 默认只读取 `is_del = 0` 的有效快照，逻辑删除记录不参与后续链路

执行方式：

- 扫描阶段先生成 `patient_raw_data_collect_task`
- 调度器再领取待处理采集任务并发执行
- 任务结果按 `PENDING / RUNNING / SUCCESS / FAILED` 流转
- 批次级执行情况写入 `infection_daily_job_log`

模式说明：

- 该链路的数据输入是按日定时增量拉取
- 后续分析应优先识别快照差异，而不是对全历史重复重算

### 2. 病程结构化与时间线摘要

- 入口任务：`StructDataFormatScheduler`
- 调度主链路：`InfectionPipelineFacade -> NormalizeCoordinator -> NormalizeHandler -> NormalizeRowProcessor -> NormalizeStructDataComposer`
- Normalize 核心目录：`domain.normalize.assemble / facts / prompt / support / validation`
- Format 相关目录：`domain.format`
- 输出表：`patient_raw_data.struct_data_json`、`patient_raw_data.event_json`
- 任务表：`patient_raw_data_change_task`
- 运维日志表：`infection_daily_job_log`

职责：

- 对病程文本做去重过滤
- 生成当天结构化事实与单日摘要
- 将单日摘要写入 `event_json`
- 为前端时间线视图和事件抽取窗口提供标准化数据源
- `NormalizeRowProcessor` 只负责 reset / 回写 / 错误包装
- `NormalizeStructDataComposer` 负责输入选择、note 结构化、fusion 调用和 JSON 组装
- `FormatAgent` 已收薄为兼容入口，格式化编排下沉到 `domain.format.*`

执行方式：

- 原始数据采集完成后，按日写入 `patient_raw_data_change_task`
- 定时任务优先领取待处理任务，再执行单日结构化和摘要更新
- 不再依赖单纯扫描 `struct_data_json is null` 作为唯一处理驱动
- 结构化调度前会巡检并补建缺失的 change task

### 3. 时间线展示

- 控制器：`PatientTimelineController`、`PatientRawDataController`
- 服务：`PatientTimelineViewServiceImpl`
- 规则配置：`timeline-view-rules.yaml`

职责：

- 直接分页读取 `patient_raw_data.event_json`
- 转换为前端可渲染的 `PatientTimelineViewData`
- 结合配置规则生成风险项、徽章、标签和展示字段

### 4. 症候群监测

- 核心类：`SurveillanceAgent`
- 共享链路中的相关能力：`EventExtractHandler`、`CaseRecomputeHandler`、`WarningModelGateway`、`WarningPromptCatalog`
- 结构化事实精炼相关目录：`service.evidence`
- 数据来源：病程信息查询
- 输出：`ai_process_log`、`items_infor_zhq`

职责：

- 对病程内容做传染病症候群识别
- 校验模型输出 JSON
- 记录成功/失败日志
- 写入高风险业务结果
- 结构化事实精炼已收敛为 `StructuredFactRefinementServiceImpl + service.evidence.StructuredFactRefinementSupport`

说明：

- 当前调度入口 `InfectiousSyndromeSurveillanceTask` 的 `@Scheduled` 默认未启用。

### 5. Pipeline 轻量监控看板

- 接口：`/api/pipeline-monitor/dashboard`
- 页面：`/pipeline_monitor_dashboard.html`
- 监控数据只写 Redis，不新增数据库监控表
- 监控切面只放在 `WorkUnitExecutor`、`ModelCallGuard`、`AbstractTaskHandler`、`LoadEnqueueHandler`
- `pipeline.monitor` 负责 Redis 聚合、backlog 快照和看板 DTO，不进入业务主编排

监控范围：

- 共享线程池总线程 / 活跃线程 / 空闲线程 / 队列长度
- 当前活跃 `work unit`
- `LOAD_PROCESS / NORMALIZE / EVENT_EXTRACT / CASE_RECOMPUTE` backlog
- 近 `3h / 12h / 24h` 的任务处理量和 LLM 调用量
- 各类 LLM 调用平均耗时、最大耗时、慢调用占比

## 启动前准备

项目依赖以下外部资源：

- SQL Server 数据库
- Redis
- 兼容 OpenAI Chat API 的模型服务

当前配置集中在 `src/main/resources/application.yaml`，至少需要确认：

- `spring.datasource.*`
- `spring.data.redis.*`
- `spring.ai.openai.*`
- `infection.monitor.*`
- `infection.monitor.dashboard.*`

当前共享配置已经改为环境变量优先，不再直接存放真实账号口令。

推荐做法：

- 共享基线配置使用 `application.yaml`
- 本地开发使用 `application-local.yaml`
- 测试环境使用 `application-test.yaml`
- 生产环境使用 `application-prod.yaml`
- 默认 profile 为 `local`
- 通过 `SPRING_PROFILES_ACTIVE=local` 启动本地覆盖配置
- 通过环境变量注入真实连接信息

Profile 切换示例：

- 本地：`SPRING_PROFILES_ACTIVE=local`
- 测试：`SPRING_PROFILES_ACTIVE=test`
- 生产：`SPRING_PROFILES_ACTIVE=prod`

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

## 本地启动

### 1. 使用 Maven Wrapper

```bash
./mvnw spring-boot:run
```

### 2. 打包运行

```bash
./mvnw clean package
java -jar target/yg_ai-0.0.1-SNAPSHOT.jar
```

默认端口见 `application.yaml`，当前配置为 `8090`。如果不显式指定 `SPRING_PROFILES_ACTIVE`，默认使用 `local`。

## 主要接口

### 1. 摘要时间线接口

```http
GET /api/patient-summary/timeline-view?reqno={reqno}
```

用途：

- 获取某患者基于 `patient_raw_data.event_json` 转换后的时间线视图数据

### 2. 原始病程演示接口

```http
GET /api/patient-raw-data/timeline-demo?reqno={reqno}
```

用途：

- 获取患者原始病程按时间聚合后的演示数据

## 定时任务

当前代码中存在以下任务：

- `InfectionMonitorScheduler`
  采集患者原始数据
- `StructDataFormatScheduler`
  处理待结构化病程数据
- `InfectiousSyndromeSurveillanceTask`
  症候群监测，默认未开启自动调度
- `SummaryWarningScheduler`
 预留，当前不注册调度逻辑

## 当前实现状态

已成型：

- 患者原始数据采集
- 共享线程池调度主链路
- 病程按天聚合与落库
- AI 结构化摘要生成
- 时间线视图转换
- 传染病症候群监测 AI 辅助分析
- 静态演示页面

仍在演进：

- 院感预警分析
- 标准化事件入池
- 病例状态快照
- 事件驱动局部重算
- 结果版本化
- 页面联动与人工复核闭环
- 依赖清理与测试补齐

主链路边界说明：

- `InfectionPipelineFacade + pipeline.*` 当前承接患者采集、结构化摘要、事件提取和病例重算主流程
- 事件抽取窗口上下文通过 `patient_raw_data.event_json` 按需构造
- `SummaryWarningScheduler` 仍为预留触发入口，旧 `WarningAgent` / `SummaryAgent` 已退出主链
- 患者扫描默认按“在院 / 近期出院”规则查询，可通过 `infection.monitor.debug-mode=true` 和 `infection.monitor.debug-reqnos` 切到调试名单

规划中但当前不开发：

- 最终审核 Agent

## 下一阶段目标

下一阶段主任务是“院感预警分析”，其核心目标不是做一次性全量分析，而是建设：

> 基于每日增量数据持续更新的院感监测系统

下一阶段的目标对象包括：

- `infection_event_pool`
- `infection_llm_node_run`
- `infection_case_snapshot`
- `infection_alert_result`

推荐推进顺序：

### Phase 1

- 标准化事件入池
- `infection_event_pool`
- `infection_llm_node_run`

### Phase 2

- 病例状态快照
- 事件驱动局部重算
- 院感法官基础节点

### Phase 3

- 部位级评分
- 总体预警建议
- 结果版本化
- 页面联动
- 人工复核闭环

### 规划项

- 最终审核 Agent
  当前只做规划，不进入开发实现

## 风险与注意事项

- `selectActiveReqnos` 当前使用硬编码患者白名单，适合开发调试，不适合生产。
- `application.yaml` 中存在强环境依赖，部署前必须重构配置管理。
- 项目测试覆盖很弱，当前主要依赖人工联调。
- 代码中保留了部分预留模块，阅读时应区分“已上线主链路”和“待实现扩展”。
- 后续院感预警分析必须坚持增量模式，不能退化为每日全量重跑方案。

## 文档

- 项目结构分析：[docs/project-structure-analysis.md](docs/project-structure-analysis.md)
- 架构设计：[docs/architecture.md](docs/architecture.md)
- 院感预警实施：[docs/infection-warning-design.md](docs/infection-warning-design.md)
- 数据模型设计：[docs/data-model-design.md](docs/data-model-design.md)
- Codex 协作规范：[AGENT.md](AGENT.md)
