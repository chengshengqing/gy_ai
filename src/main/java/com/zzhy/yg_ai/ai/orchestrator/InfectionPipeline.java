package com.zzhy.yg_ai.ai.orchestrator;

import com.zzhy.yg_ai.ai.agent.SummaryAgent;
import com.zzhy.yg_ai.config.InfectionMonitorProperties;
import com.zzhy.yg_ai.config.StructDataFormatProperties;
import com.zzhy.yg_ai.domain.entity.InfectionEventTaskEntity;
import com.zzhy.yg_ai.domain.entity.PatientRawDataChangeTaskEntity;
import com.zzhy.yg_ai.domain.entity.PatientRawDataCollectTaskEntity;
import com.zzhy.yg_ai.domain.entity.PatientRawDataEntity;
import com.zzhy.yg_ai.domain.enums.InfectionEventTaskType;
import com.zzhy.yg_ai.domain.enums.InfectionJobStage;
import com.zzhy.yg_ai.domain.enums.InfectionJobStatus;
import com.zzhy.yg_ai.domain.model.EvidenceBlockBuildResult;
import com.zzhy.yg_ai.domain.model.LlmEventExtractorResult;
import com.zzhy.yg_ai.domain.model.RawDataCollectResult;
import com.zzhy.yg_ai.service.InfectionDailyJobLogService;
import com.zzhy.yg_ai.service.InfectionEventTaskService;
import com.zzhy.yg_ai.service.InfectionEvidenceBlockService;
import com.zzhy.yg_ai.service.LlmEventExtractorService;
import com.zzhy.yg_ai.service.PatientRawDataChangeTaskService;
import com.zzhy.yg_ai.service.PatientRawDataCollectTaskService;
import com.zzhy.yg_ai.service.PatientService;
import java.time.LocalDateTime;
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
public class InfectionPipeline {

    private static final int EVENT_EXTRACT_PRIORITY = 50;
    private static final int CASE_RECOMPUTE_PRIORITY = 10;
    private static final int CASE_BUCKET_MINUTES = 30;

    private final InfectionMonitorProperties infectionMonitorProperties;
    private final SummaryAgent summaryAgent;
    private final PatientService patientService;
    private final PatientRawDataCollectTaskService patientRawDataCollectTaskService;
    private final PatientRawDataChangeTaskService patientRawDataChangeTaskService;
    private final InfectionEventTaskService infectionEventTaskService;
    private final InfectionDailyJobLogService infectionDailyJobLogService;
    private final InfectionEvidenceBlockService infectionEvidenceBlockService;
    private final LlmEventExtractorService llmEventExtractorService;
    private final StructDataFormatProperties structDataFormatProperties;

    public InfectionPipeline(InfectionMonitorProperties infectionMonitorProperties,
                             SummaryAgent summaryAgent,
                             PatientService patientService,
                             PatientRawDataCollectTaskService patientRawDataCollectTaskService,
                             PatientRawDataChangeTaskService patientRawDataChangeTaskService,
                             InfectionEventTaskService infectionEventTaskService,
                             InfectionDailyJobLogService infectionDailyJobLogService,
                             InfectionEvidenceBlockService infectionEvidenceBlockService,
                             LlmEventExtractorService llmEventExtractorService,
                             StructDataFormatProperties structDataFormatProperties) {
        this.infectionMonitorProperties = infectionMonitorProperties;
        this.summaryAgent = summaryAgent;
        this.patientService = patientService;
        this.patientRawDataCollectTaskService = patientRawDataCollectTaskService;
        this.patientRawDataChangeTaskService = patientRawDataChangeTaskService;
        this.infectionEventTaskService = infectionEventTaskService;
        this.infectionDailyJobLogService = infectionDailyJobLogService;
        this.infectionEvidenceBlockService = infectionEvidenceBlockService;
        this.llmEventExtractorService = llmEventExtractorService;
        this.structDataFormatProperties = structDataFormatProperties;
    }

    public int enqueueRawDataTasks(List<String> reqnos, LocalDateTime sourceBatchTime) {
        return patientRawDataCollectTaskService.enqueueBatch(reqnos, sourceBatchTime);
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
            futures.add(CompletableFuture.supplyAsync(() -> processCollectTask(taskEntity), executor));
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
            futures.add(CompletableFuture.supplyAsync(() -> processStructTask(taskGroup), executor));
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
        List<InfectionEventTaskEntity> claimedTasks =
                infectionEventTaskService.claimPendingTasks(InfectionEventTaskType.EVENT_EXTRACT, structDataFormatProperties.getBatchSize());
        if (claimedTasks == null || claimedTasks.isEmpty()) {
            return 0;
        }

        ExecutorService executor = Executors.newFixedThreadPool(Math.max(1, structDataFormatProperties.getWorkerThreads()));
        List<CompletableFuture<EventTaskExecutionResult>> futures = new ArrayList<>();
        for (InfectionEventTaskEntity taskEntity : claimedTasks) {
            futures.add(CompletableFuture.supplyAsync(() -> processEventTask(taskEntity), executor));
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

    public int processPendingCaseData() {
        List<InfectionEventTaskEntity> claimedTasks =
                infectionEventTaskService.claimPendingTasks(InfectionEventTaskType.CASE_RECOMPUTE, structDataFormatProperties.getBatchSize());
        if (claimedTasks == null || claimedTasks.isEmpty()) {
            return 0;
        }

        ExecutorService executor = Executors.newFixedThreadPool(Math.max(1, structDataFormatProperties.getWorkerThreads()));
        List<CompletableFuture<CaseTaskExecutionResult>> futures = new ArrayList<>();
        for (InfectionEventTaskEntity taskEntity : claimedTasks) {
            futures.add(CompletableFuture.supplyAsync(() -> processCaseTask(taskEntity), executor));
        }

        int processedCount;
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            processedCount = futures.stream()
                    .map(CompletableFuture::join)
                    .peek(this::finalizeCaseTask)
                    .mapToInt(CaseTaskExecutionResult::successCount)
                    .sum();
        } finally {
            executor.shutdown();
        }

        if (processedCount > 0) {
            log.info("病例重算任务完成，processedCount={}", processedCount);
        }
        return processedCount;
    }

    private RawCollectTaskExecutionResult processCollectTask(PatientRawDataCollectTaskEntity taskEntity) {
        String reqno = taskEntity == null ? null : taskEntity.getReqno();
        if (!StringUtils.hasText(reqno)) {
            return RawCollectTaskExecutionResult.failed(taskEntity == null ? null : taskEntity.getId(), reqno, "reqno为空");
        }
        try {
            RawDataCollectResult result = patientService.collectAndSaveRawDataResult(
                    reqno,
                    taskEntity == null ? null : taskEntity.getPreviousSourceLastTime(),
                    taskEntity == null ? null : taskEntity.getSourceLastTime()
            );
            return new RawCollectTaskExecutionResult(taskEntity.getId(), reqno, result);
        } catch (Exception e) {
            log.error("患者原始数据采集失败，reqno={}", reqno, e);
            return RawCollectTaskExecutionResult.failed(taskEntity.getId(), reqno, e.getMessage());
        }
    }

    private StructTaskExecutionResult processStructTask(List<PatientRawDataChangeTaskEntity> taskGroup) {
        List<Long> taskIds = extractStructTaskIds(taskGroup);
        String reqno = resolveStructReqno(taskGroup);
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
            RawDataProcessResult processResult = processPendingRawData(rawData);
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

    private RawDataProcessResult processPendingRawData(PatientRawDataEntity rawData) {
        if (rawData == null || !StringUtils.hasText(rawData.getDataJson())) {
            return RawDataProcessResult.failed(InfectionJobStage.NORMALIZE, "rawData为空或缺少dataJson");
        }

        try {
            patientService.resetDerivedDataForRawData(rawData.getId());
            SummaryAgent.DailyIllnessResult dailyIllnessResult = summaryAgent.extractDailyIllness(rawData);
            rawData.setStructDataJson(dailyIllnessResult.structDataJson());
            rawData.setEventJson(dailyIllnessResult.dailySummaryJson());
            patientService.saveStructDataJson(
                    rawData.getId(),
                    dailyIllnessResult.structDataJson(),
                    dailyIllnessResult.dailySummaryJson()
            );
            return RawDataProcessResult.success();
        } catch (Exception e) {
            log.error("病程增量摘要失败，rowId={}, reqno={}", rawData.getId(), rawData.getReqno(), e);
            return RawDataProcessResult.failed(InfectionJobStage.NORMALIZE, "存在未完成的 rawData 行，需重试");
        }
    }

    private EventTaskExecutionResult processEventTask(InfectionEventTaskEntity taskEntity) {
        List<Long> taskIds = extractEventTaskIds(taskEntity);
        String reqno = resolveEventReqno(taskEntity);
        if (!StringUtils.hasText(reqno)) {
            return EventTaskExecutionResult.failed(taskIds, reqno, "reqno为空");
        }
        PatientRawDataEntity rawData = collectFreshRawData(taskEntity);
        if (rawData == null) {
            return EventTaskExecutionResult.skipped(taskIds, reqno, "事件任务版本已过期，跳过");
        }

        try {
            String timelineWindowJson = patientService.buildSummaryWindowJson(reqno, rawData.getDataDate(), 7);
            LlmEventExtractorResult extractResult = extractEvents(rawData, timelineWindowJson);
            int caseTaskCount = 0;
            if (extractResult != null && !extractResult.persistedEvents().isEmpty()) {
                infectionEventTaskService.upsertCaseRecomputeTask(
                        reqno,
                        rawData.getId(),
                        rawData.getDataDate(),
                        rawData.getLastTime(),
                        taskEntity.getSourceBatchTime() == null ? LocalDateTime.now() : taskEntity.getSourceBatchTime(),
                        CASE_BUCKET_MINUTES,
                        CASE_RECOMPUTE_PRIORITY
                );
                caseTaskCount = 1;
            }
            return EventTaskExecutionResult.success(taskIds, reqno, caseTaskCount);
        } catch (Exception e) {
            log.error("事件抽取失败，taskId={}, rowId={}, reqno={}", taskEntity.getId(), rawData.getId(), reqno, e);
            return EventTaskExecutionResult.failed(taskIds, reqno, "存在未完成的事件抽取 rawData 行，需重试");
        }
    }

    private LlmEventExtractorResult extractEvents(PatientRawDataEntity rawData, String timelineWindowJson) {
        EvidenceBlockBuildResult buildResult = infectionEvidenceBlockService.buildBlocks(rawData, timelineWindowJson);
        if (buildResult == null || buildResult.primaryBlocks().isEmpty()) {
            return null;
        }
        return llmEventExtractorService.extractAndSave(buildResult);
    }

    private CaseTaskExecutionResult processCaseTask(InfectionEventTaskEntity taskEntity) {
        List<Long> taskIds = extractEventTaskIds(taskEntity);
        String reqno = resolveEventReqno(taskEntity);
        if (!StringUtils.hasText(reqno)) {
            return CaseTaskExecutionResult.failed(taskIds, reqno, "reqno为空");
        }
        try {
            log.info("病例重算占位任务执行，taskId={}, reqno={}", taskEntity.getId(), reqno);
            return CaseTaskExecutionResult.success(taskIds, reqno);
        } catch (Exception e) {
            log.error("病例重算占位任务失败，taskId={}, reqno={}", taskEntity.getId(), reqno, e);
            return CaseTaskExecutionResult.failed(taskIds, reqno, "病例重算任务执行失败，需重试");
        }
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
            infectionDailyJobLogService.log(result.failedStage(), InfectionJobStatus.ERROR, result.reqno(), message);
            return;
        }
        if (result.totalRows() == 0) {
            patientRawDataChangeTaskService.markStructSkipped(result.taskIds(), "结构化任务版本已过期，跳过");
            return;
        }
        patientRawDataChangeTaskService.markStructSuccess(
                result.taskIds(),
                "结构化处理成功，successCount=" + result.successCount()
        );
    }

    private void finalizeEventTask(EventTaskExecutionResult result) {
        if (result == null || result.taskIds() == null || result.taskIds().isEmpty()) {
            return;
        }
        if (result.failedCount() > 0) {
            String message = StringUtils.hasText(result.lastErrorMessage())
                    ? result.lastErrorMessage()
                    : "部分事件抽取失败";
            infectionEventTaskService.markFailed(result.taskIds(), message);
            infectionDailyJobLogService.log(InfectionJobStage.LLM, InfectionJobStatus.ERROR, result.reqno(), message);
            return;
        }
        if (result.skipped()) {
            infectionEventTaskService.markSkipped(result.taskIds(), result.message());
            return;
        }
        infectionEventTaskService.markSuccess(result.taskIds(), result.message());
    }

    private void finalizeCaseTask(CaseTaskExecutionResult result) {
        if (result == null || result.taskIds() == null || result.taskIds().isEmpty()) {
            return;
        }
        if (result.failedCount() > 0) {
            String message = StringUtils.hasText(result.lastErrorMessage())
                    ? result.lastErrorMessage()
                    : "病例重算失败";
            infectionEventTaskService.markFailed(result.taskIds(), message);
            infectionDailyJobLogService.log(InfectionJobStage.FINALIZE, InfectionJobStatus.ERROR, result.reqno(), message);
            return;
        }
        infectionEventTaskService.markSuccess(result.taskIds(), result.message());
    }

    private void finalizeCollectTask(RawCollectTaskExecutionResult result) {
        if (result == null || result.taskId() == null) {
            return;
        }
        patientRawDataCollectTaskService.updateChangeTypes(result.taskId(), result.changeTypes());
        if (!result.isSuccessLike()) {
            patientRawDataCollectTaskService.markFailed(result.taskId(), result.message());
            infectionDailyJobLogService.log(InfectionJobStage.LOAD, InfectionJobStatus.ERROR, result.reqno(), result.message());
            return;
        }
        patientRawDataCollectTaskService.markSuccess(result.taskId(), result.message());
    }

    private List<Long> extractStructTaskIds(List<PatientRawDataChangeTaskEntity> taskGroup) {
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

    private List<Long> extractEventTaskIds(InfectionEventTaskEntity taskEntity) {
        if (taskEntity == null || taskEntity.getId() == null) {
            return List.of();
        }
        return List.of(taskEntity.getId());
    }

    private String resolveStructReqno(List<PatientRawDataChangeTaskEntity> taskGroup) {
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

    private String resolveEventReqno(InfectionEventTaskEntity taskEntity) {
        if (taskEntity == null || !StringUtils.hasText(taskEntity.getReqno())) {
            return null;
        }
        return taskEntity.getReqno().trim();
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

    private PatientRawDataEntity collectFreshRawData(InfectionEventTaskEntity taskEntity) {
        if (taskEntity == null || taskEntity.getPatientRawDataId() == null || taskEntity.getRawDataLastTime() == null) {
            return null;
        }
        PatientRawDataEntity rawData = patientService.getRawDataById(taskEntity.getPatientRawDataId());
        if (rawData == null || rawData.getLastTime() == null) {
            return null;
        }
        if (!rawData.getLastTime().equals(taskEntity.getRawDataLastTime())) {
            return null;
        }
        return rawData;
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

    private record RawDataProcessResult(InfectionJobStage failedStage, String errorMessage) {

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
                                            int successCount,
                                            int failedCount,
                                            int caseTaskCount,
                                            String lastErrorMessage,
                                            boolean skipped,
                                            String message) {

        private static EventTaskExecutionResult success(List<Long> taskIds, String reqno, int caseTaskCount) {
            String message = caseTaskCount > 0
                    ? "事件抽取成功，已创建caseTask=" + caseTaskCount
                    : "事件抽取成功";
            return new EventTaskExecutionResult(taskIds, reqno, 1, 0, caseTaskCount, null, false, message);
        }

        private static EventTaskExecutionResult failed(List<Long> taskIds, String reqno, String lastErrorMessage) {
            return new EventTaskExecutionResult(taskIds, reqno, 0, 1, 0, lastErrorMessage, false, lastErrorMessage);
        }

        private static EventTaskExecutionResult skipped(List<Long> taskIds, String reqno, String message) {
            return new EventTaskExecutionResult(taskIds, reqno, 0, 0, 0, null, true, message);
        }
    }

    private record CaseTaskExecutionResult(List<Long> taskIds,
                                           String reqno,
                                           int successCount,
                                           int failedCount,
                                           String lastErrorMessage,
                                           String message) {

        private static CaseTaskExecutionResult success(List<Long> taskIds, String reqno) {
            return new CaseTaskExecutionResult(taskIds, reqno, 1, 0, null, "病例重算占位任务完成");
        }

        private static CaseTaskExecutionResult failed(List<Long> taskIds, String reqno, String lastErrorMessage) {
            return new CaseTaskExecutionResult(taskIds, reqno, 0, 1, lastErrorMessage, lastErrorMessage);
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
