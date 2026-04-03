package com.zzhy.yg_ai.task;

import com.zzhy.yg_ai.ai.orchestrator.InfectionPipeline;
import com.zzhy.yg_ai.domain.enums.InfectionJobStage;
import com.zzhy.yg_ai.domain.enums.InfectionJobStatus;
import com.zzhy.yg_ai.service.InfectionDailyJobLogService;
import com.zzhy.yg_ai.service.PatientRawDataCollectTaskService;
import com.zzhy.yg_ai.service.PatientService;
import java.time.LocalDateTime;
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
    private final PatientRawDataCollectTaskService patientRawDataCollectTaskService;

    public InfectionMonitorScheduler(PatientService patientService,
                                     InfectionPipeline infectionPipeline,
                                     InfectionDailyJobLogService infectionDailyJobLogService,
                                     PatientRawDataCollectTaskService patientRawDataCollectTaskService) {
        this.patientService = patientService;
        this.infectionPipeline = infectionPipeline;
        this.infectionDailyJobLogService = infectionDailyJobLogService;
        this.patientRawDataCollectTaskService = patientRawDataCollectTaskService;
    }

    @Scheduled(fixedDelayString = "${infection.monitor.enqueue-fixed-delay:${infection.monitor.fixed-delay:60000}}")
    public void enqueuePendingPatients() {
        try {
            reclaimTimedOutCollectTasks("采集扫描");
            LocalDateTime sourceBatchTime = patientService.getLatestSourceBatchTime();
            LocalDateTime latestEnqueuedBatchTime = patientRawDataCollectTaskService.getLatestSourceLastTime();
            if (sourceBatchTime == null) {
                log.info("院感采集扫描任务本轮未获取到上游批次时间");
                return;
            }
            if (latestEnqueuedBatchTime != null && !sourceBatchTime.isAfter(latestEnqueuedBatchTime)) {
                log.debug("院感采集扫描任务跳过，sourceBatchTime={} 未超过 latestEnqueuedBatchTime={}",
                        sourceBatchTime, latestEnqueuedBatchTime);
                return;
            }
            List<String> reqnos = patientService.listActiveReqnos();
            if (reqnos == null || reqnos.isEmpty()) {
                log.info("院感采集扫描任务本轮无患者");
                return;
            }
            int enqueuedCount = infectionPipeline.enqueueRawDataTasks(reqnos, sourceBatchTime);
            log.info("院感采集扫描任务结束，sourceBatchTime={}, patients={}, enqueuedCount={}", sourceBatchTime, reqnos.size(), enqueuedCount);
            infectionDailyJobLogService.log(InfectionJobStage.LOAD,
                    InfectionJobStatus.SUCCESS,
                    null,
                    "sourceBatchTime=" + sourceBatchTime + ", scanPatients=" + reqnos.size() + ", enqueued=" + enqueuedCount);
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
            reclaimTimedOutCollectTasks("采集执行");
            int processedCount = infectionPipeline.processPendingRawDataTasks();
            if (processedCount <= 0) {
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

    private void reclaimTimedOutCollectTasks(String phase) {
        int reclaimedCount = patientRawDataCollectTaskService.reclaimTimedOutRunningTasks();
        if (reclaimedCount > 0) {
            log.warn("{}阶段回收超时采集任务，count={}", phase, reclaimedCount);
        }
    }
}
