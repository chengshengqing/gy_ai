# AI 智能预审演示快照表实施清单

## 1. 文档目标

本文档用于指导一次性生成演示数据快照。

目标是从现有数据库读取已生成的数据，按 `reqno` 生成并保存到一张临时演示表中，供外部程序通过 `reqno` 查询后直接展示：

- 患者时间线 HTML 页面
- AI 智能预审 JSON

本方案只服务于临时演示，不进入院感预警正式主链路。

## 2. 已确认前提

- 数据库中已经存在患者时间线来源数据。
- 数据库中已经存在院感法官结果。
- `docs/demo/infection-pre-review-demo-extension-plan.md` 已完成。
- `infection_alert_result.result_json` 中已包含 `missingEvidenceReminders` 和 `aiSuggestions`。
- 不需要重新调用模型。
- 不需要重新跑定时任务。
- 不需要分页读取超过 50 条的患者时间线数据。
- 外部程序使用 `<iframe srcdoc="...">` 方式展示 HTML。
- 需要生成的患者来自 `src/main/resources/application-local.yaml` 中的 `infection.monitor.debug-reqnos`。

当前本地默认 `debug-reqnos`：

```text
260221118,260302506,260304621,260218553,260220893,260304412,260215773,260218494,260217042
```

## 3. 目标表设计

建议新增持久化演示表，不使用 SQL Server `#temp` 临时表，因为外部程序需要跨连接按 `reqno` 查询。

表名建议：

```text
infection_pre_review_demo
```

字段只保留 3 个业务字段：

```text
reqno
timeline_html
ai_pre_review_json
```

建表 SQL 草案：

```sql
IF OBJECT_ID('dbo.infection_pre_review_demo', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.infection_pre_review_demo (
        reqno VARCHAR(64) NOT NULL PRIMARY KEY,
        timeline_html NVARCHAR(MAX) NULL,
        ai_pre_review_json NVARCHAR(MAX) NULL
    );
END
GO
```

说明：

- `reqno` 作为主键，同一个患者重复生成时覆盖旧快照。
- `timeline_html` 建议用 `NVARCHAR(MAX)`，避免中文 HTML 内容出现编码问题。
- `ai_pre_review_json` 建议用 `NVARCHAR(MAX)`，避免中文 JSON 内容出现编码问题。
- 该表不承载正式业务状态，不参与现有院感结果版本化。

## 4. 数据来源

### 4.1 患者时间线

复用现有服务：

```text
PatientTimelineViewService.buildTimelineViewData(reqno, 1, 50)
```

现有链路：

```text
PatientTimelineController
  -> PatientTimelineViewServiceImpl
  -> patient_raw_data.event_json
  -> timeline-view-rules.yaml
  -> PatientTimelineViewData
```

本次不直接调用 HTTP 接口，建议在服务内直接调用 `PatientTimelineViewService`。

### 4.2 AI 智能预审

读取现有表：

```text
infection_case_snapshot
infection_alert_result
```

关联口径：

- 以 `infection_case_snapshot.reqno` 为患者维度。
- 按 `reqno` 查询 `infection_alert_result` 最新一条结果。
- 最新排序建议：`create_time DESC, result_version DESC, id DESC`。

查询 SQL 草案：

```sql
SELECT
    s.reqno,
    s.primary_site,
    s.nosocomial_likelihood,
    r.result_json
FROM infection_case_snapshot s
OUTER APPLY (
    SELECT TOP 1 *
    FROM infection_alert_result r
    WHERE r.reqno = s.reqno
    ORDER BY r.create_time DESC, r.result_version DESC, r.id DESC
) r
WHERE s.reqno = #{reqno}
```

## 5. HTML 生成要求

由于外部程序通过 `<iframe srcdoc="...">` 直接塞 HTML，`timeline_html` 必须是自包含页面。

不要直接保存 `src/main/resources/static/patient_summary_timeline_view.html` 原文，因为该页面当前会请求：

```text
/api/patient-summary/timeline-view?reqno=...
```

在 `srcdoc` 场景下，该请求通常会打到外部程序所在域名，导致加载失败。

推荐生成方式：

1. 复用现有时间线页面的 CSS 和渲染函数。
2. 去掉原页面中的 `<div class="header"></div>` 和 `<div class="toolbar"></div>` 区域。
3. 移除或绕过 `fetch(...)` 数据加载逻辑。
4. 生成自包含 HTML。
5. 可以选择将当前 `reqno` 的 `PatientTimelineViewData` 序列化为内嵌 JSON 后由页面脚本渲染，也可以由服务端直接预渲染 HTML。

本次实现采用服务端直接预渲染 HTML：

- 不保留查询输入框。
- 不保留接口请求逻辑。
- 不保留 fallback demo 数据逻辑。
- 保留左侧时间轴和右侧单日详情的切换交互。

内嵌数据建议形态：

```html
<script id="timeline-data" type="application/json">
{"reqno":"260215773","items":[]}
</script>
```

然后由页面脚本读取：

```javascript
const embedded = document.getElementById('timeline-data').textContent;
state.data = JSON.parse(embedded);
```

注意：

- 内嵌 JSON 时必须做安全转义，避免 `</script>` 截断脚本标签。
- 生成 HTML 时不再保留自动 fallback demo 数据逻辑。
- `PatientTimelineViewData` 只查 `pageNo=1,pageSize=50`。
- 生成后的 HTML 不展示页面标题区和查询工具栏，避免嵌入外部系统后出现重复标题、重复输入框。

## 6. AI 智能预审 JSON 生成要求

最终保存到 `ai_pre_review_json` 的内容建议包括：

```json
{
  "reqno": "260215773",
  "lastJudgeTime": "2026-04-12 14:03:50",
  "primarySiteCode": "lower_respiratory",
  "primarySite": "下呼吸道",
  "nosocomialLikelihood": "medium",
  "nosocomialLikelihoodLabel": "中",
  "resultJson": {
    "warningLevel": "high",
    "decisionReason": [],
    "missingEvidenceReminders": [],
    "aiSuggestions": []
  }
}
```

字段口径：

- `reqno`：当前患者住院号。
- `lastJudgeTime`：`infection_case_snapshot.last_judge_time`，格式为 `yyyy-MM-dd HH:mm:ss`，保留到秒。
- `primarySiteCode`：`infection_case_snapshot.primary_site` 原始 code。
- `primarySite`：通过 `InfectionBodySite` 转换后的中文。
- `nosocomialLikelihood`：`infection_case_snapshot.nosocomial_likelihood` 原始 code。
- `nosocomialLikelihoodLabel`：通过 `InfectionNosocomialLikelihood` 转换后的中文。
- `resultJson`：最新 `infection_alert_result.result_json` 解析后用于展示的 JSON 对象，只保留 `warningLevel`、`decisionReason`、`missingEvidenceReminders`、`aiSuggestions`。
- `resultJson` 中去掉 `decisionStatus`、`primarySite`、`nosocomialLikelihood`，也不保留未列出的其他字段。
- `resultJson.aiSuggestions[].category`：模型输出为枚举 code，需要转换为中文值。
- `resultJson.aiSuggestions`：按 `priority` 排序，顺序为 `high -> medium -> low`。

`aiSuggestions` 输入格式示例：

```json
[
  {
    "priority": "low|medium|high",
    "category": "diagnosis|test|treatment|monitoring|review",
    "text": "面向医生的简短预审建议"
  }
]
```

`category` 中文转换建议：

```text
diagnosis -> 诊断
test -> 检查
treatment -> 治疗
monitoring -> 监测
review -> 复核
```

`aiSuggestions` 输出只保留原字段，不额外增加 label 字段；其中 `category` 转换为中文：

```json
{
  "priority": "high",
  "category": "检查",
  "text": "建议立即完善血培养、痰培养及降钙素原检测，以明确病原体并指导精准抗感染治疗。"
}
```

容错建议：

- `primary_site` 为空或非法时，中文显示为 `未知`。
- `nosocomial_likelihood` 为空或非法时，中文显示为 `未知`。
- `result_json` 为空时，保存固定结构的 `resultJson` 对象。
- `result_json` 解析失败时，保存固定结构的 `resultJson` 对象，不额外输出 `resultJsonRaw`。
- `aiSuggestions[].category` 为空或非法时，中文显示为 `其他` 或 `复核`，建议优先使用 `复核`。
- `aiSuggestions[].priority` 为空或非法时，排序降级到最后。

## 7. 推荐代码落点

### 7.1 SQL

新增：

```text
src/main/resources/sql/infection_pre_review_demo.sql
```

### 7.2 Entity

新增：

```text
src/main/java/com/zzhy/yg_ai/domain/entity/InfectionPreReviewDemoEntity.java
```

建议字段：

```text
reqno
timelineHtml
aiPreReviewJson
```

### 7.3 Mapper

新增：

```text
src/main/java/com/zzhy/yg_ai/mapper/InfectionPreReviewDemoMapper.java
```

如需要复杂 SQL，可新增：

```text
src/main/resources/mapper/InfectionPreReviewDemoMapper.xml
```

建议 Mapper 承担：

- `upsertDemoSnapshot(...)`
- `selectLatestPreReviewByReqno(...)`

不要在 Service 中直接拼 JDBC。

### 7.4 Service

新增具体职责类，建议：

```text
src/main/java/com/zzhy/yg_ai/service/impl/InfectionPreReviewDemoSnapshotService.java
```

本需求是临时演示的一次性生成工具，不建议新增“单实现接口 + Impl”双层壳。

建议职责：

- 读取 `infection.monitor.debug-reqnos`
- 遍历 reqno
- 调用 `PatientTimelineViewService`
- 生成自包含 HTML
- 查询 AI 预审结果
- 组装 AI 预审 JSON
- upsert 到 `infection_pre_review_demo`
- 返回生成结果摘要

### 7.5 Controller 或 Runner

新增手动触发接口：

```text
GET /api/demo/infection-pre-review/snapshot/generate
```

原因：

- 本次是一次性演示数据生成，不应随应用启动自动执行。
- 手动接口便于确认执行时机。
- 执行完成后可以删除或保留为 demo 工具接口。
- 该接口存在写库副作用，正式业务接口仍应优先使用 POST；本次按演示调用便利性使用 GET。

接口返回建议：

```json
{
  "requested": 9,
  "success": 9,
  "failed": 0,
  "items": [
    {
      "reqno": "260215773",
      "success": true,
      "message": "OK"
    }
  ]
}
```

## 8. 实施清单

- [ ] 新增 `infection_pre_review_demo.sql`。
- [ ] 新增 `InfectionPreReviewDemoEntity`。
- [ ] 新增 `InfectionPreReviewDemoMapper`。
- [ ] 如需 XML，新增 `InfectionPreReviewDemoMapper.xml`。
- [ ] 在 Mapper 中实现按 `reqno` 查询最新 AI 预审结果。
- [ ] 在 Mapper 中实现按 `reqno` upsert 演示快照。
- [ ] 新增 `InfectionPreReviewDemoSnapshotService` 具体类。
- [ ] 从配置读取 `infection.monitor.debug-reqnos`。
- [ ] 按英文逗号切分并 trim，过滤空 reqno。
- [ ] 对每个 reqno 调用 `PatientTimelineViewService.buildTimelineViewData(reqno, 1, 50)`。
- [ ] 生成自包含 `timeline_html`。
- [ ] 生成 `timeline_html` 时去掉原页面 `<div class="header"></div>` 和 `<div class="toolbar"></div>` 内容。
- [ ] 查询 `infection_case_snapshot` 和最新 `infection_alert_result`。
- [ ] 将 `infection_case_snapshot.last_judge_time` 格式化为 `lastJudgeTime`，保留到秒。
- [ ] 使用 `InfectionBodySite` 转换 `primarySite` 中文。
- [ ] 使用 `InfectionNosocomialLikelihood` 转换 `nosocomialLikelihoodLabel` 中文。
- [ ] 将 `infection_alert_result.result_json` 解析为 JSON 对象后写入 `resultJson`。
- [ ] `resultJson` 只保留 `warningLevel`、`decisionReason`、`missingEvidenceReminders`、`aiSuggestions`。
- [ ] 将 `resultJson.aiSuggestions[].category` 转换为中文值，不增加额外 label 字段。
- [ ] 按 `high -> medium -> low` 对 `resultJson.aiSuggestions` 排序。
- [ ] 组装 `ai_pre_review_json`。
- [ ] upsert 到 `infection_pre_review_demo`。
- [ ] 新增手动触发接口 `GET /api/demo/infection-pre-review/snapshot/generate`。
- [ ] 对单个缺失数据患者记录失败原因，但不要中断其他患者生成。
- [ ] 执行编译验证。

编译命令：

```bash
./mvnw -q -DskipTests compile
```

## 9. 验证清单

- [ ] 调用手动生成接口。
- [ ] 确认返回的 `requested` 等于 `debug-reqnos` 数量。
- [ ] 确认每个目标 `reqno` 在 `infection_pre_review_demo` 中有记录。
- [ ] 查询 `timeline_html` 不为空。
- [ ] 将 `timeline_html` 放入 `<iframe srcdoc="...">` 后可直接展示，不依赖 `/api/patient-summary/timeline-view` 请求。
- [ ] 查询 `ai_pre_review_json` 不为空。
- [ ] `ai_pre_review_json.primarySite` 为中文。
- [ ] `ai_pre_review_json.resultJson.missingEvidenceReminders` 存在。
- [ ] `ai_pre_review_json.resultJson.aiSuggestions` 存在。

## 10. 影响范围

- 定时任务链路：不影响。
- 数据库读写：新增并写入 `infection_pre_review_demo`，读取 `infection_case_snapshot`、`infection_alert_result`、`patient_raw_data`。
- 模型输出结构：不影响，不新增模型调用。
- API 返回字段：不影响现有接口；仅新增 demo 手动接口。
- 主链路稳定性：不影响 `InfectionPipelineFacade -> StageDispatcher -> StageCoordinator -> WorkUnitExecutor -> Handler` 主流程。
- 配置变更：不新增配置，复用 `infection.monitor.debug-reqnos`。

## 11. 不做事项

- 不开发最终审核 Agent。
- 不新增院感预警正式节点。
- 不修改 `infection_alert_result` 表结构。
- 不修改 `infection_case_snapshot` 表结构。
- 不重新生成 `infection_alert_result.result_json`。
- 不调用 LLM。
- 不引入新的调度任务。
- 不将演示表纳入正式结果版本化。
