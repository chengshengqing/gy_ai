# yg_ai 重构任务拆分

## 1. 文档目的

本文档基于 [docs/refactor-plan.md](/Users/chengshengqing/IdeaProjects/yg_ai/docs/refactor-plan.md) 的重构项清单，继续下钻为可执行的开发任务，方便后续按阶段推进。

目标不是一次性完成所有重构，而是按优先级逐步收敛风险、建立骨架、再扩展能力。

## 2. 执行原则

- 优先保证现有主链路可运行
- 优先做低耦合、高收益的治理项
- 每一步都要明确影响范围
- 每一步都要尽量具备可验证结果
- 不在当前阶段实现最终审核 Agent

## 3. P0 任务

### Task P0-1 配置环境化治理

目标：

- 去除共享配置中的强环境依赖
- 降低数据库、模型服务、Redis 配置泄露风险

建议改动：

- 拆分 `application.yaml` 与 `application-local.yaml`
- 将敏感配置改为环境变量读取
- 为本地开发保留示例配置说明

涉及文件：

- `src/main/resources/application.yaml`
- 新增 `src/main/resources/application-local.yaml`
- `README.md`

交付物：

- 基础公共配置
- 本地环境专用配置模板
- 启动说明文档更新

验收标准：

- 共享配置中不再出现真实口令
- 本地开发仍可通过环境化方式启动
- 文档能说明本地如何注入配置

### Task P0-2 去除患者扫描硬编码

目标：

- 将当前开发态白名单扫描切换为可配置或规则驱动方式

建议改动：

- 梳理 `selectActiveReqnos` 当前逻辑
- 将固定 `reqno` 白名单改为配置项或业务筛选条件
- 为扫描范围保留调试开关，但默认不走硬编码

涉及文件：

- `mapper` 对应 XML
- `PatientRawDataMapper`
- `PatientServiceImpl`
- 调度入口类

交付物：

- 新版患者扫描策略
- 调试模式与正式模式边界说明

验收标准：

- 默认模式下不依赖硬编码患者清单
- 调试模式仍可定向指定患者
- 不破坏现有增量采集主流程

### Task P0-3 主链路与预留模块边界治理

目标：

- 明确哪些能力已经进入生产主链路
- 避免预留组件继续混入核心流程认知

建议改动：

- 清理或标注 `InfectionPipeline` 中未落地依赖
- 为 `WarningAgent`、`AuditAgent`、`SummaryWarningScheduler` 增加边界说明
- 同步修正文档中的模块状态描述

涉及文件：

- `ai/orchestrator/InfectionPipeline`
- `ai/agent/WarningAgent`
- `ai/agent/AuditAgent`
- `task/SummaryWarningScheduler`
- `README.md`
- `docs/architecture.md`

交付物：

- 更清晰的主链路边界
- 预留模块说明

验收标准：

- 阅读主流程代码时可明确区分已启用与预留能力
- 文档与代码口径一致

## 4. P1 任务

### Task P1-1 建立院感事件池骨架

目标：

- 为院感预警链路建立最小数据中枢

建议改动：

- 新增 `InfectionEventPoolEntity`
- 新增 `InfectionEventPoolMapper`
- 新增 `InfectionEventPoolService`
- 明确主键、来源引用、事件类型、证据、状态字段

交付物：

- 实体、Mapper、Service 骨架
- 建表 SQL 或表结构文档

验收标准：

- 代码侧可完成事件池基础写入
- 字段命名与枚举定义明确

### Task P1-2 建立节点运行留痕骨架

目标：

- 记录 LLM 节点运行过程，提升可追踪性

建议改动：

- 新增 `InfectionLlmNodeRunEntity`
- 新增 `InfectionLlmNodeRunMapper`
- 新增 `InfectionLlmNodeRunService`
- 统一记录运行批次、输入来源、状态、错误信息、耗时

交付物：

- 节点运行留痕表结构
- 基础写入接口

验收标准：

- 节点运行结果可被追踪
- 失败、重试、成功状态可区分

### Task P1-3 梳理增量差异识别层

目标：

- 为事件池写入建立上游输入层

建议改动：

- 定义新旧快照差异识别对象
- 统一输出新增、修改、撤销三类变化
- 避免直接对整病例历史重复分析

交付物：

- 差异识别模型
- 差异识别服务骨架

验收标准：

- 同一病例仅对受影响数据产生变化结果
- 输出结果可直接用于事件抽取

### Task P1-4 明确院感法官节点输入输出契约

目标：

- 在实现节点前先稳定接口协议

建议改动：

- 定义节点输入对象
- 定义节点输出对象
- 统一风险级别、状态、证据字段语义

交付物：

- 输入输出 DTO / model
- 契约文档

验收标准：

- 后续节点实现不再依赖临时 JSON 拼接
- 枚举语义在 Java、数据库、Prompt 之间保持一致

## 5. P2 任务

### Task P2-1 建立病例快照骨架

目标：

- 保存院感分析“当前态”

建议改动：

- 新增 `InfectionCaseSnapshotEntity`
- 新增 `InfectionCaseSnapshotMapper`
- 新增 `InfectionCaseSnapshotService`
- 明确当前状态、最近事件、当前风险、最近变更类型

验收标准：

- 可表达病例当前院感状态
- 不直接复制时间轴窗口 JSON 作为快照正文

### Task P2-2 实现事件驱动局部重算

目标：

- 将增量模式真正落到执行层

建议改动：

- 以事件变更作为重算触发条件
- 仅处理受影响病例
- 保留幂等与重放能力

验收标准：

- 不退化成全量每日重跑
- 同一事件重复消费不会产生脏结果

### Task P2-3 建立院感法官基础节点

目标：

- 先实现单一职责的基础判定节点

建议改动：

- 从最小判断闭环开始
- 先实现稳定输入、稳定输出、稳定留痕
- 不在第一阶段堆成多 Agent 体系

验收标准：

- 节点具备基础判定能力
- 可输出结构化风险结果和关键证据

## 6. P3 任务

### Task P3-1 建立结果版本化输出

目标：

- 支撑页面展示、历史回溯和变化解释

建议改动：

- 新增 `InfectionAlertResultEntity`
- 新增 `InfectionAlertResultMapper`
- 新增 `InfectionAlertResultService`
- 记录版本号、风险等级、解释、建议动作

验收标准：

- 同一病例可保留多版本结果
- 可区分当前结果与历史结果

### Task P3-2 页面联动与解释输出

目标：

- 让后续前端或接口可以直接使用结构化结果

建议改动：

- 统一对外返回字段
- 输出风险变化原因
- 输出当前风险摘要和建议复核点

验收标准：

- 输出层字段具备稳定性
- 能支撑页面展示与医生阅读

### Task P3-3 人工复核闭环规划

目标：

- 为后续人工参与留扩展点

说明：

- 当前阶段先完成设计，不进入正式开发

## 7. 测试补齐任务

### Task T-1 主链路 Service 单测

覆盖重点：

- 原始数据采集后的聚合行为
- 摘要更新行为
- 增量任务关键分支

### Task T-2 Mapper 集成测试

覆盖重点：

- 关键查询 SQL
- 患者扫描逻辑
- 新增院感相关表的基础读写

### Task T-3 Agent 输出结构校验测试

覆盖重点：

- `FormatAgent`
- `SummaryAgent`
- `SurveillanceAgent`
- 后续院感法官节点

### Task T-4 时间线转换测试

覆盖重点：

- `PatientTimelineViewServiceImpl`
- `timeline-view-rules.yaml`
- 标签、风险、徽章映射逻辑

## 8. 推荐执行顺序

1. 先完成 P0 配置与主链路治理
2. 再完成 P1 事件池和运行留痕骨架
3. 再进入 P2 快照与局部重算
4. 最后做 P3 结果版本化和页面联动
5. 测试补齐贯穿整个过程，不要留到最后一次性补

## 9. 一句话结论

如果要让 `yg_ai` 的重构真正可执行，最合理的路径是先治理配置和主链路边界，再建立院感事件池与节点留痕骨架，随后补齐快照、局部重算和结果版本化，而不是直接跳到复杂 Agent 编排。
