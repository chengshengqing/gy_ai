package com.zzhy.yg_ai.task;

import com.zzhy.yg_ai.pipeline.facade.InfectionPipelineFacade;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class StructDataFormatScheduler {

    private final InfectionPipelineFacade infectionPipelineFacade;

    @Scheduled(fixedDelayString = "${infection.format.fixed-delay:30000}")
    public void formatPendingStructData() {
        infectionPipelineFacade.triggerNormalize();
    }
}
