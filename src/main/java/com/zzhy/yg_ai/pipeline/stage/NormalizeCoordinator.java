package com.zzhy.yg_ai.pipeline.stage;

import com.zzhy.yg_ai.domain.entity.PatientRawDataChangeTaskEntity;
import com.zzhy.yg_ai.pipeline.handler.NormalizeHandler;
import com.zzhy.yg_ai.pipeline.scheduler.executor.WorkUnitExecutor;
import com.zzhy.yg_ai.pipeline.scheduler.policy.PipelineStage;
import com.zzhy.yg_ai.pipeline.scheduler.policy.StagePolicyRegistry;
import com.zzhy.yg_ai.pipeline.scheduler.runtime.StageRuntimeRegistry;
import com.zzhy.yg_ai.service.PatientRawDataChangeTaskService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class NormalizeCoordinator
        extends AbstractClaimingStageCoordinator<PatientRawDataChangeTaskEntity, List<PatientRawDataChangeTaskEntity>> {

    private final PatientRawDataChangeTaskService patientRawDataChangeTaskService;
    private final NormalizeHandler normalizeHandler;

    public NormalizeCoordinator(StagePolicyRegistry stagePolicyRegistry,
                                StageRuntimeRegistry stageRuntimeRegistry,
                                WorkUnitExecutor workUnitExecutor,
                                PatientRawDataChangeTaskService patientRawDataChangeTaskService,
                                NormalizeHandler normalizeHandler) {
        super(stagePolicyRegistry, stageRuntimeRegistry, workUnitExecutor);
        this.patientRawDataChangeTaskService = patientRawDataChangeTaskService;
        this.normalizeHandler = normalizeHandler;
    }

    @Override
    public PipelineStage stage() {
        return PipelineStage.NORMALIZE;
    }

    @Override
    protected List<PatientRawDataChangeTaskEntity> claimItems(int claimLimit) {
        return patientRawDataChangeTaskService.claimPendingStructTasks(claimLimit);
    }

    @Override
    protected List<List<PatientRawDataChangeTaskEntity>> buildWorkItems(List<PatientRawDataChangeTaskEntity> claimedItems) {
        Map<String, List<PatientRawDataChangeTaskEntity>> tasksByReqno = groupByReqno(claimedItems);
        List<List<PatientRawDataChangeTaskEntity>> workItems = new ArrayList<>();
        for (List<PatientRawDataChangeTaskEntity> taskGroup : tasksByReqno.values()) {
            if (taskGroup != null && !taskGroup.isEmpty()) {
                workItems.add(List.copyOf(taskGroup));
            }
        }
        return workItems;
    }

    private Map<String, List<PatientRawDataChangeTaskEntity>> groupByReqno(List<PatientRawDataChangeTaskEntity> claimedTasks) {
        Map<String, List<PatientRawDataChangeTaskEntity>> tasksByReqno = new LinkedHashMap<>();
        for (PatientRawDataChangeTaskEntity claimedTask : claimedTasks) {
            if (claimedTask == null || !StringUtils.hasText(claimedTask.getReqno())) {
                continue;
            }
            tasksByReqno.computeIfAbsent(claimedTask.getReqno().trim(), key -> new ArrayList<>()).add(claimedTask);
        }
        return tasksByReqno;
    }

    @Override
    protected boolean isValidWorkItem(List<PatientRawDataChangeTaskEntity> workItem) {
        return workItem != null && !workItem.isEmpty();
    }

    @Override
    protected String unitId(List<PatientRawDataChangeTaskEntity> workItem) {
        String reqno = workItem.get(0).getReqno();
        return "normalize:" + buildUnitKey(reqno, workItem);
    }

    private String buildUnitKey(String reqno, List<PatientRawDataChangeTaskEntity> taskGroup) {
        if (StringUtils.hasText(reqno)) {
            return reqno;
        }
        PatientRawDataChangeTaskEntity firstTask = taskGroup.get(0);
        return firstTask.getId() == null ? "unknown" : String.valueOf(firstTask.getId());
    }

    @Override
    protected void handleWorkItem(List<PatientRawDataChangeTaskEntity> workItem) {
        normalizeHandler.handle(workItem);
    }
}
