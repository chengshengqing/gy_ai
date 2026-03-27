# yg_ai 重构项清单

## 1. 文档目的

本文档用于汇总“查看我的 `yg_ai` 项目，梳理代码结构”过程中已经明确的重构项，作为后续代码治理、数据库演进和任务拆分的统一入口。

当前只整理已经在现有文档中明确出现的内容，不额外发散新增范围。

## 2. 当前已识别的主要问题

### 2.1 配置管理仍是开发态

当前 `application.yaml` 直接包含数据库地址、账号、密码以及模型服务地址，存在明显的环境耦合和配置安全风险。

重构目标：

- 将敏感配置迁移到环境变量
- 引入 `application-local.yaml`
- 视部署形态进一步接入配置中心

影响范围：

- `src/main/resources/application.yaml`
- 启动配置加载方式
- 本地开发和部署流程

### 2.2 患者扫描策略存在硬编码

当前 `selectActiveReqnos` 仍使用固定患者白名单，只适合开发或演示环境，不适合作为生产扫描策略。

重构目标：

- 去除硬编码患者范围
- 改为配置化、业务规则驱动或数据库条件驱动
- 保证扫描范围与增量处理模式一致

影响范围：

- `PatientRawDataMapper`
- 对应 XML 查询
- 定时调度入口

### 2.3 主链路与预留能力边界不清

当前 `InfectionPipeline` 已注入 `WarningAgent`、`AuditAgent`、Redis 等能力，但这些模块并未真正进入主流程，容易让阅读者误判系统状态。

重构目标：

- 明确“已启用模块”和“预留模块”边界
- 避免主链路类继续混入未落地能力
- 同步更新文档说明，降低理解成本

影响范围：

- `ai/orchestrator/InfectionPipeline`
- `ai/agent/WarningAgent`
- `ai/agent/AuditAgent`
- `task/SummaryWarningScheduler`
- 相关文档

### 2.4 自动化测试覆盖不足

当前项目测试主要停留在启动层，缺少核心业务回归保障。

优先缺口：

- Service 层单测
- Mapper 层集成测试
- Prompt / Agent 输出校验测试
- 时间线转换测试
- 增量差异识别测试

重构目标：

- 为主链路建立最小可回归测试集
- 对结构化输出和时间线转换补齐稳定性校验
- 为后续院感预警链路预留测试骨架

## 3. 下一阶段的结构性重构方向

### 3.1 以增量模式重构院感预警链路

后续院感预警分析不能退化成“每日全量重跑”，而应坚持：

1. 接收每日增量快照
2. 做新旧快照差异识别
3. 将变化切分为 EvidenceBlock
4. 通过统一事件抽取器生成标准化事件
5. 写入事件池
6. 维护病例状态快照
7. 仅对受影响病例做局部重算
8. 产出版本化结果和解释信息

### 3.2 建立独立的院感预警数据中枢

建议围绕以下核心对象推进重构：

- `infection_event_pool`
- `infection_llm_node_run`
- `infection_case_snapshot`
- `infection_alert_result`

说明：

- `infection_event_pool` 是事件中枢
- `infection_llm_node_run` 用于记录节点运行留痕
- `infection_case_snapshot` 保存病例当前态
- `infection_alert_result` 保存历史版本输出

### 3.3 将复杂判断从主链路中拆分

建议保持“工作流负责调度、数据层负责组织、LLM 负责复杂判断、规则负责约束和兜底”的基本分工，避免把所有逻辑继续堆到现有主编排类中。

## 4. 建议的落地优先级

### P0

- 重构配置管理
- 去除患者扫描硬编码
- 明确主链路与预留模块边界

原因：

- 这些问题直接影响部署安全、系统认知和后续开发基线

### P1

- 建立 `infection_event_pool`
- 建立 `infection_llm_node_run`
- 梳理增量差异识别层
- 明确院感法官节点输入输出契约

原因：

- 这是院感预警链路可以起步的最小骨架

### P2

- 建立 `infection_case_snapshot`
- 事件驱动局部重算
- 院感法官基础节点

原因：

- 这部分决定系统是否真正具备“当前态更新”能力

### P3

- 建立 `infection_alert_result`
- 页面联动与解释输出
- 人工复核闭环规划

原因：

- 这部分负责版本化结果输出和业务可用性增强

## 5. 推荐的代码落地方式

建议新增模块分层：

### `domain/entity`

- `InfectionEventPoolEntity`
- `InfectionLlmNodeRunEntity`
- `InfectionCaseSnapshotEntity`
- `InfectionAlertResultEntity`

### `mapper`

- `InfectionEventPoolMapper`
- `InfectionLlmNodeRunMapper`
- `InfectionCaseSnapshotMapper`
- `InfectionAlertResultMapper`

### `service`

- `InfectionEventPoolService`
- `InfectionLlmNodeRunService`
- `InfectionCaseSnapshotService`
- `InfectionAlertResultService`

### `service/impl`

- 对应实现类

落地顺序建议：

1. 先建 Entity / Mapper / Service 骨架
2. 先打通 `infection_event_pool` 写入
3. 再补 `infection_llm_node_run` 留痕
4. 再补 `infection_case_snapshot`
5. 最后补 `infection_alert_result`

## 6. 当前明确不进入实现的内容

以下内容当前只做规划，不做正式开发：

- 最终审核 Agent
- 多级人工复核流程表
- 多角色审核记录表

## 7. 一句话结论

`yg_ai` 下一阶段的重构重点，不是继续叠加零散能力，而是先完成配置治理、扫描策略治理和院感事件中枢建设，再逐步把系统演进为“增量接入 + 事件驱动 + 状态快照 + 结果版本化”的院感监测架构。
