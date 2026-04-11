package com.zzhy.yg_ai.pipeline.handler;

import com.zzhy.yg_ai.domain.entity.PatientRawDataChangeTaskEntity;
import com.zzhy.yg_ai.domain.entity.PatientRawDataEntity;
import com.zzhy.yg_ai.domain.enums.InfectionJobStage;
import com.zzhy.yg_ai.domain.enums.InfectionJobStatus;
import com.zzhy.yg_ai.domain.normalize.facts.DailyIllnessExtractionResult;
import com.zzhy.yg_ai.pipeline.model.NormalizeResult;
import com.zzhy.yg_ai.service.InfectionDailyJobLogService;
import com.zzhy.yg_ai.service.PatientService;
import com.zzhy.yg_ai.service.PatientRawDataChangeTaskService;
import com.zzhy.yg_ai.service.normalize.NormalizeStructDataService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Slf4j
@Component
@RequiredArgsConstructor
public class NormalizeHandler extends AbstractTaskHandler<List<PatientRawDataChangeTaskEntity>, NormalizeResult> {

    private static final String TASK_NAME = "结构化处理任务";
    private static final String RETRY_MESSAGE = "存在未完成的 rawData 行，需重试";

    private final NormalizeStructDataService normalizeStructDataService;
    private final PatientService patientService;
    private final PatientRawDataChangeTaskService patientRawDataChangeTaskService;
    private final InfectionDailyJobLogService infectionDailyJobLogService;

    @Override
    protected NormalizeResult process(List<PatientRawDataChangeTaskEntity> taskGroup) {
        return processTaskGroup(taskGroup);
    }

    @Override
    protected void afterHandle(NormalizeResult result) {
        finalizeTask(result);
    }

    private NormalizeResult processTaskGroup(List<PatientRawDataChangeTaskEntity> taskGroup) {
        List<Long> taskIds = extractTaskIds(taskGroup);
        String reqno = resolveReqno(taskGroup);
        if (!StringUtils.hasText(reqno)) {
            return new NormalizeResult(taskIds, reqno, 0, 0, Math.max(1, taskIds.size()), "reqno为空", InfectionJobStage.NORMALIZE);
        }

        List<PatientRawDataEntity> rows = collectFreshRawData(taskGroup);
        if (rows.isEmpty()) {
            return new NormalizeResult(taskIds, reqno, 0, 0, 0, null, InfectionJobStage.NORMALIZE);
        }

        int successCount = 0;
        int failedCount = 0;
        String lastErrorMessage = null;
        InfectionJobStage failedStage = InfectionJobStage.NORMALIZE;
        for (PatientRawDataEntity rawData : rows) {
            NormalizeRowProcessResult rowResult = processRow(rawData);
            if (!rowResult.isSuccess()) {
                failedCount++;
                lastErrorMessage = StringUtils.hasText(rowResult.errorMessage())
                        ? rowResult.errorMessage()
                        : RETRY_MESSAGE;
                failedStage = rowResult.failedStage() == null ? InfectionJobStage.NORMALIZE : rowResult.failedStage();
                continue;
            }
            successCount++;
        }

        return new NormalizeResult(taskIds, reqno, rows.size(), successCount, failedCount, lastErrorMessage, failedStage);
    }

    private List<Long> extractTaskIds(List<PatientRawDataChangeTaskEntity> taskGroup) {
        List<Long> taskIds = new ArrayList<>();
        if (taskGroup == null || taskGroup.isEmpty()) {
            return taskIds;
        }
        for (PatientRawDataChangeTaskEntity taskEntity : taskGroup) {
            if (taskEntity != null && taskEntity.getId() != null) {
                taskIds.add(taskEntity.getId());
            }
        }
        return taskIds;
    }

    private String resolveReqno(List<PatientRawDataChangeTaskEntity> taskGroup) {
        if (taskGroup == null || taskGroup.isEmpty()) {
            return null;
        }
        for (PatientRawDataChangeTaskEntity taskEntity : taskGroup) {
            if (taskEntity != null && StringUtils.hasText(taskEntity.getReqno())) {
                return taskEntity.getReqno().trim();
            }
        }
        return null;
    }

    private List<PatientRawDataEntity> collectFreshRawData(List<PatientRawDataChangeTaskEntity> taskGroup) {
        Map<String, PatientRawDataEntity> rows = new LinkedHashMap<>();
        if (taskGroup == null || taskGroup.isEmpty()) {
            return List.of();
        }
        for (PatientRawDataChangeTaskEntity taskEntity : taskGroup) {
            if (taskEntity == null || taskEntity.getPatientRawDataId() == null || taskEntity.getRawDataLastTime() == null) {
                continue;
            }
            PatientRawDataEntity rawData = patientService.getRawDataById(taskEntity.getPatientRawDataId());
            if (rawData == null || rawData.getLastTime() == null) {
                continue;
            }
            if (!rawData.getLastTime().equals(taskEntity.getRawDataLastTime())) {
                continue;
            }
            rows.putIfAbsent(rawData.getId() + "|" + taskEntity.getRawDataLastTime(), rawData);
        }
        return new ArrayList<>(rows.values());
    }

    private NormalizeRowProcessResult processRow(PatientRawDataEntity rawData) {
        if (rawData == null || !StringUtils.hasText(rawData.getDataJson())) {
            return NormalizeRowProcessResult.failed(InfectionJobStage.NORMALIZE, "rawData为空或缺少dataJson");
        }

        try {
            DailyIllnessExtractionResult dailyIllnessResult = normalizeStructDataService.compose(rawData);
            rawData.setStructDataJson(dailyIllnessResult.structDataJson());
            rawData.setEventJson(dailyIllnessResult.dailySummaryJson());
            patientService.saveStructDataJson(
                    rawData.getId(),
                    dailyIllnessResult.structDataJson(),
                    dailyIllnessResult.dailySummaryJson()
            );
            return NormalizeRowProcessResult.success();
        } catch (Exception e) {
            log.error(buildFailureMessage(
                    TASK_NAME,
                    "rowId", rawData.getId(),
                    "reqno", rawData.getReqno(),
                    "message", RETRY_MESSAGE
            ), e);
            return NormalizeRowProcessResult.failed(InfectionJobStage.NORMALIZE, RETRY_MESSAGE);
        }
    }

    private void finalizeTask(NormalizeResult result) {
        if (result == null || result.taskIds() == null || result.taskIds().isEmpty()) {
            return;
        }

        if (result.failedCount() > 0) {
            String message = StringUtils.hasText(result.lastErrorMessage())
                    ? result.lastErrorMessage()
                    : "部分结构化处理失败";
            patientRawDataChangeTaskService.markStructFailed(result.taskIds(), message);
            infectionDailyJobLogService.log(result.failedStage(), InfectionJobStatus.ERROR, result.reqno(), message);
            return;
        }

        if (result.totalRows() == 0) {
            patientRawDataChangeTaskService.markStructSkipped(result.taskIds(), "结构化任务版本已过期，跳过");
            log.info("{}跳过，{}", TASK_NAME, buildSummary(
                    "taskId", firstTaskId(result.taskIds()),
                    "reqno", result.reqno(),
                    "totalRows", result.totalRows(),
                    "successCount", result.successCount(),
                    "failedCount", result.failedCount(),
                    "message", "结构化任务版本已过期，跳过"
            ));
            return;
        }

        patientRawDataChangeTaskService.markStructSuccess(
                result.taskIds(),
                "结构化处理成功，successCount=" + result.successCount()
        );
        log.info("{}结束，{}", TASK_NAME, buildSummary(
                "taskId", firstTaskId(result.taskIds()),
                "reqno", result.reqno(),
                "totalRows", result.totalRows(),
                "successCount", result.successCount(),
                "failedCount", result.failedCount(),
                "message", "结构化处理成功，successCount=" + result.successCount()
        ));
    }

    private record NormalizeRowProcessResult(InfectionJobStage failedStage, String errorMessage) {

        private static NormalizeRowProcessResult success() {
            return new NormalizeRowProcessResult(null, null);
        }

        private static NormalizeRowProcessResult failed(InfectionJobStage failedStage, String errorMessage) {
            return new NormalizeRowProcessResult(failedStage, errorMessage);
        }

        private boolean isSuccess() {
            return failedStage == null && !StringUtils.hasText(errorMessage);
        }
    }
}
