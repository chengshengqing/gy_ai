package com.zzhy.yg_ai.pipeline.facade;

import com.zzhy.yg_ai.pipeline.scheduler.executor.StageDispatcher;
import com.zzhy.yg_ai.pipeline.scheduler.policy.PipelineStage;
import org.springframework.stereotype.Component;

@Component
public class InfectionPipelineFacade {

    private final StageDispatcher stageDispatcher;

    public InfectionPipelineFacade(StageDispatcher stageDispatcher) {
        this.stageDispatcher = stageDispatcher;
    }

    public void triggerLoadEnqueue() {
        stageDispatcher.trigger(PipelineStage.LOAD_ENQUEUE);
    }

    public void triggerLoadProcess() {
        stageDispatcher.trigger(PipelineStage.LOAD_PROCESS);
    }

    public void triggerNormalize() {
        stageDispatcher.trigger(PipelineStage.NORMALIZE);
    }

    public void triggerEventExtract() {
        stageDispatcher.trigger(PipelineStage.EVENT_EXTRACT);
    }

    public void triggerCaseRecompute() {
        stageDispatcher.trigger(PipelineStage.CASE_RECOMPUTE);
    }
}
