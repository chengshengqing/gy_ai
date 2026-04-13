---
name: yg-ai-backend-dev
description: Project-specific backend development workflow for the yg_ai Java 17 Spring Boot system. Use when working in the yg_ai repository or when tasks mention patient_raw_data, infection warning, 院感预警, timeline, Normalize, InfectionPipelineFacade, StageDispatcher, event pool, case snapshot, alert result, Spring AI, MyBatis-Plus, SQL Server, scheduler, LLM JSON output, or related backend development.
---

# yg_ai Backend Development

## Overview

Use this skill as the navigation layer for backend development in `yg_ai`. Treat the repo documentation as the source of truth, identify the affected pipeline stage first, then extend the existing code path without bypassing established service, mapper, AI, and scheduler boundaries.

## First Steps

1. Determine the affected chain before editing code:
   - Raw collection: `LOAD_ENQUEUE` or `LOAD_PROCESS`
   - Daily structure generation: `NORMALIZE`
   - Timeline view: `PatientTimelineViewServiceImpl` and `timeline-view-rules.yaml`
   - Infection event extraction: `EVENT_EXTRACT`
   - Case recompute and judge: `CASE_RECOMPUTE`
   - Infectious syndrome monitoring: `SurveillanceAgent`, `InfectionJudgeServiceImpl`, and supporting services
2. Read the smallest relevant docs set:
   - Overall architecture: `docs/overview/architecture.md`
   - Code path lookup: `docs/code-paths/code-chain-index.md`
   - Scheduling changes: `docs/scheduling/shared-executor-architecture-design.md`
   - Infection warning and judge flow: `docs/infection-warning/infection-warning-design.md`
   - Tables, entities, enums, mapper changes: `docs/data/data-model-design.md`
   - Raw JSON structure: `docs/data/patient-raw-json-structure.md`
3. Inspect the current implementation with fast file search where available. Prefer `rg`; if unavailable, use `find` and targeted reads.

## Development Rules

- Keep controller logic thin. Put orchestration in service classes, persistence in mapper/XML, model calls in `ai` through `AiGateway`, scheduled entry points in `task`, and configuration/rules in `config` or YAML files.
- Do not add a single-implementation interface plus a default implementation. Add abstractions only for a real variation point, an external boundary, or repeated stable logic.
- Do not write direct JDBC in services unless there is a specific reason. Extend existing MyBatis-Plus mapper and XML patterns first.
- Preserve the incremental model: daily collection identifies new, corrected, and revoked changes, then only recomputes affected cases. Do not introduce daily full reruns for infection warning.
- Keep LLM outputs structured and parseable. Avoid renaming existing JSON fields used by `patient_raw_data.struct_data_json`, `patient_raw_data.event_json`, `SurveillanceAgent`, or infection warning validators.
- For timeline view changes, prefer `timeline-view-rules.yaml`. Change Java only when rules cannot express the behavior.
- Treat `WarningAgent`, `SummaryWarningScheduler`, Redis memory, ReactAgent, and final review Agent as reserved or partially implemented unless the task explicitly targets them.
- Do not formally implement the final review Agent. Planning and interface reservation are allowed.

## Pipeline Playbooks

For raw collection:
- Follow `InfectionMonitorScheduler -> InfectionPipelineFacade -> StageDispatcher -> Load*Coordinator -> Load*Handler -> PatientServiceImpl`.
- Keep collection focused on fact updates and downstream task creation.

For `NORMALIZE`:
- Follow `StructDataFormatScheduler -> NormalizeHandler -> NormalizeStructDataService -> NormalizeContextBuilder / NormalizeResultAssembler -> AiGateway`.
- Budget daily fusion input with config fallback. Do not rely only on documented model context size.

For timeline view:
- Follow `PatientTimelineController -> PatientTimelineViewServiceImpl -> patient_raw_data.event_json -> timeline-view-rules.yaml -> PatientTimelineViewData`.
- Preserve the API response shape unless the task explicitly changes the contract.

For infection warning:
- Follow `infection_event_task(EVENT_EXTRACT) -> EventExtractHandler -> InfectionEvidenceBlockService -> LlmEventExtractorService -> EventNormalizerService -> infection_event_pool -> infection_llm_node_run`.
- Then follow `infection_event_task(CASE_RECOMPUTE) -> CaseRecomputeHandler -> InfectionEvidencePacketBuilder -> InfectionJudgeService -> infection_case_snapshot -> infection_alert_result`.
- Prioritize Canonical Schema consistency for `infection_event_pool`, run traceability for `infection_llm_node_run`, current-state semantics for `infection_case_snapshot`, and versioned diff semantics for `infection_alert_result`.
- Do not create `infection_node_result` without a clear query scenario.

## Verification

- After structural refactors, run `./mvnw -q -DskipTests compile` when feasible.
- If validation cannot be run, state why.
- Report whether the change affects scheduled pipeline behavior, database reads/writes, model output structure, API response fields, configuration, or table migration.
- When changing architecture, package structure, or abstraction rules, update the relevant docs: `docs/scheduling/shared-executor-architecture-design.md`, `docs/code-paths/code-chain-index.md`, `README.md`, and `AGENTS.md`.
