package com.zzhy.yg_ai.pipeline.handler;

import com.zzhy.yg_ai.domain.enums.InfectionJobStatus;
import com.zzhy.yg_ai.pipeline.model.NormalizeResult;
import com.zzhy.yg_ai.service.InfectionDailyJobLogService;
import com.zzhy.yg_ai.service.PatientRawDataChangeTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Slf4j
@Component
@RequiredArgsConstructor
public class NormalizeResultApplier {

    private final PatientRawDataChangeTaskService patientRawDataChangeTaskService;
    private final InfectionDailyJobLogService infectionDailyJobLogService;

    public void apply(NormalizeResult result) {
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
            return;
        }

        patientRawDataChangeTaskService.markStructSuccess(
                result.taskIds(),
                "结构化处理成功，successCount=" + result.successCount()
        );
        log.info("结构化处理完成，reqno={}, totalRows={}, successCount={}",
                result.reqno(), result.totalRows(), result.successCount());
    }
}
