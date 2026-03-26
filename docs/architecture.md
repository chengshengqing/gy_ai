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

## 3. 总体架构

```text
               +-------------------+
               |   SQL Server      |
               |  住院业务数据源     |
               +---------+---------+
                         |
                         v
              +----------+-----------+
              |   PatientService     |
              | 原始数据查询与按天组装 |
              +----------+-----------+
                         |
                         v
              +----------+-----------+
              |  patient_raw_data    |
              | 原始病程语义块落库      |
              +----------+-----------+
                         |
                         v
              +----------+-----------+
              |  InfectionPipeline   |
              | AI 编排入口           |
              +----------+-----------+
                         |
         +---------------+----------------+
         |                                |
         v                                v
+--------+--------+             +---------+--------+
|   FormatAgent   |             |   SummaryAgent   |
| 病程过滤 / 规整   |             | 摘要 / timeline  |
+--------+--------+             +---------+--------+
         |                                |
         +---------------+----------------+
                         |
                         v
              +----------+-----------+
              |   patient_summary    |
              | 最新时间线摘要         |
              +----------+-----------+
                         |
                         v
              +----------+-----------+
              | TimelineViewService  |
              | 视图转换与规则应用      |
              +----------+-----------+
                         |
                         v
              +----------+-----------+
              | REST API / Static UI |
              +----------------------+
```

并行存在的第二条链路：

```text
PatillnessCourseInfo -> SurveillanceAgent -> 校验 -> ai_process_log / items_infor_zhq
```

## 4. 模块划分

### 4.1 调度层 `task`

职责：

- 驱动周期性任务
- 作为主流程入口
- 避免承载业务细节

模块：

- `InfectionMonitorScheduler`
  定时扫描患者并触发数据采集
- `StructDataFormatScheduler`
  定时消费待结构化病程记录
- `InfectiousSyndromeSurveillanceTask`
  传染病症候群监测任务
- `SummaryWarningScheduler`
  预留任务

设计说明：

- 调度层只负责触发，不做复杂数据拼装。
- 当前症候群监测任务默认未启用自动调度。

### 4.2 编排层 `ai/orchestrator`

核心类：

- `InfectionPipeline`

职责：

- 组织原始病程采集与结构化摘要主流程
- 协调 `PatientService`、`FormatAgent`、`SummaryAgent`
- 负责待处理批次的并发消费

设计说明：

- 该类是院感主链路的统一业务编排入口。
- 当前类中已经注入 `WarningAgent`、`AuditAgent`、`StringRedisTemplate`，但主流程尚未完整使用这些能力。

### 4.3 AI 层 `ai/agent`

职责：

- 封装大模型调用
- 维护 Prompt 输入输出结构
- 将原始文本转为可落库或可展示的结构化结果

核心模块：

- `AbstractAgent`
  提供统一 `ChatModel` 调用能力
- `FormatAgent`
  负责病程文本过滤、规整
- `SummaryAgent`
  负责每日病程摘要抽取与时间线追加
- `SurveillanceAgent`
  负责症候群监测分析

预留模块：

- `WarningAgent`
- `AuditAgent`

设计原则：

- Agent 不直接持有过多业务状态
- 输入输出尽量 JSON 化、结构化
- 复杂业务协调尽量放在编排层或 Service 层

### 4.4 业务服务层 `service`

职责：

- 负责核心业务处理
- 承接 Controller / Task 与 Mapper / AI 之间的桥接
- 负责数据装配、持久化和结果转换

核心服务：

- `PatientServiceImpl`
  原始数据采集、按天聚合、落库、摘要更新
- `PatientSummaryTimelineViewServiceImpl`
  摘要转时间线展示数据
- `AiProcessLogServiceImpl`
  AI 处理日志记录

设计说明：

- `PatientServiceImpl` 是当前数据加工主中心。
- `PatientSummaryTimelineViewServiceImpl` 是展示输出层中心。

### 4.5 数据访问层 `mapper`

职责：

- 统一数据库读写入口
- 保持 SQL 与实体映射清晰

核心 Mapper：

- `PatientRawDataMapper`
- `PatientSummaryMapper`
- `PatillnessCourseInfoMapper`
- `ItemsInforZhqMapper`
- `AiProcessLogMapper`

设计说明：

- `PatientRawDataMapper.xml` 包含最重的查询逻辑，负责从业务库抽取患者多维住院数据。
- 当前项目优先依赖 MyBatis-Plus 与 XML 映射，不应在 Service 中直接堆 JDBC 逻辑。

### 4.6 展示转换层

核心类：

- `PatientSummaryTimelineViewServiceImpl`
- `TimelineViewRuleProperties`

职责：

- 读取 `summaryJson`
- 识别主问题、次问题、风险项
- 生成时间线展示字段
- 将规则配置与业务数据结合

设计说明：

- 展示逻辑采用“Java 规则逻辑 + YAML 规则配置”的混合方式。
- 扩展展示策略时优先考虑规则配置，而不是直接写死在 Java 代码中。

## 5. 核心运行链路

### 5.1 原始数据采集链路

触发入口：

- `InfectionMonitorScheduler.scanPatients()`

执行过程：

1. 查询待处理患者 `reqno`
2. 遍历患者调用 `InfectionPipeline.processPatient(reqno)`
3. 进入 `LoadDataTool.loadPatientRawData(reqno)`
4. `PatientServiceImpl.collectAndSaveRawData(reqno)` 从 SQL Server 聚合查询：
   - 患者信息
   - 诊断
   - 体征
   - 医嘱
   - 病程记录
   - 检验
   - 用药
   - 影像
   - 转科
   - 手术
   - 微生物与药敏
5. 将结果按天分组并写入 `patient_raw_data`

架构特征：

- 采用“源库读取 + 本地中间表缓存”的模式
- 为后续 LLM 处理提供稳定输入

### 5.2 结构化摘要链路

触发入口：

- `StructDataFormatScheduler.formatPendingStructData()`

执行过程：

1. 查询待结构化患者列表
2. 读取 `patient_raw_data` 中 `struct_data_json` 为空的记录
3. 使用 `FormatAgent.filterIllnessCourse()` 去重和清洗病程
4. 使用 `SummaryAgent.extractDailyIllness()` 生成：
   - `structDataJson`
   - `updatedSummaryJson`
5. 回写 `patient_raw_data`
6. 更新 `patient_summary`

架构特征：

- 摘要采用增量构建，而不是每次整份重算
- 时间线在落库前会按时间排序

### 5.3 时间线展示链路

触发入口：

- `/api/patient-summary/timeline-view`

执行过程：

1. 查询某患者最新 `patient_summary`
2. 解析 `summaryJson`
3. 提取 timeline 节点
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

### 5.4 症候群监测链路

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

### 6.3 `patient_summary`

角色：

- 患者最新时间线摘要存储

典型字段用途：

- `summary_json`
  时间线摘要主载体
- `update_time`
  最新摘要更新时间

### 6.4 `ai_process_log`

角色：

- AI 调用日志

用途：

- 记录成功与失败
- 保存原始响应和错误信息

### 6.5 `items_infor_zhq`

角色：

- 症候群监测结果业务落表

用途：

- 保存风险等级
- 保存症候群类型
- 保存关键证据与建议动作

### 6.6 Redis

当前角色：

- 已完成配置
- 预期用于业务缓存或 AI 记忆

当前状态：

- 主链路中未形成强依赖

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

### 8.2 预留能力

- Warning 能力
- Audit 能力
- SummaryWarning 调度
- Redis 记忆增强
- ReactAgent 深度接入

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

- 清理未使用依赖
- 梳理主链路与预留模块边界
- 增加时间线转换测试
- 增加 `SummaryAgent` 输出结构校验测试

### 10.2 中期

- 将症候群监测纳入统一任务调度管理
- 将告警能力从占位模块推进为闭环功能
- 补齐 Redis 在主链路中的明确职责

### 10.3 长期

- 将 Prompt、规则、校验进一步标准化
- 形成 AI 处理链路的可观测性和回放能力
- 视复杂度决定是否拆分独立分析服务

## 11. 架构结论

`yg_ai` 当前是一个以“住院病程采集与 AI 时间线摘要”为主链路的单体后端系统，已经具备较清晰的分层结构和可运行流程；同时保留了告警、审计、症候群监测、Redis 记忆等扩展方向，但其中部分能力仍处于预留或过渡阶段。
