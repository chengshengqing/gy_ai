package com.zzhy.yg_ai.task;

import com.zzhy.yg_ai.ai.orchestrator.InfectionPipeline;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class SummaryWarningScheduler {

    private final InfectionPipeline infectionPipeline;

    public SummaryWarningScheduler(InfectionPipeline infectionPipeline) {
        this.infectionPipeline = infectionPipeline;
    }

//    @Scheduled(fixedDelayString = "${infection.summary.fixed-delay:60000}")
    public void extractEventsToRedis() {
        try {
            int count = infectionPipeline.extractEventsToRedis(50);
            if (count > 0) {
                log.info("SummaryAgent事件抽取任务完成，count={}", count);
            }
        } catch (Exception e) {
            log.error("SummaryAgent事件抽取任务失败", e);
        }
    }

//    @Scheduled(cron = "${infection.summary.sync.cron:0 0 0 * * ?}")
    public void syncRedisToSummary() {
        try {
            int count = infectionPipeline.syncRedisToSummary();
            if (count > 0) {
                log.info("Redis同步Summary任务完成，count={}", count);
            }
        } catch (Exception e) {
            log.error("Redis同步Summary任务失败", e);
        }
    }

//    @Scheduled(fixedDelayString = "${infection.warning.fixed-delay:120000}")
    public void evaluateWarning() {
        try {
            int count = infectionPipeline.evaluateWarnings();
            if (count > 0) {
                log.info("WarningAgent预警任务完成，count={}", count);
            }
        } catch (Exception e) {
            log.error("WarningAgent预警任务失败", e);
        }
    }
}
