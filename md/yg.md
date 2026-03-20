你是一个Java后端专家。

使用技术栈：
- Java 17
- Spring Boot 3
- MyBatis Plus
- redis
- spring ai （链接：https://docs.spring.io/spring-ai/reference/getting-started.html）
- spring ai alibaba （链接：https://java2ai.com/docs/overview/）

任务：
创建一个基于Spring Boot 3的AI院感预警与审核系统，该系统需要实现以下功能：
- 数据采集层：用spring ai中的@Tools注解，实现数据采集方法，并返回数据。（只规划，不写实现代码）
- DataHandler（数据清洗层）：用@tools注解，实现数据清洗方法。（只规划，不写实现代码）
- AI推理层（Agent Layer）
- 规则引擎层（Rule Engine）
- 预警与审核
- 院感监测系统

Agent设计，系统包含 4个核心Agent
- 格式化Agent 原始数据 → 标准JSON
- 摘要Agent 历史数据压缩
- 预警Agent 院感风险识别
- 审核Agent 减少误报
  核心策略：滚动摘要
  summary_new =
      summarize(
      old_summary + new_data
  )

院感规则库示例：
- VAP（呼吸机相关肺炎）

Java代码架构
infection-ai
│
├── scheduler
│    └── InfectionMonitorScheduler   定时任务入口
│
├── pipeline
│    └── InfectionPipeline           院感分析流程
│
├── agent
│    ├── FormatAgent
│    ├── SummaryAgent
│    ├── WarningAgent
│    └── AuditAgent
│
├── rule
│    ├── RuleEngine
│    ├── VapRule
│    ├── ClabsiRule
│    └── CautiRule
│
├── service
│    ├── PatientService
│    └── AlertService
│
├── tool
│    ├── LoadDataTool
│    └── DataHandlerTool
│
└── model
├── PatientContext
├── PatientSummary
├── RuleResult
└── InfectionAlert

数据库设计
- 患者原始数据：patient_raw_data
  id
  reqno
  data_json
  create_time
  last_time
- 患者摘要：patient_summary
  id
  reqno
  summary_json
  token_count
  update_time
- 院感预警：infection_alert
  id
  reqno
  risk_level
  infection_type
  evidence
  alert_time
  status
- 院感审核：infection_review
  id
  alert_id
  reqno
  final_alert
  confidence
  review_comment
  create_time

流程（定时扫描患者）：
for patient in hospital:

    raw_data = load_data(patient)

    clean_data = data_handler(raw_data)

    context = format_agent(clean_data)

    summary = summary_agent(
        previous_summary,
        context
    )

    rule_result = rule_engine(summary)

    if rule_result.risk:
        alert = warning_agent(summary)

        review = audit_agent(alert, summary)

    save_result()

提示：
1.基于spring ai与spring ai alibaba，实现以上 功能。
- 使用 Agent Framework 内置的 ReactAgent 抽象快速构建 Agent 应用
- 我的agent是本地部署的Qwen3.5-9B，参考application.yaml中的配置。
2.不要参考com.zzhy.yg_ai.ai中的实现代码，这里是我的测试代码。
3.只完成系统架构功能，不要写具体的实现代码
4.我考虑不周全的地方，帮我补全