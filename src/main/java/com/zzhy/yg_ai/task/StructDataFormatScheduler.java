package com.zzhy.yg_ai.task;

import com.zzhy.yg_ai.ai.orchestrator.InfectionPipeline;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class StructDataFormatScheduler {

    private final InfectionPipeline infectionPipeline;

    public StructDataFormatScheduler(InfectionPipeline infectionPipeline) {
        this.infectionPipeline = infectionPipeline;
    }

    @Scheduled(fixedDelayString = "${infection.format.fixed-delay:30000}")
    public void formatPendingStructData() {
        try {
            int formattedCount = infectionPipeline.processPendingStructData();
            if (formattedCount > 0) {
                log.info("结构化格式化定时任务完成，formattedCount={}", formattedCount);
            }
        } catch (Exception e) {
            log.error("结构化格式化定时任务执行失败", e);
        }
    }
}
