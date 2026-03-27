package com.zzhy.yg_ai.task;

import com.zzhy.yg_ai.ai.orchestrator.InfectionPipeline;
import com.zzhy.yg_ai.domain.enums.InfectionJobStage;
import com.zzhy.yg_ai.domain.enums.InfectionJobStatus;
import com.zzhy.yg_ai.service.InfectionDailyJobLogService;
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
    private final InfectionDailyJobLogService infectionDailyJobLogService;

    public InfectionMonitorScheduler(PatientService patientService,
                                     InfectionPipeline infectionPipeline,
                                     InfectionDailyJobLogService infectionDailyJobLogService) {
        this.patientService = patientService;
        this.infectionPipeline = infectionPipeline;
        this.infectionDailyJobLogService = infectionDailyJobLogService;
    }

    @Scheduled(fixedDelayString = "${infection.monitor.enqueue-fixed-delay:${infection.monitor.fixed-delay:60000}}")
    public void enqueuePendingPatients() {
        try {
            List<String> reqnos = patientService.listActiveReqnos();
            if (reqnos == null || reqnos.isEmpty()) {
                log.info("院感采集扫描任务本轮无患者");
                infectionDailyJobLogService.log(InfectionJobStage.LOAD, InfectionJobStatus.SKIP, null, "采集扫描阶段无可入队患者");
                return;
            }
            int enqueuedCount = infectionPipeline.enqueueRawDataTasks(reqnos);
            log.info("院感采集扫描任务结束，patients={}, enqueuedCount={}", reqnos.size(), enqueuedCount);
            infectionDailyJobLogService.log(InfectionJobStage.LOAD,
                    InfectionJobStatus.SUCCESS,
                    null,
                    "scanPatients=" + reqnos.size() + ", enqueued=" + enqueuedCount);
        } catch (Exception e) {
            log.error("院感采集扫描任务执行失败", e);
            infectionDailyJobLogService.log(InfectionJobStage.LOAD,
                    InfectionJobStatus.ERROR,
                    null,
                    "院感采集扫描任务执行失败: " + e.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${infection.monitor.process-fixed-delay:${infection.monitor.fixed-delay:60000}}")
    public void processPendingCollectTasks() {
        try {
            int processedCount = infectionPipeline.processPendingRawDataTasks();
            if (processedCount <= 0) {
                infectionDailyJobLogService.log(InfectionJobStage.LOAD,
                        InfectionJobStatus.SKIP,
                        null,
                        "采集执行阶段无待处理任务");
                return;
            }
            log.info("院感采集执行任务结束，processedCount={}", processedCount);
            infectionDailyJobLogService.log(InfectionJobStage.LOAD,
                    InfectionJobStatus.SUCCESS,
                    null,
                    "processed=" + processedCount);
        } catch (Exception e) {
            log.error("院感采集执行任务失败", e);
            infectionDailyJobLogService.log(InfectionJobStage.LOAD,
                    InfectionJobStatus.ERROR,
                    null,
                    "院感采集执行任务失败: " + e.getMessage());
        }
    }
}
