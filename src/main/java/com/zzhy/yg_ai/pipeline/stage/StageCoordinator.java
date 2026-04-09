package com.zzhy.yg_ai.pipeline.stage;

import com.zzhy.yg_ai.pipeline.scheduler.policy.PipelineStage;

public interface StageCoordinator {

    PipelineStage stage();

    void dispatchOnce();
}
