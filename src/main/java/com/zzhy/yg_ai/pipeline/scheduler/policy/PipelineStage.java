package com.zzhy.yg_ai.pipeline.scheduler.policy;

public enum PipelineStage {
    LOAD_ENQUEUE,
    LOAD_PROCESS,
    NORMALIZE,
    EVENT_EXTRACT,
    CASE_RECOMPUTE
}
