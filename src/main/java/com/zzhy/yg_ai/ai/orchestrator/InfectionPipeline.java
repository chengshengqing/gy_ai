package com.zzhy.yg_ai.ai.orchestrator;

import com.zzhy.yg_ai.ai.agent.SummaryAgent;
import com.zzhy.yg_ai.config.InfectionMonitorProperties;
import com.zzhy.yg_ai.config.StructDataFormatProperties;
import com.zzhy.yg_ai.domain.entity.PatientRawDataChangeTaskEntity;
import com.zzhy.yg_ai.domain.entity.PatientRawDataCollectTaskEntity;
import com.zzhy.yg_ai.domain.entity.PatientRawDataEntity;
import com.zzhy.yg_ai.domain.enums.InfectionJobStage;
import com.zzhy.yg_ai.domain.enums.InfectionJobStatus;
import com.zzhy.yg_ai.domain.model.EvidenceBlockBuildResult;
import com.zzhy.yg_ai.domain.model.RawDataCollectResult;
import com.zzhy.yg_ai.service.InfectionEvidenceBlockService;
import com.zzhy.yg_ai.service.InfectionDailyJobLogService;
import com.zzhy.yg_ai.service.LlmEventExtractorService;
import com.zzhy.yg_ai.service.PatientRawDataChangeTaskService;
import com.zzhy.yg_ai.service.PatientRawDataCollectTaskService;
import com.zzhy.yg_ai.service.PatientService;
import com.zzhy.yg_ai.common.DateTimeUtils;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
/**
 * 当前主链路编排入口，负责患者原始数据采集、结构化摘要和异步事件抽取任务编排。
 */
public class InfectionPipeline {

    private final InfectionMonitorProperties infectionMonitorProperties;
    private final SummaryAgent summaryAgent;
    private final PatientService patientService;
    private final PatientRawDataCollectTaskService patientRawDataCollectTaskService;
    private final PatientRawDataChangeTaskService patientRawDataChangeTaskService;
    private final InfectionDailyJobLogService infectionDailyJobLogService;
    private final InfectionEvidenceBlockService infectionEvidenceBlockService;
    private final LlmEventExtractorService llmEventExtractorService;
    private final StructDataFormatProperties structDataFormatProperties;
    public InfectionPipeline(InfectionMonitorProperties infectionMonitorProperties,
                             SummaryAgent summaryAgent,
                             PatientService patientService,
                             PatientRawDataCollectTaskService patientRawDataCollectTaskService,
                             PatientRawDataChangeTaskService patientRawDataChangeTaskService,
                             InfectionDailyJobLogService infectionDailyJobLogService,
                             InfectionEvidenceBlockService infectionEvidenceBlockService,
                             LlmEventExtractorService llmEventExtractorService,
                             StructDataFormatProperties structDataFormatProperties) {
        this.infectionMonitorProperties = infectionMonitorProperties;
        this.summaryAgent = summaryAgent;
        this.patientService = patientService;
        this.patientRawDataCollectTaskService = patientRawDataCollectTaskService;
        this.patientRawDataChangeTaskService = patientRawDataChangeTaskService;
        this.infectionDailyJobLogService = infectionDailyJobLogService;
        this.infectionEvidenceBlockService = infectionEvidenceBlockService;
        this.llmEventExtractorService = llmEventExtractorService;
        this.structDataFormatProperties = structDataFormatProperties;
    }

    public int enqueueRawDataTasks(List<String> reqnos) {
        return patientRawDataCollectTaskService.enqueueBatch(reqnos, DateTimeUtils.now());
    }

    public int processPendingRawDataTasks() {
        List<PatientRawDataCollectTaskEntity> tasks =
                patientRawDataCollectTaskService.claimPendingTasks(infectionMonitorProperties.getBatchSize());
        if (tasks == null || tasks.isEmpty()) {
            return 0;
        }

        ExecutorService executor = Executors.newFixedThreadPool(Math.max(1, infectionMonitorProperties.getWorkerThreads()));
        List<CompletableFuture<RawCollectTaskExecutionResult>> futures = new ArrayList<>();
        for (PatientRawDataCollectTaskEntity taskEntity : tasks) {
            CompletableFuture<RawCollectTaskExecutionResult> task =
                    CompletableFuture.supplyAsync(() -> processCollectTask(taskEntity), executor);
            futures.add(task);
        }

        int processedCount;
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            processedCount = futures.stream()
                    .map(CompletableFuture::join)
                    .peek(this::finalizeCollectTask)
                    .mapToInt(result -> result.isSuccessLike() ? 1 : 0)
                    .sum();
        } finally {
            executor.shutdown();
        }

        if (processedCount > 0) {
            log.info("患者原始数据采集任务完成，processedCount={}", processedCount);
        }
        return processedCount;
    }

    public int processPendingStructData() {
        List<PatientRawDataChangeTaskEntity> claimedTasks =
                patientRawDataChangeTaskService.claimPendingStructTasks(structDataFormatProperties.getBatchSize());
        if (claimedTasks == null || claimedTasks.isEmpty()) {
            return 0;
        }
        Map<String, List<PatientRawDataChangeTaskEntity>> tasksByReqno = new LinkedHashMap<>();
        for (PatientRawDataChangeTaskEntity claimedTask : claimedTasks) {
            if (claimedTask == null || !StringUtils.hasText(claimedTask.getReqno())) {
                continue;
            }
            tasksByReqno.computeIfAbsent(claimedTask.getReqno().trim(), key -> new ArrayList<>()).add(claimedTask);
        }
        if (tasksByReqno.isEmpty()) {
            return 0;
        }

        ExecutorService executor = Executors.newFixedThreadPool(Math.max(1, structDataFormatProperties.getWorkerThreads()));
        List<CompletableFuture<StructTaskExecutionResult>> futures = new ArrayList<>();
        for (List<PatientRawDataChangeTaskEntity> taskGroup : tasksByReqno.values()) {
            CompletableFuture<StructTaskExecutionResult> task =
                    CompletableFuture.supplyAsync(() -> processStructTask(taskGroup), executor);
            futures.add(task);
        }

        int formattedCount;
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            formattedCount = futures.stream()
                    .map(CompletableFuture::join)
                    .peek(this::finalizeStructTask)
                    .mapToInt(StructTaskExecutionResult::successCount)
                    .sum();
        } finally {
            executor.shutdown();
        }

        if (formattedCount > 0) {
            log.info("患者结构化格式化完成，formattedCount={}", formattedCount);
        }
        return formattedCount;
    }

    public int processPendingEventData() {
        List<PatientRawDataChangeTaskEntity> claimedTasks =
                patientRawDataChangeTaskService.claimPendingEventTasks(structDataFormatProperties.getBatchSize());
        if (claimedTasks == null || claimedTasks.isEmpty()) {
            return 0;
        }
        Map<String, List<PatientRawDataChangeTaskEntity>> tasksByReqno = new LinkedHashMap<>();
        for (PatientRawDataChangeTaskEntity claimedTask : claimedTasks) {
            if (claimedTask == null || !StringUtils.hasText(claimedTask.getReqno())) {
                continue;
            }
            tasksByReqno.computeIfAbsent(claimedTask.getReqno().trim(), key -> new ArrayList<>()).add(claimedTask);
        }
        if (tasksByReqno.isEmpty()) {
            return 0;
        }

        ExecutorService executor = Executors.newFixedThreadPool(Math.max(1, structDataFormatProperties.getWorkerThreads()));
        List<CompletableFuture<EventTaskExecutionResult>> futures = new ArrayList<>();
        for (List<PatientRawDataChangeTaskEntity> taskGroup : tasksByReqno.values()) {
            CompletableFuture<EventTaskExecutionResult> task =
                    CompletableFuture.supplyAsync(() -> processEventTask(taskGroup), executor);
            futures.add(task);
        }

        int extractedCount;
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            extractedCount = futures.stream()
                    .map(CompletableFuture::join)
                    .peek(this::finalizeEventTask)
                    .mapToInt(EventTaskExecutionResult::successCount)
                    .sum();
        } finally {
            executor.shutdown();
        }

        if (extractedCount > 0) {
            log.info("患者事件抽取完成，extractedCount={}", extractedCount);
        }
        return extractedCount;
    }

    private RawCollectTaskExecutionResult processCollectTask(PatientRawDataCollectTaskEntity taskEntity) {
        String reqno = taskEntity == null ? null : taskEntity.getReqno();
        if (!StringUtils.hasText(reqno)) {
            return RawCollectTaskExecutionResult.failed(taskEntity == null ? null : taskEntity.getId(),
                    reqno, "reqno为空");
        }
        try {
            RawDataCollectResult result = patientService.collectAndSaveRawDataResult(reqno);
            return new RawCollectTaskExecutionResult(taskEntity.getId(), reqno, result);
        } catch (Exception e) {
            log.error("患者原始数据采集失败，reqno={}", reqno, e);
            return RawCollectTaskExecutionResult.failed(taskEntity.getId(), reqno, e.getMessage());
        }
    }

    private StructTaskExecutionResult processStructTask(List<PatientRawDataChangeTaskEntity> taskGroup) {
        List<Long> taskIds = extractTaskIds(taskGroup);
        String reqno = resolveReqno(taskGroup);
        if (!StringUtils.hasText(reqno)) {
            return StructTaskExecutionResult.failed(taskIds, reqno, 0, "reqno为空");
        }

        List<PatientRawDataEntity> rows = collectFreshRawData(taskGroup);
        if (rows.isEmpty()) {
            return StructTaskExecutionResult.success(taskIds, reqno, 0);
        }
        int successCount = 0;
        int failedCount = 0;
        String lastErrorMessage = null;
        InfectionJobStage failedStage = InfectionJobStage.NORMALIZE;
        for (PatientRawDataEntity rawData : rows) {
            RawDataProcessResult processResult = processPendingRawData(reqno, rawData);
            if (!processResult.isSuccess()) {
                failedCount++;
                lastErrorMessage = StringUtils.hasText(processResult.errorMessage())
                        ? processResult.errorMessage()
                        : "存在未完成的 rawData 行，需重试";
                failedStage = processResult.failedStage() == null ? InfectionJobStage.NORMALIZE : processResult.failedStage();
                continue;
            }
            successCount++;
        }
        return new StructTaskExecutionResult(taskIds, reqno, rows.size(), successCount, failedCount, lastErrorMessage, failedStage);
    }

    private RawDataProcessResult processPendingRawData(String reqno, PatientRawDataEntity rawData) {
        if (rawData == null || !StringUtils.hasText(rawData.getDataJson())) {
            return RawDataProcessResult.failed(InfectionJobStage.NORMALIZE, "rawData为空或缺少dataJson");
        }

        try {
            patientService.resetDerivedDataForRawData(rawData.getId());
            SummaryAgent.DailyIllnessResult dailyIllnessResult = summaryAgent.extractDailyIllness(rawData);
            rawData.setStructDataJson(dailyIllnessResult.structDataJson());
            rawData.setEventJson(dailyIllnessResult.dailySummaryJson());
            patientService.saveStructDataJson(rawData.getId(),
                    dailyIllnessResult.structDataJson(),
                    dailyIllnessResult.dailySummaryJson());
            return RawDataProcessResult.success();
        } catch (Exception e) {
            log.error("病程增量摘要失败，rowId={}, reqno={}", rawData.getId(), rawData.getReqno(), e);
            return RawDataProcessResult.failed(InfectionJobStage.NORMALIZE, "存在未完成的 rawData 行，需重试");
        }
    }

    private EventTaskExecutionResult processEventTask(List<PatientRawDataChangeTaskEntity> taskGroup) {
        List<Long> taskIds = extractTaskIds(taskGroup);
        String reqno = resolveReqno(taskGroup);
        if (!StringUtils.hasText(reqno)) {
            return EventTaskExecutionResult.failed(taskIds, reqno, 0, "reqno为空");
        }

        List<PatientRawDataEntity> rows = collectFreshRawData(taskGroup);
        if (rows.isEmpty()) {
            return EventTaskExecutionResult.success(taskIds, reqno, 0);
        }

        int successCount = 0;
        int failedCount = 0;
        String lastErrorMessage = null;
        for (PatientRawDataEntity rawData : rows) {
            try {
                String timelineWindowJson = patientService.buildSummaryWindowJson(reqno, rawData.getDataDate(), 7);
                extractEvents(rawData, timelineWindowJson);
                successCount++;
            } catch (Exception e) {
                failedCount++;
                lastErrorMessage = "存在未完成的事件抽取 rawData 行，需重试";
                log.error("事件抽取失败，rowId={}, reqno={}", rawData.getId(), rawData.getReqno(), e);
            }
        }
        if (failedCount > 0) {
            return new EventTaskExecutionResult(taskIds, reqno, rows.size(), successCount, failedCount, lastErrorMessage);
        }
        return EventTaskExecutionResult.success(taskIds, reqno, successCount);
    }

    private void extractEvents(PatientRawDataEntity rawData, String timelineWindowJson) {
        EvidenceBlockBuildResult buildResult = infectionEvidenceBlockService.buildBlocks(rawData, timelineWindowJson);
        if (buildResult == null || buildResult.primaryBlocks().isEmpty()) {
            return;
        }
        llmEventExtractorService.extractAndSave(buildResult);
    }

    private void finalizeStructTask(StructTaskExecutionResult result) {
        if (result == null || result.taskIds() == null || result.taskIds().isEmpty()) {
            return;
        }
        if (result.failedCount() > 0) {
            String message = StringUtils.hasText(result.lastErrorMessage())
                    ? result.lastErrorMessage()
                    : "部分结构化处理失败";
            patientRawDataChangeTaskService.markStructFailed(result.taskIds(), message);
            infectionDailyJobLogService.log(result.failedStage(),
                    InfectionJobStatus.ERROR,
                    result.reqno(),
                    message);
            return;
        }
        String message = result.totalRows() == 0
                ? "无待处理结构化数据"
                : "结构化处理成功，successCount=" + result.successCount();
        patientRawDataChangeTaskService.markStructSuccess(result.taskIds(), message, result.totalRows() > 0);
    }

    private void finalizeEventTask(EventTaskExecutionResult result) {
        if (result == null || result.taskIds() == null || result.taskIds().isEmpty()) {
            return;
        }
        if (result.failedCount() > 0) {
            String message = StringUtils.hasText(result.lastErrorMessage())
                    ? result.lastErrorMessage()
                    : "部分事件抽取失败";
            patientRawDataChangeTaskService.markEventFailed(result.taskIds(), message);
            infectionDailyJobLogService.log(InfectionJobStage.LLM,
                    InfectionJobStatus.ERROR,
                    result.reqno(),
                    message);
            return;
        }
        String message = result.totalRows() == 0
                ? "无待处理事件数据"
                : "事件抽取成功，successCount=" + result.successCount();
        patientRawDataChangeTaskService.markEventSuccess(result.taskIds(), message);
    }

    private void finalizeCollectTask(RawCollectTaskExecutionResult result) {
        if (result == null || result.taskId() == null) {
            return;
        }
        patientRawDataCollectTaskService.updateChangeTypes(result.taskId(), result.changeTypes());
        if (!result.isSuccessLike()) {
            patientRawDataCollectTaskService.markFailed(result.taskId(), result.message());
            infectionDailyJobLogService.log(InfectionJobStage.LOAD,
                    InfectionJobStatus.ERROR,
                    result.reqno(),
                    result.message());
            return;
        }
        patientRawDataCollectTaskService.markSuccess(result.taskId(), result.message());
    }

    private List<Long> extractTaskIds(List<PatientRawDataChangeTaskEntity> taskGroup) {
        List<Long> taskIds = new ArrayList<>();
        if (taskGroup == null || taskGroup.isEmpty()) {
            return taskIds;
        }
        for (PatientRawDataChangeTaskEntity taskEntity : taskGroup) {
            if (taskEntity != null && taskEntity.getId() != null) {
                taskIds.add(taskEntity.getId());
            }
        }
        return taskIds;
    }

    private String resolveReqno(List<PatientRawDataChangeTaskEntity> taskGroup) {
        if (taskGroup == null || taskGroup.isEmpty()) {
            return null;
        }
        for (PatientRawDataChangeTaskEntity taskEntity : taskGroup) {
            if (taskEntity != null && StringUtils.hasText(taskEntity.getReqno())) {
                return taskEntity.getReqno().trim();
            }
        }
        return null;
    }

    private List<PatientRawDataEntity> collectFreshRawData(List<PatientRawDataChangeTaskEntity> taskGroup) {
        Map<String, PatientRawDataEntity> rows = new LinkedHashMap<>();
        if (taskGroup == null || taskGroup.isEmpty()) {
            return List.of();
        }
        for (PatientRawDataChangeTaskEntity taskEntity : taskGroup) {
            if (taskEntity == null || taskEntity.getPatientRawDataId() == null || taskEntity.getRawDataLastTime() == null) {
                continue;
            }
            PatientRawDataEntity rawData = patientService.getRawDataById(taskEntity.getPatientRawDataId());
            if (rawData == null || rawData.getLastTime() == null) {
                continue;
            }
            if (!rawData.getLastTime().equals(taskEntity.getRawDataLastTime())) {
                continue;
            }
            rows.putIfAbsent(rawData.getId() + "|" + taskEntity.getRawDataLastTime(), rawData);
        }
        return new ArrayList<>(rows.values());
    }

    private record StructTaskExecutionResult(List<Long> taskIds,
                                             String reqno,
                                             int totalRows,
                                             int successCount,
                                             int failedCount,
                                             String lastErrorMessage,
                                             InfectionJobStage failedStage) {

        private static StructTaskExecutionResult success(List<Long> taskIds, String reqno, int successCount) {
            return new StructTaskExecutionResult(taskIds, reqno, successCount, successCount, 0, null, InfectionJobStage.NORMALIZE);
        }

        private static StructTaskExecutionResult failed(List<Long> taskIds, String reqno, int totalRows, String lastErrorMessage) {
            return new StructTaskExecutionResult(taskIds, reqno, totalRows, 0, Math.max(1, totalRows), lastErrorMessage, InfectionJobStage.NORMALIZE);
        }
    }

    private record RawDataProcessResult(InfectionJobStage failedStage,
                                        String errorMessage) {

        private static RawDataProcessResult success() {
            return new RawDataProcessResult(null, null);
        }

        private static RawDataProcessResult failed(InfectionJobStage failedStage, String errorMessage) {
            return new RawDataProcessResult(failedStage, errorMessage);
        }

        private boolean isSuccess() {
            return failedStage == null && !StringUtils.hasText(errorMessage);
        }
    }

    private record EventTaskExecutionResult(List<Long> taskIds,
                                            String reqno,
                                            int totalRows,
                                            int successCount,
                                            int failedCount,
                                            String lastErrorMessage) {

        private static EventTaskExecutionResult success(List<Long> taskIds, String reqno, int successCount) {
            return new EventTaskExecutionResult(taskIds, reqno, successCount, successCount, 0, null);
        }

        private static EventTaskExecutionResult failed(List<Long> taskIds, String reqno, int totalRows, String lastErrorMessage) {
            return new EventTaskExecutionResult(taskIds, reqno, totalRows, 0, Math.max(1, totalRows), lastErrorMessage);
        }
    }

    private record RawCollectTaskExecutionResult(Long taskId,
                                                 String reqno,
                                                 String status,
                                                 String message,
                                                 Integer savedDays,
                                                 String changeTypes) {

        private RawCollectTaskExecutionResult(Long taskId, String reqno, RawDataCollectResult result) {
            this(taskId,
                    reqno,
                    result == null ? "failed" : result.getStatus(),
                    result == null ? "原始数据采集结果为空" : result.getMessage(),
                    result == null ? null : result.getSavedDays(),
                    result == null ? null : result.getChangeTypes());
        }

        private static RawCollectTaskExecutionResult failed(Long taskId, String reqno, String message) {
            return new RawCollectTaskExecutionResult(taskId, reqno, "failed", message, 0, null);
        }

        private boolean isSuccessLike() {
            return "success".equalsIgnoreCase(status) || "no_data".equalsIgnoreCase(status);
        }
    }

}
