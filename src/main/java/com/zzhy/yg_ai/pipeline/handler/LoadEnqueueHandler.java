package com.zzhy.yg_ai.pipeline.handler;

import com.zzhy.yg_ai.config.InfectionMonitorProperties;
import com.zzhy.yg_ai.domain.enums.InfectionJobStage;
import com.zzhy.yg_ai.domain.enums.InfectionJobStatus;
import com.zzhy.yg_ai.pipeline.model.LoadEnqueueResult;
import com.zzhy.yg_ai.pipeline.monitor.PipelineMonitorExecutionContextHolder;
import com.zzhy.yg_ai.pipeline.monitor.PipelineMonitorTaskSummaryResolver;
import com.zzhy.yg_ai.service.InfectionDailyJobLogService;
import com.zzhy.yg_ai.service.PatientRawDataCollectTaskService;
import com.zzhy.yg_ai.service.PatientService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Slf4j
@Component
@RequiredArgsConstructor
public class LoadEnqueueHandler {

    private static final String TASK_NAME = "院感采集扫描任务";
    private static final int DEFAULT_SCAN_LIMIT = 200;
    private static final int DEFAULT_RECENT_ADMISSION_DAYS = 30;

    private final InfectionMonitorProperties infectionMonitorProperties;
    private final PatientService patientService;
    private final PatientRawDataCollectTaskService patientRawDataCollectTaskService;
    private final InfectionDailyJobLogService infectionDailyJobLogService;

    public LoadEnqueueResult handle() {
        try {
            LocalDateTime sourceBatchTime = patientService.getLatestSourceBatchTime();
            LocalDateTime latestEnqueuedBatchTime = patientRawDataCollectTaskService.getLatestSourceLastTime();
            LocalDateTime latestLoadSuccessTime = infectionDailyJobLogService.getLatestSuccessTime(InfectionJobStage.LOAD);
            LoadEnqueueResult precheckResult = precheck(sourceBatchTime, latestEnqueuedBatchTime);
            if (precheckResult != null) {
                return monitored(precheckResult);
            }
            return monitored(scanAndEnqueue(sourceBatchTime, latestLoadSuccessTime));
        } catch (Exception e) {
            return monitored(handleFailure(e));
        }
    }

    private LoadEnqueueResult precheck(LocalDateTime sourceBatchTime, LocalDateTime latestEnqueuedBatchTime) {
        if (sourceBatchTime == null) {
            log.info("{}跳过，reason=未获取到上游批次时间", TASK_NAME);
            return new LoadEnqueueResult(true, 0, "未获取到上游批次时间");
        }
        if (latestEnqueuedBatchTime != null && !sourceBatchTime.isAfter(latestEnqueuedBatchTime)) {
            log.debug("{}跳过，reason=批次时间未变化, sourceBatchTime={}, latestEnqueuedBatchTime={}",
                    TASK_NAME, sourceBatchTime, latestEnqueuedBatchTime);
            return new LoadEnqueueResult(true, 0, "批次时间未变化");
        }
        return null;
    }

    private LoadEnqueueResult scanAndEnqueue(LocalDateTime sourceBatchTime, LocalDateTime latestLoadSuccessTime) {
        List<String> debugReqnos = normalizeReqnos(infectionMonitorProperties.getDebugReqnos());
        if (infectionMonitorProperties.isDebugMode()) {
            return enqueueDebugReqnos(sourceBatchTime, debugReqnos);
        }
        if (!debugReqnos.isEmpty()) {
            log.info("已配置 debugReqnos，但 debugMode=false，当前仍按正式扫描策略执行");
        }
        return enqueuePagedReqnos(sourceBatchTime, latestLoadSuccessTime);
    }

    private LoadEnqueueResult enqueueDebugReqnos(LocalDateTime sourceBatchTime, List<String> debugReqnos) {
        if (debugReqnos.isEmpty()) {
            log.warn("{}跳过，reason=调试模式未配置患者", TASK_NAME);
            return new LoadEnqueueResult(true, 0, "调试模式未配置患者");
        }
        log.info("{}使用调试名单，patients={}", TASK_NAME, debugReqnos.size());
        int enqueuedCount = patientRawDataCollectTaskService.enqueueBatch(debugReqnos, sourceBatchTime);
        return buildSuccessResult(sourceBatchTime, 1, debugReqnos.size(), enqueuedCount);
    }

    private LoadEnqueueResult enqueuePagedReqnos(LocalDateTime sourceBatchTime, LocalDateTime latestLoadSuccessTime) {
        int recentAdmissionDays = resolveRecentAdmissionDays();
        int scanLimit = resolveScanLimit();
        int scannedCount = 0;
        int enqueuedCount = 0;
        int offset = 0;
        int pageCount = 0;
        while (true) {
            List<String> reqnos = patientService.listActiveReqnos(
                    latestLoadSuccessTime,
                    recentAdmissionDays,
                    offset,
                    scanLimit
            );
            if (reqnos == null || reqnos.isEmpty()) {
                break;
            }
            pageCount++;
            scannedCount += reqnos.size();
            enqueuedCount += patientRawDataCollectTaskService.enqueueBatch(reqnos, sourceBatchTime);
            offset += reqnos.size();
        }
        if (scannedCount <= 0) {
            log.info("{}结束，reason=本轮无患者", TASK_NAME);
            return new LoadEnqueueResult(true, 0, "本轮无患者");
        }
        return buildSuccessResult(sourceBatchTime, pageCount, scannedCount, enqueuedCount);
    }

    private LoadEnqueueResult buildSuccessResult(LocalDateTime sourceBatchTime,
                                                 int pageCount,
                                                 int scannedCount,
                                                 int enqueuedCount) {
        String summary = buildSuccessSummary(sourceBatchTime, pageCount, scannedCount, enqueuedCount);
        log.info("{}结束，{}", TASK_NAME, summary);
        infectionDailyJobLogService.log(InfectionJobStage.LOAD,
                InfectionJobStatus.SUCCESS,
                null,
                summary);
        return new LoadEnqueueResult(true, enqueuedCount, "任务入队完成");
    }

    private LoadEnqueueResult handleFailure(Exception e) {
        String errorMessage = buildFailureMessage(e);
        log.error(errorMessage, e);
        infectionDailyJobLogService.log(InfectionJobStage.LOAD,
                InfectionJobStatus.ERROR,
                null,
                errorMessage);
        return new LoadEnqueueResult(false, 0, e.getMessage());
    }

    private String buildSuccessSummary(LocalDateTime sourceBatchTime,
                                       int pageCount,
                                       int scannedCount,
                                       int enqueuedCount) {
        return "sourceBatchTime=" + sourceBatchTime
                + ", pages=" + pageCount
                + ", scanPatients=" + scannedCount
                + ", enqueued=" + enqueuedCount;
    }

    private String buildFailureMessage(Exception e) {
        return TASK_NAME + "执行失败: " + e.getMessage();
    }

    private int resolveRecentAdmissionDays() {
        return infectionMonitorProperties.getRecentAdmissionDays() <= 0
                ? DEFAULT_RECENT_ADMISSION_DAYS
                : infectionMonitorProperties.getRecentAdmissionDays();
    }

    private int resolveScanLimit() {
        return infectionMonitorProperties.getScanLimit() <= 0
                ? DEFAULT_SCAN_LIMIT
                : infectionMonitorProperties.getScanLimit();
    }

    private List<String> normalizeReqnos(List<String> reqnos) {
        if (reqnos == null || reqnos.isEmpty()) {
            return Collections.emptyList();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String reqno : reqnos) {
            if (StringUtils.hasText(reqno)) {
                normalized.add(reqno.trim());
            }
        }
        if (normalized.isEmpty()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(normalized);
    }

    private LoadEnqueueResult monitored(LoadEnqueueResult result) {
        PipelineMonitorExecutionContextHolder.updateTaskSummary(
                PipelineMonitorTaskSummaryResolver.resolveTaskSummary(result)
        );
        return result;
    }
}
