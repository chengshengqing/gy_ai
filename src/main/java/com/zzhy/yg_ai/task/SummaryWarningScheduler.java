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

}
