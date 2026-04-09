package com.zzhy.yg_ai.task;

import com.zzhy.yg_ai.pipeline.facade.InfectionPipelineFacade;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class InfectionMonitorScheduler {

    private final InfectionPipelineFacade infectionPipelineFacade;

    @Scheduled(fixedDelayString = "${infection.monitor.enqueue-fixed-delay:${infection.monitor.fixed-delay:60000}}")
    public void enqueuePendingPatients() {
        infectionPipelineFacade.triggerLoadEnqueue();
    }

    @Scheduled(fixedDelayString = "${infection.monitor.process-fixed-delay:${infection.monitor.fixed-delay:60000}}")
    public void processPendingCollectTasks() {
        infectionPipelineFacade.triggerLoadProcess();
    }
}
