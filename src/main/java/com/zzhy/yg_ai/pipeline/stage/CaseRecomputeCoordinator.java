package com.zzhy.yg_ai.pipeline.stage;

import com.zzhy.yg_ai.domain.entity.InfectionEventTaskEntity;
import com.zzhy.yg_ai.domain.enums.InfectionEventTaskType;
import com.zzhy.yg_ai.pipeline.handler.CaseRecomputeHandler;
import com.zzhy.yg_ai.pipeline.scheduler.executor.WorkUnitExecutor;
import com.zzhy.yg_ai.pipeline.scheduler.policy.PipelineStage;
import com.zzhy.yg_ai.pipeline.scheduler.policy.StagePolicyRegistry;
import com.zzhy.yg_ai.pipeline.scheduler.runtime.StageRuntimeRegistry;
import com.zzhy.yg_ai.service.InfectionEventTaskService;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class CaseRecomputeCoordinator extends AbstractSingleItemStageCoordinator<InfectionEventTaskEntity> {

    private final InfectionEventTaskService infectionEventTaskService;
    private final CaseRecomputeHandler caseRecomputeHandler;

    public CaseRecomputeCoordinator(StagePolicyRegistry stagePolicyRegistry,
                                    StageRuntimeRegistry stageRuntimeRegistry,
                                    WorkUnitExecutor workUnitExecutor,
                                    InfectionEventTaskService infectionEventTaskService,
                                    CaseRecomputeHandler caseRecomputeHandler) {
        super(stagePolicyRegistry, stageRuntimeRegistry, workUnitExecutor);
        this.infectionEventTaskService = infectionEventTaskService;
        this.caseRecomputeHandler = caseRecomputeHandler;
    }

    @Override
    public PipelineStage stage() {
        return PipelineStage.CASE_RECOMPUTE;
    }

    @Override
    protected List<InfectionEventTaskEntity> claimItems(int claimLimit) {
        return infectionEventTaskService.claimPendingTasks(InfectionEventTaskType.CASE_RECOMPUTE, claimLimit);
    }

    @Override
    protected Long workItemId(InfectionEventTaskEntity workItem) {
        return workItem == null ? null : workItem.getId();
    }

    @Override
    protected String unitPrefix() {
        return "case-recompute:";
    }

    @Override
    protected void handleSingleWorkItem(InfectionEventTaskEntity workItem) {
        caseRecomputeHandler.handle(workItem);
    }
}
