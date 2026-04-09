package com.zzhy.yg_ai.pipeline.stage;

import com.zzhy.yg_ai.domain.entity.PatientRawDataCollectTaskEntity;
import com.zzhy.yg_ai.pipeline.handler.LoadProcessHandler;
import com.zzhy.yg_ai.pipeline.scheduler.executor.WorkUnitExecutor;
import com.zzhy.yg_ai.pipeline.scheduler.policy.PipelineStage;
import com.zzhy.yg_ai.pipeline.scheduler.policy.StagePolicyRegistry;
import com.zzhy.yg_ai.pipeline.scheduler.runtime.StageRuntimeRegistry;
import com.zzhy.yg_ai.service.PatientRawDataCollectTaskService;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class LoadProcessCoordinator
        extends AbstractSingleItemStageCoordinator<PatientRawDataCollectTaskEntity> {

    private final PatientRawDataCollectTaskService patientRawDataCollectTaskService;
    private final LoadProcessHandler loadProcessHandler;

    public LoadProcessCoordinator(StagePolicyRegistry stagePolicyRegistry,
                                  StageRuntimeRegistry stageRuntimeRegistry,
                                  WorkUnitExecutor workUnitExecutor,
                                  PatientRawDataCollectTaskService patientRawDataCollectTaskService,
                                  LoadProcessHandler loadProcessHandler) {
        super(stagePolicyRegistry, stageRuntimeRegistry, workUnitExecutor);
        this.patientRawDataCollectTaskService = patientRawDataCollectTaskService;
        this.loadProcessHandler = loadProcessHandler;
    }

    @Override
    public PipelineStage stage() {
        return PipelineStage.LOAD_PROCESS;
    }

    @Override
    protected List<PatientRawDataCollectTaskEntity> claimItems(int claimLimit) {
        return patientRawDataCollectTaskService.claimPendingTasks(claimLimit);
    }

    @Override
    protected Long workItemId(PatientRawDataCollectTaskEntity workItem) {
        return workItem == null ? null : workItem.getId();
    }

    @Override
    protected String unitPrefix() {
        return "load-process:";
    }

    @Override
    protected void handleSingleWorkItem(PatientRawDataCollectTaskEntity workItem) {
        loadProcessHandler.handle(workItem);
    }
}
