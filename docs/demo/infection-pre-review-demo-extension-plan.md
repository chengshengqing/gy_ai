# AI 智能预审演示增强临时方案

## 1. 文档目标

本文档记录 AI 智能预审演示增强的临时实现方案。

本方案只服务于快速演示，目标是在不影响现有院感法官裁决质量的前提下，为 `infection_alert_result.result_json` 补充两块展示数据：

- 关键依据缺失提醒
- AI 建议

演示完成后，应按本文档的删除清单移除该临时链路。

## 2. 背景与结论

当前 AI 智能预审已有两块结果基本可用：

- 预审结论
  来自 `infection_case_snapshot` 与 `infection_alert_result`。
- 预审依据
  来自 `JudgeDecisionResult.decisionReason`、`newSupportingKeys`、`newAgainstKeys`、`newRiskKeys` 等字段。

本次不考虑页面展示，只考虑数据落库。

由于当前模型为 27B 级别，若直接把“关键依据缺失提醒”和“AI 建议”加入现有法官裁决 Prompt，可能影响 `decisionStatus`、`warningLevel`、`primarySite`、`nosocomialLikelihood`、`infectionPolarity` 等核心裁决字段的输出稳定性。

因此临时方案采用：

```text
CASE_RECOMPUTE
  -> InfectionJudgeServiceImpl
  -> 第一次 LLM：现有 CASE_JUDGE，保持不变
  -> parseDecision 成功后
  -> 第二次 LLM：演示用预审补充内容生成
  -> 合并到 JudgeDecisionResult
  -> CaseRecomputeHandler 一次写入 infection_alert_result.result_json
```

## 3. 架构边界

本方案遵守以下边界：

- 不新增调度阶段。
- 不新增最终审核 Agent。
- 不新增 `infection_case_snapshot` 字段。
- 不新增数据库表。
- 不修改 `infection_alert_result` 表结构。
- 不修改页面和 API。
- 不改变 `CASE_JUDGE` 主 Prompt 的核心裁决职责。
- 不让第二次 LLM 调用失败影响 `CASE_RECOMPUTE` 主链路。

结果只写入：

- `infection_alert_result.result_json`

仍由现有 `CaseRecomputeHandler.persistAlertResult(...)` 完成一次保存。

## 4. 数据结构建议

在 `JudgeDecisionResult` 中临时增加两个字段：

```java
List<PreReviewMissingEvidenceReminder> missingEvidenceReminders
List<PreReviewAiSuggestion> aiSuggestions
```

建议新增轻量 record：

```java
public record PreReviewMissingEvidenceReminder(
        String level,
        String title,
        String message
) {
}
```

```java
public record PreReviewAiSuggestion(
        String priority,
        String category,
        String text
) {
}
```

说明：

- 第一版不强制引入 `basisKeys`，降低解析和校验复杂度。
- 如果后续演示需要展示来源，可再临时增加 `basisKeys`，但必须注意只引用真实 `event_key`。
- `level` 建议使用 `info / warning / critical`。
- `priority` 建议使用 `low / medium / high`。
- `category` 建议使用 `diagnosis / test / treatment / monitoring / review`。

失败或无输出时统一返回：

```json
{
  "missingEvidenceReminders": [],
  "aiSuggestions": []
}
```

## 5. 第二次 LLM 调用设计

### 5.1 调用位置

推荐放在 `InfectionJudgeServiceImpl.judge(...)` 的主法官解析成功之后。

不要放在 `rawOutput = aiGateway.callSystem(...)` 后立即执行，因为此时主法官输出尚未经过 `parseDecision(...)` 校验。

推荐流程：

```java
String prompt = WarningPromptCatalog.buildCaseJudgePrompt();
rawOutput = aiGateway.callSystem(...);
JudgeDecisionResult parsed = infectionCaseJudgeSupport.parseDecision(rawOutput, safePacket, judgeTime);
infectionLlmNodeRunService.markSuccess(...);
return enrichDemoPreReviewBlocks(parsed, safePacket);
```

如果 `enrichDemoPreReviewBlocks(...)` 失败：

- 记录 warn。
- 返回 `parsed`，其中两个新增字段为空数组。
- 不抛异常。
- 不触发主法官 fallback。

### 5.2 NodeType

建议复用现有枚举：

```java
InfectionNodeType.EXPLANATION_GENERATOR
```

建议节点名：

```text
infection-pre-review-demo-extension
```

建议 Prompt 版本：

```text
infection-pre-review-demo-extension-v1
```

### 5.3 LLM 入参

入参保持简洁，不直接传完整原始大 JSON。

建议传：

- `decision`
  - 已解析的 `JudgeDecisionResult`
- `eventCatalog`
  - 来自 `InfectionEvidencePacket.eventCatalog`
- `evidenceGroups`
  - 来自 `InfectionEvidencePacket.evidenceGroups`
- `decisionBuckets`
  - 来自 `InfectionEvidencePacket.decisionBuckets`
- `precomputed`
  - 来自 `InfectionEvidencePacket.precomputed`
- `judgeContext`
  - 来自 `InfectionEvidencePacket.judgeContext`
- `recentChanges`
  - 来自 `InfectionEvidencePacket.recentChanges`

不建议传：

- `patient_raw_data.data_json`
- `patient_raw_data.filter_data_json`
- 原始病程大文本
- 完整时间线大 JSON

### 5.4 LLM 出参

要求模型只输出 JSON：

```json
{
  "missingEvidenceReminders": [
    {
      "level": "warning",
      "title": "缺少病原学依据",
      "message": "当前感染判断缺少病原学结果支持，建议结合感染部位补充培养或相关病原学检查。"
    }
  ],
  "aiSuggestions": [
    {
      "priority": "high",
      "category": "test",
      "text": "建议动态复查血常规、CRP、PCT，并结合疑似感染部位完善病原学送检。"
    }
  ]
}
```

Prompt 约束：

- 不允许改写主法官裁决结论。
- 不允许重新判断 `decisionStatus`。
- 不允许输出诊疗医嘱口吻的强制指令。
- 只基于输入证据生成缺失提醒和预审建议。
- 没有明确缺失依据时返回空数组。
- 没有明确建议时返回空数组。

## 6. 轻量解析与失败策略

本方案为演示临时链路，不做复杂校验。

建议只保留以下轻量处理：

- JSON parse 失败：返回空数组。
- 字段不存在：返回空数组。
- 数组超长：截断到 5 条。
- 单条文本过长：截断到 200 字以内。
- 第二次 LLM 调用异常：返回空数组。

不要因为第二次 LLM 失败导致：

- `CASE_RECOMPUTE` 失败。
- 主法官结果 fallback。
- `infection_case_snapshot` 不更新。
- `infection_alert_result` 不保存。

## 7. 实施清单

### 7.1 模型结构

- [ ] 修改 `domain/model/JudgeDecisionResult.java`
  - [ ] 增加 `missingEvidenceReminders`
  - [ ] 增加 `aiSuggestions`
  - [ ] canonical constructor 中将 null 转为空数组

- [ ] 新增轻量 record
  - [ ] `domain/model/PreReviewMissingEvidenceReminder.java`
  - [ ] `domain/model/PreReviewAiSuggestion.java`

### 7.2 Prompt

- [ ] 修改 `ai/prompt/WarningPromptCatalog.java`
  - [ ] 新增 `PRE_REVIEW_DEMO_EXTENSION_PROMPT_VERSION`
  - [ ] 新增 `buildPreReviewDemoExtensionPrompt()`
  - [ ] Prompt 明确只生成 `missingEvidenceReminders` 与 `aiSuggestions`
  - [ ] 不修改现有 `buildCaseJudgePrompt()` 的输出 schema

### 7.3 LLM 调用

- [ ] 修改 `service/impl/InfectionJudgeServiceImpl.java`
  - [ ] 在 `parseDecision(...)` 成功后追加第二次 LLM 调用
  - [ ] 使用 `PipelineStage.CASE_RECOMPUTE`
  - [ ] 使用 `InfectionNodeType.EXPLANATION_GENERATOR`
  - [ ] 第二次调用失败时返回空数组增强结果，不抛异常
  - [ ] 保持主 `CASE_JUDGE` 调用、解析、fallback 逻辑不变

- [ ] 保留最小 LLM 运行审计
  - [ ] 创建 `infection_llm_node_run` pending 记录
  - [ ] 成功时 mark success
  - [ ] 失败时 mark failed
  - [ ] mark failed 失败时只记录日志，不影响主流程

### 7.4 入参组装

- [ ] 在 `InfectionJudgeServiceImpl` 内新增私有方法，例如：
  - [ ] `buildPreReviewDemoExtensionInput(...)`
  - [ ] `parsePreReviewDemoExtension(...)`
  - [ ] `enrichDemoPreReviewBlocks(...)`

- [ ] 入参只包含：
  - [ ] parsed decision
  - [ ] packet.eventCatalog
  - [ ] packet.evidenceGroups
  - [ ] packet.decisionBuckets
  - [ ] packet.precomputed
  - [ ] packet.judgeContext
  - [ ] packet.recentChanges

### 7.5 落库

- [ ] 不修改 `CaseRecomputeHandler.persistAlertResult(...)`
- [ ] 确认 `result.setResultJson(writeJson(decision))` 能保存新增字段
- [ ] 不修改 `infection_alert_result.sql`
- [ ] 不修改 `InfectionAlertResultEntity`
- [ ] 不修改 `InfectionCaseSnapshotEntity`

### 7.6 验证

- [ ] 执行编译：

```bash
./mvnw -q -DskipTests compile
```

- [ ] 通过单个病例触发 `CASE_RECOMPUTE`
- [ ] 查询 `infection_alert_result.result_json`
  - [ ] 确认存在 `missingEvidenceReminders`
  - [ ] 确认存在 `aiSuggestions`
  - [ ] 第二次 LLM 失败时两个字段为 `[]`

## 8. 影响范围

- 定时任务链路：不新增调度阶段，不改变 `CASE_RECOMPUTE` 触发方式。
- 数据库读写：不新增表字段，只扩展 `infection_alert_result.result_json` 内容。
- 模型输出结构：主法官输出结构不变，新增第二次演示用输出。
- API 返回字段：不涉及。
- 页面展示：不涉及。
- 主链路风险：第二次 LLM 调用失败不影响主法官结果和病例状态快照更新。

## 9. 删除清单

演示完成后，应删除以下临时内容。

### 9.1 删除 Prompt

- [ ] 删除 `WarningPromptCatalog.PRE_REVIEW_DEMO_EXTENSION_PROMPT_VERSION`
- [ ] 删除 `WarningPromptCatalog.buildPreReviewDemoExtensionPrompt()`
- [ ] 删除相关 Prompt 文本

### 9.2 删除第二次 LLM 调用

- [ ] 从 `InfectionJudgeServiceImpl.judge(...)` 中删除 `enrichDemoPreReviewBlocks(...)` 调用
- [ ] 删除 `buildPreReviewDemoExtensionInput(...)`
- [ ] 删除 `parsePreReviewDemoExtension(...)`
- [ ] 删除 `enrichDemoPreReviewBlocks(...)`
- [ ] 删除 `infection-pre-review-demo-extension` 节点审计创建逻辑

### 9.3 删除临时结果字段

如果后续正式方案不复用这两个字段：

- [ ] 从 `JudgeDecisionResult` 删除 `missingEvidenceReminders`
- [ ] 从 `JudgeDecisionResult` 删除 `aiSuggestions`
- [ ] 删除 `PreReviewMissingEvidenceReminder`
- [ ] 删除 `PreReviewAiSuggestion`

如果正式方案要保留这两个字段：

- [ ] 将字段迁移到正式的结果解释节点输出模型
- [ ] 明确字段由正式节点生成，不再由 `InfectionJudgeServiceImpl` 临时生成
- [ ] 更新正式 Prompt、校验和文档

### 9.4 清理审计数据口径

- [ ] 明确 `infection_llm_node_run.node_type = EXPLANATION_GENERATOR` 中的演示期记录是否保留
- [ ] 若运维口径需要区分，按 `node_name = infection-pre-review-demo-extension` 过滤演示期记录

### 9.5 删除本文档或改为历史记录

- [ ] 删除本文档，或移动到历史归档目录
- [ ] 如果正式方案已经落地，在 `docs/overview/architecture.md` 和 `docs/infection-warning/infection-warning-design.md` 中补充正式方案说明

## 10. 后续正式方案方向

演示方案不代表长期架构。

正式方案建议将“关键依据缺失提醒”和“AI 建议”从 `InfectionJudgeServiceImpl` 中移出，形成独立的结果解释节点或规则 + LLM 组合节点：

```text
CASE_RECOMPUTE
  -> InfectionEvidencePacketBuilder
  -> InfectionJudgeService
  -> ResultExplanationService
  -> infection_alert_result
```

正式方案再考虑：

- 更稳定的输出 schema
- 更细的证据 key 追溯
- 与人工复核闭环联动
- 是否将部分缺失依据提醒规则化
- 是否在 `infection_alert_result` 中增加独立字段
