package com.zzhy.yg_ai.pipeline.stage;

import com.zzhy.yg_ai.pipeline.scheduler.executor.WorkUnitExecutor;
import com.zzhy.yg_ai.pipeline.scheduler.policy.StagePolicyRegistry;
import com.zzhy.yg_ai.pipeline.scheduler.runtime.StageRuntimeRegistry;
import java.util.List;

public abstract class AbstractSingleItemStageCoordinator<T> extends AbstractClaimingStageCoordinator<T, T> {

    protected AbstractSingleItemStageCoordinator(StagePolicyRegistry stagePolicyRegistry,
                                                 StageRuntimeRegistry stageRuntimeRegistry,
                                                 WorkUnitExecutor workUnitExecutor) {
        super(stagePolicyRegistry, stageRuntimeRegistry, workUnitExecutor);
    }

    @Override
    protected final List<T> buildWorkItems(List<T> claimedItems) {
        return claimedItems == null ? List.of() : List.copyOf(claimedItems);
    }

    @Override
    protected final boolean isValidWorkItem(T workItem) {
        return workItemId(workItem) != null;
    }

    @Override
    protected final String unitId(T workItem) {
        return unitPrefix() + workItemId(workItem);
    }

    @Override
    protected final void handleWorkItem(T workItem) {
        handleSingleWorkItem(workItem);
    }

    protected abstract Long workItemId(T workItem);

    protected abstract String unitPrefix();

    protected abstract void handleSingleWorkItem(T workItem);
}
