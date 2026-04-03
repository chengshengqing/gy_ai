package com.zzhy.yg_ai.task;

import com.zzhy.yg_ai.ai.orchestrator.InfectionPipeline;
import com.zzhy.yg_ai.domain.enums.InfectionJobStage;
import com.zzhy.yg_ai.domain.enums.InfectionJobStatus;
import com.zzhy.yg_ai.service.InfectionDailyJobLogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class SummaryWarningScheduler {

    private final InfectionPipeline infectionPipeline;
    private final InfectionDailyJobLogService infectionDailyJobLogService;

    public SummaryWarningScheduler(InfectionPipeline infectionPipeline,
                                   InfectionDailyJobLogService infectionDailyJobLogService) {
        this.infectionPipeline = infectionPipeline;
        this.infectionDailyJobLogService = infectionDailyJobLogService;
    }

    @Scheduled(fixedDelayString = "${infection.warning.fixed-delay:60000}")
    public void processPendingEventTasks() {
        try {
            int extractedCount = infectionPipeline.processPendingEventData();
            if (extractedCount <= 0) {
                return;
            }
            log.info("事件抽取定时任务完成，extractedCount={}", extractedCount);
            infectionDailyJobLogService.log(InfectionJobStage.LLM,
                    InfectionJobStatus.SUCCESS,
                    null,
                    "extractedCount=" + extractedCount);
        } catch (Exception e) {
            log.error("事件抽取定时任务执行失败", e);
            infectionDailyJobLogService.log(InfectionJobStage.LLM,
                    InfectionJobStatus.ERROR,
                    null,
                    "事件抽取定时任务执行失败: " + e.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${infection.warning.case-fixed-delay:${infection.warning.fixed-delay:60000}}")
    public void processPendingCaseTasks() {
        try {
            int processedCount = infectionPipeline.processPendingCaseData();
            if (processedCount <= 0) {
                return;
            }
            log.info("病例重算定时任务完成，processedCount={}", processedCount);
            infectionDailyJobLogService.log(InfectionJobStage.FINALIZE,
                    InfectionJobStatus.SUCCESS,
                    null,
                    "processedCount=" + processedCount);
        } catch (Exception e) {
            log.error("病例重算定时任务执行失败", e);
            infectionDailyJobLogService.log(InfectionJobStage.FINALIZE,
                    InfectionJobStatus.ERROR,
                    null,
                    "病例重算定时任务执行失败: " + e.getMessage());
        }
    }
}
