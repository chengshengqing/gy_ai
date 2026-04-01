package com.zzhy.yg_ai.task;

import com.zzhy.yg_ai.ai.orchestrator.InfectionPipeline;
import com.zzhy.yg_ai.domain.enums.InfectionJobStage;
import com.zzhy.yg_ai.domain.enums.InfectionJobStatus;
import com.zzhy.yg_ai.service.InfectionDailyJobLogService;
import com.zzhy.yg_ai.service.PatientRawDataChangeTaskService;
import com.zzhy.yg_ai.service.PatientService;
import java.time.LocalDateTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class StructDataFormatScheduler {

    private final InfectionPipeline infectionPipeline;
    private final InfectionDailyJobLogService infectionDailyJobLogService;
    private final PatientService patientService;
    private final PatientRawDataChangeTaskService patientRawDataChangeTaskService;

    public StructDataFormatScheduler(InfectionPipeline infectionPipeline,
                                     InfectionDailyJobLogService infectionDailyJobLogService,
                                     PatientService patientService,
                                     PatientRawDataChangeTaskService patientRawDataChangeTaskService) {
        this.infectionPipeline = infectionPipeline;
        this.infectionDailyJobLogService = infectionDailyJobLogService;
        this.patientService = patientService;
        this.patientRawDataChangeTaskService = patientRawDataChangeTaskService;
    }

    @Scheduled(fixedDelayString = "${infection.format.fixed-delay:30000}")
    public void formatPendingStructData() {
        try {
            repairMissingStructTasks();
            int formattedCount = infectionPipeline.processPendingStructData();
            if (formattedCount <= 0) {
                infectionDailyJobLogService.log(InfectionJobStage.NORMALIZE,
                        InfectionJobStatus.SKIP,
                        null,
                        "本轮无待处理结构化任务");
                return;
            }
            log.info("结构化格式化定时任务完成，formattedCount={}", formattedCount);
            infectionDailyJobLogService.log(InfectionJobStage.NORMALIZE,
                    InfectionJobStatus.SUCCESS,
                    null,
                    "formattedCount=" + formattedCount);
        } catch (Exception e) {
            log.error("结构化格式化定时任务执行失败", e);
            infectionDailyJobLogService.log(InfectionJobStage.NORMALIZE,
                    InfectionJobStatus.ERROR,
                    null,
                    "结构化格式化定时任务执行失败: " + e.getMessage());
        }
    }

    private void repairMissingStructTasks() {
        List<String> reqnos = patientService.listActiveReqnos();
        if (reqnos == null || reqnos.isEmpty()) {
            return;
        }
        int repaired = patientRawDataChangeTaskService.repairMissingStructTasks(
                reqnos,
                LocalDateTime.now().minusDays(90),
                Math.max(20, reqnos.size() * 2));
        if (repaired > 0) {
            log.warn("补建缺失结构化任务完成，repaired={}", repaired);
        }
    }
}
