package com.zzhy.yg_ai.pipeline.handler;

import com.zzhy.yg_ai.domain.entity.PatientRawDataCollectTaskEntity;
import com.zzhy.yg_ai.domain.enums.InfectionJobStage;
import com.zzhy.yg_ai.domain.enums.InfectionJobStatus;
import com.zzhy.yg_ai.domain.model.RawDataCollectResult;
import com.zzhy.yg_ai.pipeline.model.LoadProcessResult;
import com.zzhy.yg_ai.service.InfectionDailyJobLogService;
import com.zzhy.yg_ai.service.PatientRawDataCollectTaskService;
import com.zzhy.yg_ai.service.PatientService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Slf4j
@Component
@RequiredArgsConstructor
public class LoadProcessHandler extends AbstractTaskHandler<PatientRawDataCollectTaskEntity, LoadProcessResult> {

    private static final String TASK_NAME = "患者原始数据采集任务";

    private final PatientService patientService;
    private final PatientRawDataCollectTaskService patientRawDataCollectTaskService;
    private final InfectionDailyJobLogService infectionDailyJobLogService;

    @Override
    protected LoadProcessResult process(PatientRawDataCollectTaskEntity taskEntity) {
        String reqno = taskEntity == null ? null : taskEntity.getReqno();
        if (taskEntity == null || taskEntity.getId() == null || !StringUtils.hasText(reqno)) {
            return new LoadProcessResult(taskEntity == null ? null : taskEntity.getId(), reqno, false, "reqno为空", null);
        }

        try {
            RawDataCollectResult collectResult = patientService.collectAndSaveRawDataResult(
                    reqno,
                    taskEntity.getPreviousSourceLastTime(),
                    taskEntity.getSourceLastTime()
            );
            boolean success = collectResult != null && collectResult.isSuccessLike();
            return new LoadProcessResult(taskEntity.getId(),
                    reqno,
                    success,
                    collectResult == null ? "采集结果为空" : collectResult.getMessage(),
                    collectResult == null ? null : collectResult.getChangeTypes());
        } catch (Exception e) {
            log.error(buildFailureMessage(TASK_NAME, "reqno", reqno, "message", e.getMessage()), e);
            return new LoadProcessResult(taskEntity.getId(), reqno, false, e.getMessage(), null);
        }
    }

    @Override
    protected void afterHandle(LoadProcessResult result) {
        if (result == null || result.taskId() == null) {
            return;
        }
        if (result.success()) {
            patientRawDataCollectTaskService.markSuccess(result.taskId(), result.message(), result.changeTypes());
            log.info("{}结束，{}", TASK_NAME, buildSummary(
                    "taskId", result.taskId(),
                    "reqno", result.reqno(),
                    "changeTypes", result.changeTypes(),
                    "message", result.message()
            ));
            return;
        }
        patientRawDataCollectTaskService.markFailed(result.taskId(), result.message(), result.changeTypes());
        String errorMessage = buildFailureMessage(TASK_NAME,
                "reqno", result.reqno(),
                "message", result.message());
        infectionDailyJobLogService.log(InfectionJobStage.LOAD,
                InfectionJobStatus.ERROR,
                result.reqno(),
                errorMessage);
    }
}
