package com.zzhy.yg_ai.pipeline.stage;

import com.zzhy.yg_ai.domain.entity.InfectionEventTaskEntity;
import com.zzhy.yg_ai.domain.enums.InfectionEventTaskType;
import com.zzhy.yg_ai.pipeline.handler.EventExtractHandler;
import com.zzhy.yg_ai.pipeline.scheduler.executor.WorkUnitExecutor;
import com.zzhy.yg_ai.pipeline.scheduler.policy.PipelineStage;
import com.zzhy.yg_ai.pipeline.scheduler.policy.StagePolicyRegistry;
import com.zzhy.yg_ai.pipeline.scheduler.runtime.StageRuntimeRegistry;
import com.zzhy.yg_ai.service.InfectionEventTaskService;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class EventExtractCoordinator extends AbstractSingleItemStageCoordinator<InfectionEventTaskEntity> {

    private final InfectionEventTaskService infectionEventTaskService;
    private final EventExtractHandler eventExtractHandler;

    public EventExtractCoordinator(StagePolicyRegistry stagePolicyRegistry,
                                   StageRuntimeRegistry stageRuntimeRegistry,
                                   WorkUnitExecutor workUnitExecutor,
                                   InfectionEventTaskService infectionEventTaskService,
                                   EventExtractHandler eventExtractHandler) {
        super(stagePolicyRegistry, stageRuntimeRegistry, workUnitExecutor);
        this.infectionEventTaskService = infectionEventTaskService;
        this.eventExtractHandler = eventExtractHandler;
    }

    @Override
    public PipelineStage stage() {
        return PipelineStage.EVENT_EXTRACT;
    }

    @Override
    protected List<InfectionEventTaskEntity> claimItems(int claimLimit) {
        return infectionEventTaskService.claimPendingTasks(InfectionEventTaskType.EVENT_EXTRACT, claimLimit);
    }

    @Override
    protected Long workItemId(InfectionEventTaskEntity workItem) {
        return workItem == null ? null : workItem.getId();
    }

    @Override
    protected String unitPrefix() {
        return "event-extract:";
    }

    @Override
    protected void handleSingleWorkItem(InfectionEventTaskEntity workItem) {
        eventExtractHandler.handle(workItem);
    }
}
