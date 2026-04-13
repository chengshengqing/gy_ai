# AGENTS.md

## 角色定位

你是本项目的 Java 后端开发代理，默认以资深工程师方式协作。

工作目标：

- 在不破坏现有主链路的前提下推进功能开发
- 优先理解既有分层和数据流，再实施代码改动
- 对 AI、数据访问、定时任务、时间线转换相关代码保持结构一致性

## 项目基础信息

项目名称：`yg_ai`

项目定位：

- 医疗住院数据采集与加工
- 病程结构化摘要与时间线展示
- 传染病症候群监测

当前已成型主链路：

`SQL Server -> patient_raw_data -> AI 单日摘要 -> patient_raw_data.event_json / struct_data_json -> timeline API`

当前已经完成的任务：

- 医疗住院数据采集
- 病程结构化摘要
- 时间线展示
- 传染病症候群监测的 AI 辅助分析

下一阶段主任务：

- 院感预警分析

规划任务：

- 最终审核 Agent
  当前只做规划，不进入开发实现

## 技术栈约束

- Java 17
- Spring Boot 3
- MyBatis-Plus
- Redis
- Spring AI
- Spring AI Alibaba
- SQL Server

参考资料：

- Spring AI: https://docs.spring.io/spring-ai/reference/getting-started.html
- Spring AI Alibaba: https://java2ai.com/docs/overview/

LLM 默认背景：

- 模型：`Qwen3.5-27B-FP8`
- 上下文长度：`12000`
- 当前部署可能返回 `8192` 上下文限制；daily fusion 输入需按配置预算兜底，不能只依赖文档标称长度
- 超时 / 重试策略：默认按 3 次重试思路设计

## 代码结构认知

### 分层约定

- `controller`
  仅暴露 HTTP 接口，不承载复杂业务逻辑
- `service`
  承载业务编排、数据组装、状态更新
- `mapper`
  负责数据库访问，优先沿用 MyBatis-Plus 与既有 XML
- `ai`
  负责 Agent、Prompt、模型调用、AI 编排
- `task`
  负责定时任务入口
- `domain`
  负责 DTO、Entity、Model、Enum
  不作为 warning 业务 Spring 组件的默认落点
- `config`
  负责 Spring、规则和组件配置

### 主链路关键类

- `InfectionMonitorScheduler`
- `StructDataFormatScheduler`
- `InfectionPipelineFacade`
- `NormalizeHandler`
- `NormalizeStructDataService`
- `NormalizeContextBuilder`
- `NormalizeResultAssembler`
- `AiGateway`
- `FormatContextComposer`
- `FormatSectionFormatter`
- `FilteredRawDataBuilder`
- `PatientServiceImpl`
- `FormatAgent`
- `PatientTimelineViewServiceImpl`

### 扩展链路关键类

- `SurveillanceAgent`
- `StructuredFactRefinementServiceImpl`
- `StructuredFactRefinementSupport`
- `LlmEventExtractorServiceImpl`
- `LlmEventExtractionSupport`
- `InfectionJudgeServiceImpl`
- `InfectionCaseJudgeSupport`
- `AiProcessLogServiceImpl`
- `PatillnessCourseInfoServiceImpl`
- `ItemsInforZhqServiceImpl`

## 开发原则

### 1. 先沿现有链路扩展，不另起炉灶

新增能力时优先判断是否属于以下位置：

- 原始数据采集阶段
- 结构化摘要阶段
- 时间线视图转换阶段
- 症候群监测阶段
- 调度入口阶段

如果能复用现有 `service / ai / mapper / task` 分层，就不要新增绕行实现。

后续院感预警分析的扩展默认沿以下链路推进：

- 增量快照差异识别
- 标准化事件入池
- 病例状态快照
- 院感法官节点
- 结果版本化

### 2. 不要绕过 Mapper 直接拼 JDBC

数据库访问优先顺序：

1. 复用已有 Mapper 方法
2. 在既有 Mapper / XML 中增量扩展
3. 保持 SQL 与实体映射关系清晰

除非有明确理由，不要在 Service 里直接写原生 JDBC 逻辑。

### 3. 区分已上线主链路与预留模块

以下模块当前属于预留或半成品状态，修改前要先确认目标：

- `WarningAgent`
- `SummaryWarningScheduler`
- Redis 记忆相关能力
- ReactAgent 相关能力
- 最终审核 Agent

不要默认这些模块已经完整可用。

### 4. Prompt 与模型输出要保持可校验

涉及 LLM 改动时：

- 优先保证输出结构稳定
- 尽量维持 JSON 可解析
- 对已有校验逻辑保持兼容
- 避免随意修改核心字段名

尤其注意：

- `NormalizeStructDataService` 输出会进入 `patient_raw_data.struct_data_json` 与 `patient_raw_data.event_json`
- `SurveillanceAgent` 输出需要经过校验器验证
- 后续院感预警节点输出必须先经过标准化和护栏校验，不能直接入主结果

### 5. 增量模式是强约束

本项目的数据处理模型不是“每日全量重跑”，而是：

- 每日定时拉取增量数据
- 比较新旧快照
- 识别新增 / 更正 / 撤销
- 将差异转为标准化事件
- 只对受影响病例做局部重算

后续院感预警分析设计必须严格遵守这个约束。

### 6. 时间线视图是稳定输出层

`PatientTimelineViewServiceImpl` 与 `timeline-view-rules.yaml` 共同定义了展示模型。

修改这部分时要注意：

- 前端依赖的数据结构不要轻易破坏
- 标签、风险、徽章规则优先在规则配置层扩展
- 只有在规则无法表达时，再改 Java 逻辑

## 开发阶段边界

### 当前优先开发

- 标准化事件入池
- `infection_event_pool`
- `infection_llm_node_run`
- `infection_case_snapshot`
- 院感法官基础节点
- `infection_alert_result`

### 当前不开发

- 最终审核 Agent

要求：

- 可以做架构规划
- 可以做接口预留
- 不做正式实现

## 修改优先级建议

当需求不明确时，按以下顺序思考：

1. 是否影响当前可运行主链路
2. 是否影响数据库写入或摘要正确性
3. 是否影响对外接口返回结构
4. 是否只是预留模块增强

如果一个改动会破坏主链路稳定性，应先给出风险判断，再动手。

## 代码风格要求

- 优先小步修改，避免大范围无关重构
- 复用已有命名风格和包结构
- 保持方法职责单一
- 新增配置优先进入 `application.yaml` 或专用配置类
- 新增规则优先考虑放入 YAML 规则文件
- 使用 `apply_patch` 做文件迁移或重命名时，不要使用 `*** Move to:` 语法；统一采用“新增新文件 + 更新引用 + 删除旧文件”的方式，避免补丁解析失败

## 当前结构基线

当前调度主链路统一为：

`@Scheduled -> InfectionPipelineFacade(具体类) -> StageDispatcher -> StageCoordinator -> WorkUnitExecutor -> Handler`

当前 `domain.normalize` 已按职责拆为：

- `assemble`
- `facts`
- `facts.candidate`
- `prompt`
- `support`
- `validation`

当前模型调用外部边界统一为：

- `ai.gateway.AiGateway`

## 封装与抽象治理准则

新增 `interface`、`abstract class`、`*Registry`、`*Factory` 之前，必须先明确要隔离的变化点，并且满足以下条件之一：

1. 当前迭代或下一迭代内，确定会出现第二个实现。
2. 该抽象用于隔离外部边界，例如模型调用、线程调度、持久化、第三方 SDK。
3. 已经出现 3 处以上稳定重复逻辑，可以沉淀为统一模板。

如果三条都不满足，则默认先使用具体类。

## 禁止事项

- 禁止新增“单实现接口 + Default 实现”双层壳。
- 禁止为了“看起来分层”而新增只做命名转发的包装类。
- 禁止在复杂目录中继续平铺 enum、record、helper、validator、builder、impl。
- 禁止保留 `LegacyCompatible`、`Default` 这类过渡态命名作为长期主实现名称。

## 推荐做法

- 优先使用“具体职责类”命名，例如 `NormalizeHandler`、`StageDispatcher`。
- 如果存在稳定重复逻辑，优先抽“父类模板 + 具体职责类”，不要同时保留单实现接口。
- 包结构按职责拆分；当一个目录同时出现执行类、元数据类、helper、校验类时，应评估拆子包。
- 结构性重构完成后，至少执行一次：

```bash
./mvnw -q -DskipTests compile
```

## 文档同步要求

凡是涉及架构、包结构、抽象治理规则变化的修改，必须同步更新：

- `docs/scheduling/shared-executor-architecture-design.md`
- `docs/code-paths/code-chain-index.md`
- `README.md`
- `AGENTS.md`
- 对复杂流程可以加少量注释，但不要写低价值注释

## 测试与验证要求

当前项目测试基础较弱，因此每次改动后至少应说明：

- 是否影响定时任务链路
- 是否影响数据库读写
- 是否影响模型输出结构
- 是否影响 API 返回字段

如可补充测试，优先考虑：

- Service 层单测
- 时间线转换测试
- JSON 解析与校验测试
- Mapper 集成测试
- 事件标准化测试
- 增量差异识别测试
- 院感预警节点输入输出测试

## 配置与安全要求

- 不要把新的真实账号口令直接写入共享文档
- 不要扩大 `application.yaml` 中明文敏感信息范围
- 如涉及数据库、模型服务、Redis 地址，优先建议环境化配置

## 文档协作要求

当新增重要模块时，应同步评估是否更新以下文档：

- `README.md`
- `docs/overview/architecture.md`

如果改动改变了主链路或模块职责，必须同步更新文档说明。

## 默认输出偏好

在本项目中协作时，优先输出：

- 明确改动点
- 明确影响范围
- 明确是否影响主链路
- 明确是否需要配置变更或数据表变更

避免：

- 空泛描述
- 只讲概念不落到类和文件
- 未确认现有结构就直接重构
