package com.zzhy.yg_ai.pipeline.stage;

import com.zzhy.yg_ai.pipeline.scheduler.executor.WorkUnitExecutor;
import com.zzhy.yg_ai.pipeline.scheduler.policy.StagePolicy;
import com.zzhy.yg_ai.pipeline.scheduler.policy.StagePolicyRegistry;
import com.zzhy.yg_ai.pipeline.scheduler.runtime.StageRuntimeRegistry;
import java.util.List;

public abstract class AbstractClaimingStageCoordinator<C, W> extends AbstractStageCoordinator {

    protected AbstractClaimingStageCoordinator(StagePolicyRegistry stagePolicyRegistry,
                                               StageRuntimeRegistry stageRuntimeRegistry,
                                               WorkUnitExecutor workUnitExecutor) {
        super(stagePolicyRegistry, stageRuntimeRegistry, workUnitExecutor);
    }

    @Override
    public final void dispatchOnce() {
        StagePolicy policy = currentPolicy();
        if (policy == null) {
            return;
        }

        int availableSlots = availableSlots(policy);
        if (availableSlots <= 0) {
            return;
        }

        int claimLimit = resolveClaimLimit(policy, availableSlots);
        List<C> claimedItems = claimItems(claimLimit);
        if (claimedItems == null || claimedItems.isEmpty()) {
            clearTriggered();
            return;
        }

        List<W> workItems = buildWorkItems(claimedItems);
        if (workItems == null || workItems.isEmpty()) {
            clearTriggered();
            return;
        }

        updateTriggerState(workItems.size(), claimLimit);
        for (W workItem : workItems) {
            if (!isValidWorkItem(workItem)) {
                continue;
            }
            submitStageWork(unitId(workItem), policy, () -> handleWorkItem(workItem));
        }
    }

    protected int resolveClaimLimit(StagePolicy policy, int availableSlots) {
        int configuredBatchSize = configuredBatchSize(policy);
        return Math.max(1, Math.min(availableSlots, configuredBatchSize));
    }

    protected int configuredBatchSize(StagePolicy policy) {
        return policy.batchDispatchSize() <= 0 ? 1 : policy.batchDispatchSize();
    }

    protected abstract List<C> claimItems(int claimLimit);

    protected abstract List<W> buildWorkItems(List<C> claimedItems);

    protected boolean isValidWorkItem(W workItem) {
        return workItem != null;
    }

    protected abstract String unitId(W workItem);

    protected abstract void handleWorkItem(W workItem);
}
