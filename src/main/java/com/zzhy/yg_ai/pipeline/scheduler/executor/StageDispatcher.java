package com.zzhy.yg_ai.pipeline.scheduler.executor;

import com.zzhy.yg_ai.pipeline.scheduler.policy.PipelineStage;
import com.zzhy.yg_ai.pipeline.scheduler.policy.StagePolicy;
import com.zzhy.yg_ai.pipeline.scheduler.policy.StagePolicyRegistry;
import com.zzhy.yg_ai.pipeline.scheduler.runtime.StageRuntimeRegistry;
import com.zzhy.yg_ai.pipeline.scheduler.runtime.StageRuntimeRegistry.StageRuntimeState;
import com.zzhy.yg_ai.pipeline.stage.StageCoordinator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class StageDispatcher {

    private final StageRuntimeRegistry stageRuntimeRegistry;
    private final StagePolicyRegistry stagePolicyRegistry;
    private final WorkUnitExecutor workUnitExecutor;
    private final Map<PipelineStage, StageCoordinator> coordinators;

    public StageDispatcher(StageRuntimeRegistry stageRuntimeRegistry,
                           StagePolicyRegistry stagePolicyRegistry,
                           WorkUnitExecutor workUnitExecutor,
                           List<StageCoordinator> stageCoordinators) {
        this.stageRuntimeRegistry = stageRuntimeRegistry;
        this.stagePolicyRegistry = stagePolicyRegistry;
        this.workUnitExecutor = workUnitExecutor;
        EnumMap<PipelineStage, StageCoordinator> mapping = new EnumMap<>(PipelineStage.class);
        for (StageCoordinator coordinator : stageCoordinators) {
            StageCoordinator previous = mapping.put(coordinator.stage(), coordinator);
            if (previous != null) {
                throw new IllegalStateException("Duplicate stage coordinator registered for " + coordinator.stage());
            }
        }
        this.coordinators = Map.copyOf(mapping);
    }

    public void trigger(PipelineStage stage) {
        StageRuntimeState runtimeState = stageRuntimeRegistry.get(stage);
        runtimeState.markTriggered();
        tryScheduleCoordinator(stage);
    }

    public void tryScheduleCoordinator(PipelineStage stage) {
        StageRuntimeState runtimeState = stageRuntimeRegistry.get(stage);
        StagePolicy policy = stagePolicyRegistry.getPolicy(stage);
        if (runtimeState == null || policy == null) {
            log.warn("阶段调度失败，未找到 runtime 或 policy，stage={}", stage);
            return;
        }
        if (runtimeState.currentInFlight() >= policy.maxInFlight()) {
            return;
        }
        if (!runtimeState.tryMarkCoordinatorScheduled()) {
            return;
        }
        StageCoordinator coordinator = coordinators.get(stage);
        if (coordinator == null) {
            runtimeState.markCoordinatorFinished();
            log.warn("阶段调度失败，未找到 coordinator，stage={}", stage);
            return;
        }
        workUnitExecutor.submit(new WorkUnit(
                "coordinator:" + stage.name() + ":" + runtimeState.lastSubmitEpochMillis(),
                stage,
                policy.priority(),
                () -> {
                    try {
                        coordinator.dispatchOnce();
                    } finally {
                        runtimeState.markCoordinatorFinished();
                        if (runtimeState.hasPendingTrigger() && runtimeState.currentInFlight() < policy.maxInFlight()) {
                            tryScheduleCoordinator(stage);
                        }
                    }
                },
                false
        ));
    }

    public void onWorkUnitCompleted(PipelineStage stage) {
        StageRuntimeState runtimeState = stageRuntimeRegistry.get(stage);
        StagePolicy policy = stagePolicyRegistry.getPolicy(stage);
        if (runtimeState == null || policy == null) {
            return;
        }
        runtimeState.decrementInFlight();
        if (runtimeState.hasPendingTrigger() && runtimeState.currentInFlight() < policy.maxInFlight()) {
            tryScheduleCoordinator(stage);
        }
    }
}
