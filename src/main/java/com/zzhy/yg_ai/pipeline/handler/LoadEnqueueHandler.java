package com.zzhy.yg_ai.pipeline.handler;

import com.zzhy.yg_ai.domain.enums.InfectionJobStage;
import com.zzhy.yg_ai.domain.enums.InfectionJobStatus;
import com.zzhy.yg_ai.pipeline.model.LoadEnqueueResult;
import com.zzhy.yg_ai.pipeline.monitor.PipelineMonitorExecutionContextHolder;
import com.zzhy.yg_ai.pipeline.monitor.PipelineMonitorTaskSummaryResolver;
import com.zzhy.yg_ai.service.InfectionDailyJobLogService;
import com.zzhy.yg_ai.service.PatientRawDataCollectTaskService;
import com.zzhy.yg_ai.service.PatientService;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class LoadEnqueueHandler {

    private final PatientService patientService;
    private final PatientRawDataCollectTaskService patientRawDataCollectTaskService;
    private final InfectionDailyJobLogService infectionDailyJobLogService;

    public LoadEnqueueResult handle() {
        try {
            LocalDateTime sourceBatchTime = patientService.getLatestSourceBatchTime();
            LocalDateTime latestEnqueuedBatchTime = patientRawDataCollectTaskService.getLatestSourceLastTime();
            if (sourceBatchTime == null) {
                log.info("院感采集扫描任务本轮未获取到上游批次时间");
                return monitored(new LoadEnqueueResult(true, 0, "未获取到上游批次时间"));
            }
            if (latestEnqueuedBatchTime != null && !sourceBatchTime.isAfter(latestEnqueuedBatchTime)) {
                log.debug("院感采集扫描任务跳过，sourceBatchTime={} 未超过 latestEnqueuedBatchTime={}",
                        sourceBatchTime, latestEnqueuedBatchTime);
                return monitored(new LoadEnqueueResult(true, 0, "批次时间未变化"));
            }
            List<String> reqnos = patientService.listActiveReqnos();
            if (reqnos == null || reqnos.isEmpty()) {
                log.info("院感采集扫描任务本轮无患者");
                return monitored(new LoadEnqueueResult(true, 0, "本轮无患者"));
            }
            int enqueuedCount = patientRawDataCollectTaskService.enqueueBatch(reqnos, sourceBatchTime);
            log.info("院感采集扫描任务结束，sourceBatchTime={}, patients={}, enqueuedCount={}",
                    sourceBatchTime, reqnos.size(), enqueuedCount);
            infectionDailyJobLogService.log(InfectionJobStage.LOAD,
                    InfectionJobStatus.SUCCESS,
                    null,
                    "sourceBatchTime=" + sourceBatchTime + ", scanPatients=" + reqnos.size() + ", enqueued=" + enqueuedCount);
            return monitored(new LoadEnqueueResult(true, enqueuedCount, "任务入队完成"));
        } catch (Exception e) {
            log.error("院感采集扫描任务执行失败", e);
            infectionDailyJobLogService.log(InfectionJobStage.LOAD,
                    InfectionJobStatus.ERROR,
                    null,
                    "院感采集扫描任务执行失败: " + e.getMessage());
            return monitored(new LoadEnqueueResult(false, 0, e.getMessage()));
        }
    }

    private LoadEnqueueResult monitored(LoadEnqueueResult result) {
        PipelineMonitorExecutionContextHolder.updateTaskSummary(
                PipelineMonitorTaskSummaryResolver.resolveTaskSummary(result)
        );
        return result;
    }
}
