# yg_ai

`yg_ai` 是一个基于 `Spring Boot 3 + Java 17 + MyBatis-Plus + Spring AI` 的单体后端项目，主要用于医疗住院数据采集、病程结构化摘要、时间线展示，以及传染病症候群监测的 AI 辅助分析。

当前最完整的主链路是：

`SQL Server 原始住院数据 -> patient_raw_data -> LLM 结构化摘要 -> patient_summary -> 时间线接口 / 静态展示页`

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

## 核心模块

### 1. 患者原始数据采集

- 入口任务：`InfectionMonitorScheduler`
- 核心服务：`PatientServiceImpl`
- 编排类：`InfectionPipeline`
- 数据来源：SQL Server
- 输出表：`patient_raw_data`

职责：

- 扫描待处理患者
- 从住院业务库聚合患者多维数据
- 按天组装病程语义块
- 将原始 JSON 与临床摘要落库

### 2. 病程结构化与时间线摘要

- 入口任务：`StructDataFormatScheduler`
- AI 核心：`FormatAgent`、`SummaryAgent`
- 输出表：`patient_raw_data.struct_data_json`、`patient_summary.summary_json`

职责：

- 对病程文本做去重过滤
- 生成当天结构化事实与增量摘要
- 将摘要累积成 timeline 结构
- 为前端时间线视图提供标准化数据源

### 3. 时间线展示

- 控制器：`PatientSummaryController`、`PatientRawDataController`
- 服务：`PatientSummaryTimelineViewServiceImpl`
- 规则配置：`timeline-view-rules.yaml`

职责：

- 读取最新摘要
- 转换为前端可渲染的 `PatientTimelineViewData`
- 结合配置规则生成风险项、徽章、标签和展示字段

### 4. 症候群监测

- 核心类：`SurveillanceAgent`
- 数据来源：病程信息查询
- 输出：`ai_process_log`、`items_infor_zhq`

职责：

- 对病程内容做传染病症候群识别
- 校验模型输出 JSON
- 记录成功/失败日志
- 写入高风险业务结果

说明：

- 当前调度入口 `InfectiousSyndromeSurveillanceTask` 的 `@Scheduled` 默认未启用。

## 启动前准备

项目依赖以下外部资源：

- SQL Server 数据库
- Redis
- 兼容 OpenAI Chat API 的模型服务

当前配置集中在 `src/main/resources/application.yaml`，至少需要确认：

- `spring.datasource.*`
- `spring.data.redis.*`
- `spring.ai.openai.*`

建议本地开发时拆分为环境专用配置，不要直接在共享配置中保留真实账号口令。

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

默认端口见 `application.yaml`，当前配置为 `8090`。

## 主要接口

### 1. 摘要时间线接口

```http
GET /api/patient-summary/timeline-view?reqno={reqno}
```

用途：

- 获取某患者最新 AI 摘要转换后的时间线视图数据

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
  预留，当前未实现

## 当前实现状态

已成型：

- 患者原始数据采集
- 病程按天聚合与落库
- AI 结构化摘要生成
- 时间线视图转换
- 静态演示页面

仍在演进：

- 告警能力
- 审计能力
- Redis 记忆能力
- 症候群监测自动调度
- 依赖清理与测试补齐

## 风险与注意事项

- `selectActiveReqnos` 当前使用硬编码患者白名单，适合开发调试，不适合生产。
- `application.yaml` 中存在强环境依赖，部署前必须重构配置管理。
- 项目测试覆盖很弱，当前主要依赖人工联调。
- 代码中保留了部分预留模块，阅读时应区分“已上线主链路”和“待实现扩展”。

## 文档

- 项目结构分析：[docs/project-structure-analysis.md](docs/project-structure-analysis.md)
- 架构设计：[docs/architecture.md](docs/architecture.md)
- Codex 协作规范：[AGENT.md](AGENT.md)
