package com.zzhy.yg_ai.pipeline.stage;

import com.zzhy.yg_ai.pipeline.scheduler.executor.WorkUnit;
import com.zzhy.yg_ai.pipeline.scheduler.executor.WorkUnitExecutor;
import com.zzhy.yg_ai.pipeline.scheduler.policy.PipelineStage;
import com.zzhy.yg_ai.pipeline.scheduler.policy.StagePolicy;
import com.zzhy.yg_ai.pipeline.scheduler.policy.StagePolicyRegistry;
import com.zzhy.yg_ai.pipeline.scheduler.runtime.StageRuntimeRegistry;
import com.zzhy.yg_ai.pipeline.scheduler.runtime.StageRuntimeRegistry.StageRuntimeState;

public abstract class AbstractStageCoordinator implements StageCoordinator {

    private final StagePolicyRegistry stagePolicyRegistry;
    private final StageRuntimeRegistry stageRuntimeRegistry;
    private final WorkUnitExecutor workUnitExecutor;

    protected AbstractStageCoordinator(StagePolicyRegistry stagePolicyRegistry,
                                       StageRuntimeRegistry stageRuntimeRegistry,
                                       WorkUnitExecutor workUnitExecutor) {
        this.stagePolicyRegistry = stagePolicyRegistry;
        this.stageRuntimeRegistry = stageRuntimeRegistry;
        this.workUnitExecutor = workUnitExecutor;
    }

    protected final StagePolicy currentPolicy() {
        return stagePolicyRegistry.getPolicy(stage());
    }

    protected final StageRuntimeState runtimeState() {
        return stageRuntimeRegistry.get(stage());
    }

    protected final int availableSlots(StagePolicy policy) {
        return Math.max(0, policy.maxInFlight() - runtimeState().currentInFlight());
    }

    protected final void clearTriggered() {
        runtimeState().clearTriggered();
    }

    protected final void markTriggered() {
        runtimeState().markTriggered();
    }

    protected final void updateTriggerState(int workItemCount, int claimLimit) {
        if (workItemCount >= claimLimit) {
            markTriggered();
        } else {
            clearTriggered();
        }
    }

    protected final void submitStageWork(String unitId, StagePolicy policy, Runnable action) {
        runtimeState().incrementInFlight();
        workUnitExecutor.submit(new WorkUnit(
                unitId,
                stage(),
                policy.priority(),
                action
        ));
    }

    @Override
    public abstract PipelineStage stage();
}
