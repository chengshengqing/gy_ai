package com.zzhy.yg_ai.pipeline.scheduler.policy;

public record StagePolicy(
        PipelineStage stage,
        int priority,
        int maxInFlight,
        int batchDispatchSize
) {
}
