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
public class StructDataFormatScheduler {

    private final InfectionPipeline infectionPipeline;
    private final InfectionDailyJobLogService infectionDailyJobLogService;

    public StructDataFormatScheduler(InfectionPipeline infectionPipeline,
                                     InfectionDailyJobLogService infectionDailyJobLogService) {
        this.infectionPipeline = infectionPipeline;
        this.infectionDailyJobLogService = infectionDailyJobLogService;
    }

//    @Scheduled(fixedDelayString = "${infection.format.fixed-delay:30000}")
    public void formatPendingStructData() {
        try {
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
}
