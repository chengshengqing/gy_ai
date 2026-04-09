package com.zzhy.yg_ai.pipeline.stage;

import com.zzhy.yg_ai.pipeline.handler.LoadEnqueueHandler;
import com.zzhy.yg_ai.pipeline.scheduler.executor.WorkUnitExecutor;
import com.zzhy.yg_ai.pipeline.scheduler.policy.PipelineStage;
import com.zzhy.yg_ai.pipeline.scheduler.policy.StagePolicy;
import com.zzhy.yg_ai.pipeline.scheduler.policy.StagePolicyRegistry;
import com.zzhy.yg_ai.pipeline.scheduler.runtime.StageRuntimeRegistry;
import org.springframework.stereotype.Component;

@Component
public class LoadEnqueueCoordinator extends AbstractStageCoordinator {

    private final LoadEnqueueHandler loadEnqueueHandler;

    public LoadEnqueueCoordinator(StagePolicyRegistry stagePolicyRegistry,
                                  StageRuntimeRegistry stageRuntimeRegistry,
                                  WorkUnitExecutor workUnitExecutor,
                                  LoadEnqueueHandler loadEnqueueHandler) {
        super(stagePolicyRegistry, stageRuntimeRegistry, workUnitExecutor);
        this.loadEnqueueHandler = loadEnqueueHandler;
    }

    @Override
    public PipelineStage stage() {
        return PipelineStage.LOAD_ENQUEUE;
    }

    @Override
    public void dispatchOnce() {
        StagePolicy policy = currentPolicy();
        if (policy == null) {
            return;
        }
        if (availableSlots(policy) <= 0) {
            return;
        }
        clearTriggered();
        submitStageWork("load-enqueue", policy, loadEnqueueHandler::handle);
    }
}
