package com.zzhy.yg_ai.task;

import com.zzhy.yg_ai.pipeline.facade.InfectionPipelineFacade;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SummaryWarningScheduler {

    private final InfectionPipelineFacade infectionPipelineFacade;

    @Scheduled(fixedDelayString = "${infection.warning.fixed-delay:60000}")
    public void processPendingEventTasks() {
        infectionPipelineFacade.triggerEventExtract();
    }

    @Scheduled(fixedDelayString = "${infection.warning.case-fixed-delay:${infection.warning.fixed-delay:60000}}")
    public void processPendingCaseTasks() {
        infectionPipelineFacade.triggerCaseRecompute();
    }
}
