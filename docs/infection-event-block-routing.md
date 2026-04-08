# 事件抽取 Block 路由策略

## 1. 目的

本文档说明 `EVENT_EXTRACT` 任务在当前实现中，如何根据 `infection_event_task.trigger_reason_codes` 与 `infection_event_task.changed_types` 选择需要执行的 EvidenceBlock。

该策略用于减少无效 LLM 调用，同时保留必要上下文。

## 2. 当前原则

1. `timelineContext` 始终可作为上下文存在，但不会单独执行事件抽取。
2. `structuredFactBlocks` 可以只作为上下文存在，不一定进入本次 primaryBlocks 执行集合。
3. `trigger_reason_codes` 作为主路由依据，`changed_types` 作为补充兜底与收窄依据。
4. Block 选择只影响“是否执行抽取”，不影响 `buildBlocks` 本身的构建结果。

## 3. 当前路由规则

### 3.1 `STRUCTURED_FACT`

以下任一触发原因或变化类型存在时执行：

- `LAB_RESULT_CHANGED`
- `MICROBE_CHANGED`
- `IMAGING_CHANGED`
- `ANTIBIOTIC_OR_ORDER_CHANGED`
- `OPERATION_CHANGED`
- `TRANSFER_CHANGED`
- `VITAL_SIGN_CHANGED`
- `FULL_PATIENT`
- `DIAGNOSIS`
- `BODY_SURFACE`
- `DOCTOR_ADVICE`
- `LAB_TEST`
- `USE_MEDICINE`
- `VIDEO_RESULT`
- `TRANSFER`
- `OPERATION`
- `MICROBE`

说明：

- 这些变化直接对应结构化事实层
- 当只有 `ILLNESS_COURSE_CHANGED` 时，不执行 `STRUCTURED_FACT` 抽取

### 3.2 `CLINICAL_TEXT`

以下触发原因或变化类型存在时执行：

- `ILLNESS_COURSE_CHANGED`
- `FULL_PATIENT`
- `ILLNESS_COURSE`

说明：

- 当前病程文本判断语义只由病程变化触发

### 3.3 `MID_SEMANTIC`

以下任一触发原因或变化类型存在时执行：

- `ILLNESS_COURSE_CHANGED`
- `ANTIBIOTIC_OR_ORDER_CHANGED`
- `OPERATION_CHANGED`
- `TRANSFER_CHANGED`
- `FULL_PATIENT`
- `ILLNESS_COURSE`
- `DOCTOR_ADVICE`
- `USE_MEDICINE`
- `TRANSFER`
- `OPERATION`

说明：

- `MID_SEMANTIC` 当前主要承载感染问题、待排感染、风险项
- 因此它既可能受病程变化影响，也可能受操作/转运/医嘱风险变化影响

## 4. 无路由字段的兜底

当 `trigger_reason_codes` 与 `changed_types` 同时为空或不可解析时：

- 回退为执行全部 primaryBlocks

这是一条开发期安全兜底，避免因任务字段缺失导致整条抽取链路静默不执行。

## 5. 说明

当前实现采用“并集路由”：

- 任一字段命中即执行对应 block
- 若两套字段都缺失，则回退为全量 primaryBlocks
- 若解析后没有任何 block 被选中，也回退为全量 primaryBlocks，避免静默漏跑

该策略仍是保守节流版本，后续如果需要进一步压缩调用，可再引入更细粒度的 section 级路由。
