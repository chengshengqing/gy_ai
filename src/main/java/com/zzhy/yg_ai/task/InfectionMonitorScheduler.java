package com.zzhy.yg_ai.task;

import com.zzhy.yg_ai.ai.orchestrator.InfectionPipeline;
import com.zzhy.yg_ai.service.PatientService;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class InfectionMonitorScheduler {

    private final PatientService patientService;
    private final InfectionPipeline infectionPipeline;

    public InfectionMonitorScheduler(PatientService patientService,
                                     InfectionPipeline infectionPipeline) {
        this.patientService = patientService;
        this.infectionPipeline = infectionPipeline;
    }

    @Scheduled(fixedDelayString = "${infection.monitor.fixed-delay:60000}")
    public void scanPatients() {
        List<String> reqnos = patientService.listActiveReqnos();
        if (reqnos == null || reqnos.isEmpty()) {
            log.info("院感采集任务本轮无患者");
            return;
        }
        log.info("院感采集任务开始，患者数={}", reqnos.size());
        for (String reqno : reqnos) {
            try {
                infectionPipeline.processPatient(reqno);
            } catch (Exception e) {
                log.error("院感采集任务处理失败，reqno={}", reqno, e);
            }
        }
        log.info("院感采集任务结束，患者数={}", reqnos.size());
    }
}
