package com.zzhy.yg_ai.ai.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import com.zzhy.yg_ai.ai.agent.SummaryAgent;
import com.zzhy.yg_ai.common.DateTimeUtils;
import com.zzhy.yg_ai.config.InfectionMonitorProperties;
import com.zzhy.yg_ai.config.StructDataFormatProperties;
import com.zzhy.yg_ai.domain.entity.InfectionAlertResultEntity;
import com.zzhy.yg_ai.domain.entity.InfectionCaseSnapshotEntity;
import com.zzhy.yg_ai.domain.entity.InfectionEventTaskEntity;
import com.zzhy.yg_ai.domain.entity.PatientRawDataChangeTaskEntity;
import com.zzhy.yg_ai.domain.entity.PatientRawDataCollectTaskEntity;
import com.zzhy.yg_ai.domain.entity.PatientRawDataEntity;
import com.zzhy.yg_ai.domain.enums.InfectionCaseState;
import com.zzhy.yg_ai.domain.enums.InfectionEventTaskType;
import com.zzhy.yg_ai.domain.enums.InfectionEventTriggerReasonCode;
import com.zzhy.yg_ai.domain.enums.InfectionJobStage;
import com.zzhy.yg_ai.domain.enums.InfectionJobStatus;
import com.zzhy.yg_ai.domain.enums.EvidenceBlockType;
import com.zzhy.yg_ai.domain.enums.PatientCourseDataType;
import com.zzhy.yg_ai.domain.model.EvidenceBlock;
import com.zzhy.yg_ai.domain.model.EvidenceBlockBuildResult;
import com.zzhy.yg_ai.domain.model.InfectionEvidencePacket;
import com.zzhy.yg_ai.domain.model.JudgeDecisionResult;
import com.zzhy.yg_ai.domain.model.LlmEventExtractorResult;
import com.zzhy.yg_ai.domain.model.RawDataCollectResult;
import com.zzhy.yg_ai.service.InfectionAlertResultService;
import com.zzhy.yg_ai.service.InfectionCaseSnapshotService;
import com.zzhy.yg_ai.service.InfectionDailyJobLogService;
import com.zzhy.yg_ai.service.InfectionEventTaskService;
import com.zzhy.yg_ai.service.InfectionEvidencePacketBuilder;
import com.zzhy.yg_ai.service.InfectionEvidenceBlockService;
import com.zzhy.yg_ai.service.InfectionEventPoolService;
import com.zzhy.yg_ai.service.InfectionJudgeService;
import com.zzhy.yg_ai.service.LlmEventExtractorService;
import com.zzhy.yg_ai.service.PatientRawDataChangeTaskService;
import com.zzhy.yg_ai.service.PatientRawDataCollectTaskService;
import com.zzhy.yg_ai.service.PatientService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
public class InfectionPipeline implements DisposableBean {

    private static final int EVENT_EXTRACT_PRIORITY = 50;
    private static final int CASE_RECOMPUTE_PRIORITY = 10;
    private static final int CASE_MAX_WAIT_MINUTES = 30;
    private static final int RESERVED_JDBC_CONNECTIONS = 1;
    private static final AtomicInteger EXECUTOR_THREAD_COUNTER = new AtomicInteger(1);

    private final ExecutorService sharedExecutor;
    private final InfectionMonitorProperties infectionMonitorProperties;
    private final SummaryAgent summaryAgent;
    private final PatientService patientService;
    private final PatientRawDataCollectTaskService patientRawDataCollectTaskService;
    private final PatientRawDataChangeTaskService patientRawDataChangeTaskService;
    private final InfectionEventTaskService infectionEventTaskService;
    private final InfectionDailyJobLogService infectionDailyJobLogService;
    private final InfectionEvidenceBlockService infectionEvidenceBlockService;
    private final LlmEventExtractorService llmEventExtractorService;
    private final InfectionEventPoolService infectionEventPoolService;
    private final InfectionCaseSnapshotService infectionCaseSnapshotService;
    private final InfectionEvidencePacketBuilder infectionEvidencePacketBuilder;
    private final InfectionJudgeService infectionJudgeService;
    private final InfectionAlertResultService infectionAlertResultService;
    private final ObjectMapper objectMapper;
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
                             InfectionEventPoolService infectionEventPoolService,
                             InfectionCaseSnapshotService infectionCaseSnapshotService,
                             InfectionEvidencePacketBuilder infectionEvidencePacketBuilder,
                             InfectionJudgeService infectionJudgeService,
                             InfectionAlertResultService infectionAlertResultService,
                             ObjectMapper objectMapper,
                             StructDataFormatProperties structDataFormatProperties,
                             DataSource dataSource) {
        int configuredThreads = Math.max(1, structDataFormatProperties.getWorkerThreads());
        int maxThreads = resolveExecutorThreads(configuredThreads, dataSource);
        this.sharedExecutor = new ThreadPoolExecutor(
                maxThreads, maxThreads,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                runnable -> {
                    Thread thread = new Thread(runnable);
                    thread.setName("infection-pipeline-" + EXECUTOR_THREAD_COUNTER.getAndIncrement());
                    thread.setDaemon(true);
                    return thread;
                });
        if (this.sharedExecutor instanceof ThreadPoolExecutor threadPoolExecutor) {
            threadPoolExecutor.prestartAllCoreThreads();
        }
        this.infectionMonitorProperties = infectionMonitorProperties;
        this.summaryAgent = summaryAgent;
        this.patientService = patientService;
        this.patientRawDataCollectTaskService = patientRawDataCollectTaskService;
        this.patientRawDataChangeTaskService = patientRawDataChangeTaskService;
        this.infectionEventTaskService = infectionEventTaskService;
        this.infectionDailyJobLogService = infectionDailyJobLogService;
        this.infectionEvidenceBlockService = infectionEvidenceBlockService;
        this.llmEventExtractorService = llmEventExtractorService;
        this.infectionEventPoolService = infectionEventPoolService;
        this.infectionCaseSnapshotService = infectionCaseSnapshotService;
        this.infectionEvidencePacketBuilder = infectionEvidencePacketBuilder;
        this.infectionJudgeService = infectionJudgeService;
        this.infectionAlertResultService = infectionAlertResultService;
        this.objectMapper = objectMapper;
        this.structDataFormatProperties = structDataFormatProperties;
        log.info("初始化院感处理执行器，configuredThreads={}, effectiveThreads={}", configuredThreads, maxThreads);
    }

    @Override
    public void destroy() {
        sharedExecutor.shutdown();
        try {
            if (!sharedExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                sharedExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            sharedExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public int enqueueRawDataTasks(List<String> reqnos, LocalDateTime sourceBatchTime) {
        return patientRawDataCollectTaskService.enqueueBatch(reqnos, sourceBatchTime);
    }

    public int processPendingRawDataTasks() {
        List<PatientRawDataCollectTaskEntity> tasks =
                patientRawDataCollectTaskService.claimPendingTasks(infectionMonitorProperties.getBatchSize());
        int processedCount = executeParallelTasks(
                tasks,
                this::processCollectTask,
                this::finalizeCollectTask,
                result -> result.isSuccessLike() ? 1 : 0
        );

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

        int formattedCount = executeParallelTasks(
                new ArrayList<>(tasksByReqno.values()),
                this::processStructTask,
                this::finalizeStructTask,
                StructTaskExecutionResult::successCount
        );

        if (formattedCount > 0) {
            log.info("患者结构化格式化完成，formattedCount={}", formattedCount);
        }
        return formattedCount;
    }

    public int processPendingEventData() {
        List<InfectionEventTaskEntity> claimedTasks =
                infectionEventTaskService.claimPendingTasks(InfectionEventTaskType.EVENT_EXTRACT, structDataFormatProperties.getBatchSize());
        int extractedCount = executeParallelTasks(
                claimedTasks,
                this::processEventTask,
                this::finalizeEventTask,
                EventTaskExecutionResult::successCount
        );

        if (extractedCount > 0) {
            log.info("患者事件抽取完成，extractedCount={}", extractedCount);
        }
        return extractedCount;
    }

    public int processPendingCaseData() {
        List<InfectionEventTaskEntity> claimedTasks =
                infectionEventTaskService.claimPendingTasks(InfectionEventTaskType.CASE_RECOMPUTE, structDataFormatProperties.getBatchSize());
        int processedCount = executeParallelTasks(
                claimedTasks,
                this::processCaseTask,
                this::finalizeCaseTask,
                CaseTaskExecutionResult::successCount
        );

        if (processedCount > 0) {
            log.info("病例重算任务完成，processedCount={}", processedCount);
        }
        return processedCount;
    }

    private int resolveExecutorThreads(int configuredThreads, DataSource dataSource) {
        if (!(dataSource instanceof HikariDataSource hikariDataSource)) {
            return configuredThreads;
        }
        int jdbcPoolSize = Math.max(1, hikariDataSource.getMaximumPoolSize());
        int safeThreads = Math.max(1, jdbcPoolSize - RESERVED_JDBC_CONNECTIONS);
        int effectiveThreads = Math.min(configuredThreads, safeThreads);
        if (effectiveThreads < configuredThreads) {
            log.warn("院感处理并发已下调以匹配 JDBC 连接池，configuredThreads={}, jdbcPoolSize={}, reservedConnections={}, effectiveThreads={}",
                    configuredThreads, jdbcPoolSize, RESERVED_JDBC_CONNECTIONS, effectiveThreads);
        }
        return effectiveThreads;
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
            String timelineWindowJson = patientService.buildSummaryWindowJson(reqno, rawData.getDataDate());
            LlmEventExtractorResult extractResult = extractEvents(rawData, timelineWindowJson, taskEntity);
            int caseTaskCount = 0;
            if (extractResult != null && !extractResult.persistedEvents().isEmpty()) {
                infectionEventTaskService.upsertCaseRecomputeTask(
                        reqno,
                        rawData.getId(),
                        rawData.getDataDate(),
                        rawData.getLastTime(),
                        taskEntity.getSourceBatchTime() == null ? LocalDateTime.now() : taskEntity.getSourceBatchTime(),
                        0,
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

    private LlmEventExtractorResult extractEvents(PatientRawDataEntity rawData,
                                                  String timelineWindowJson,
                                                  InfectionEventTaskEntity taskEntity) {
        EvidenceBlockBuildResult buildResult = infectionEvidenceBlockService.buildBlocks(rawData, timelineWindowJson);
        if (buildResult == null || buildResult.primaryBlocks().isEmpty()) {
            return null;
        }
        List<EvidenceBlock> primaryBlocks = selectPrimaryBlocks(buildResult, taskEntity);
        if (primaryBlocks.isEmpty()) {
            return null;
        }
        return llmEventExtractorService.extractAndSave(buildResult, primaryBlocks);
    }

    private CaseTaskExecutionResult processCaseTask(InfectionEventTaskEntity taskEntity) {
        List<Long> taskIds = extractEventTaskIds(taskEntity);
        String reqno = resolveEventReqno(taskEntity);
        if (!StringUtils.hasText(reqno)) {
            return CaseTaskExecutionResult.failed(taskIds, reqno, "reqno为空");
        }
        try {
            InfectionCaseSnapshotEntity snapshot = infectionCaseSnapshotService.getOrInit(reqno);
            Long latestEventPoolVersion = infectionEventPoolService.getLatestActiveEventVersion(reqno);
            long snapshotVersion = snapshot == null || snapshot.getLastEventPoolVersion() == null
                    ? 0L : snapshot.getLastEventPoolVersion();
            if (latestEventPoolVersion == null || latestEventPoolVersion <= snapshotVersion) {
                return CaseTaskExecutionResult.skipped(taskIds, reqno, "无新增事件版本，跳过病例重算");
            }

            LocalDateTime now = DateTimeUtils.now();
            if (taskEntity.getDebounceUntil() != null
                    && now.isBefore(taskEntity.getDebounceUntil())
                    && (taskEntity.getFirstTriggeredAt() == null
                    || now.isBefore(taskEntity.getFirstTriggeredAt().plusMinutes(CASE_MAX_WAIT_MINUTES)))) {
                infectionEventTaskService.reschedule(taskIds, taskEntity.getDebounceUntil(), "仍在病例重算防抖窗口内");
                return CaseTaskExecutionResult.rescheduled(taskIds, reqno, "病例重算延后执行");
            }

            InfectionEvidencePacket packet = infectionEvidencePacketBuilder.build(reqno, now);
            JudgeDecisionResult decision = infectionJudgeService.judge(packet, now);
            persistAlertResult(snapshot, taskEntity, packet, decision);
            updateSnapshot(snapshot, latestEventPoolVersion, now, decision);
            log.info("病例重算完成，taskId={}, reqno={}, decisionStatus={}, warningLevel={}",
                    taskEntity.getId(), reqno, decision.decisionStatus(), decision.warningLevel());
            return CaseTaskExecutionResult.success(taskIds, reqno);
        } catch (Exception e) {
            log.error("病例重算任务失败，taskId={}, reqno={}", taskEntity.getId(), reqno, e);
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
        if (result.rescheduled()) {
            return;
        }
        if (result.skipped()) {
            infectionEventTaskService.markSkipped(result.taskIds(), result.message());
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
        if (!result.isSuccessLike()) {
            patientRawDataCollectTaskService.markFailed(result.taskId(), result.message(), result.changeTypes());
            infectionDailyJobLogService.log(InfectionJobStage.LOAD, InfectionJobStatus.ERROR, result.reqno(), result.message());
            return;
        }
        patientRawDataCollectTaskService.markSuccess(result.taskId(), result.message(), result.changeTypes());
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

    private <T, R> int executeParallelTasks(List<T> tasks,
                                            Function<T, R> processor,
                                            Consumer<R> finalizer,
                                            ToIntFunction<R> successCounter) {
        if (tasks == null || tasks.isEmpty()) {
            return 0;
        }
        List<CompletableFuture<R>> futures = new ArrayList<>();
        for (T task : tasks) {
            futures.add(CompletableFuture.supplyAsync(() -> processor.apply(task), sharedExecutor));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        return futures.stream()
                .map(CompletableFuture::join)
                .peek(finalizer)
                .mapToInt(successCounter)
                .sum();
    }

    private List<EvidenceBlock> selectPrimaryBlocks(EvidenceBlockBuildResult buildResult, InfectionEventTaskEntity taskEntity) {
        if (buildResult == null) {
            return List.of();
        }
        Set<InfectionEventTriggerReasonCode> triggerReasons = InfectionEventTriggerReasonCode.fromCsv(
                taskEntity == null ? null : taskEntity.getTriggerReasonCodes()
        );
        EnumSet<PatientCourseDataType> changedTypes = PatientCourseDataType.parseCsv(
                taskEntity == null ? null : taskEntity.getChangedTypes()
        );
        if (triggerReasons.isEmpty() && changedTypes.isEmpty()) {
            return buildResult.primaryBlocks();
        }

        boolean includeStructuredFact = hasAny(triggerReasons,
                InfectionEventTriggerReasonCode.LAB_RESULT_CHANGED,
                InfectionEventTriggerReasonCode.MICROBE_CHANGED,
                InfectionEventTriggerReasonCode.IMAGING_CHANGED,
                InfectionEventTriggerReasonCode.ANTIBIOTIC_OR_ORDER_CHANGED,
                InfectionEventTriggerReasonCode.OPERATION_CHANGED,
                InfectionEventTriggerReasonCode.TRANSFER_CHANGED,
                InfectionEventTriggerReasonCode.VITAL_SIGN_CHANGED)
                || hasAny(changedTypes,
                PatientCourseDataType.FULL_PATIENT,
                PatientCourseDataType.DIAGNOSIS,
                PatientCourseDataType.BODY_SURFACE,
                PatientCourseDataType.DOCTOR_ADVICE,
                PatientCourseDataType.LAB_TEST,
                PatientCourseDataType.USE_MEDICINE,
                PatientCourseDataType.VIDEO_RESULT,
                PatientCourseDataType.TRANSFER,
                PatientCourseDataType.OPERATION,
                PatientCourseDataType.MICROBE);
        boolean includeClinicalText = triggerReasons.contains(InfectionEventTriggerReasonCode.ILLNESS_COURSE_CHANGED)
                || hasAny(changedTypes, PatientCourseDataType.FULL_PATIENT, PatientCourseDataType.ILLNESS_COURSE);
        boolean includeMidSemantic = includeClinicalText || hasAny(triggerReasons,
                InfectionEventTriggerReasonCode.ANTIBIOTIC_OR_ORDER_CHANGED,
                InfectionEventTriggerReasonCode.OPERATION_CHANGED,
                InfectionEventTriggerReasonCode.TRANSFER_CHANGED)
                || hasAny(changedTypes,
                PatientCourseDataType.FULL_PATIENT,
                PatientCourseDataType.ILLNESS_COURSE,
                PatientCourseDataType.DOCTOR_ADVICE,
                PatientCourseDataType.USE_MEDICINE,
                PatientCourseDataType.TRANSFER,
                PatientCourseDataType.OPERATION);

        List<EvidenceBlock> selected = new ArrayList<>();
        if (includeStructuredFact) {
            selected.addAll(filterByBlockType(buildResult.structuredFactBlocks(), EvidenceBlockType.STRUCTURED_FACT));
        }
        if (includeClinicalText) {
            selected.addAll(filterByBlockType(buildResult.clinicalTextBlocks(), EvidenceBlockType.CLINICAL_TEXT));
        }
        if (includeMidSemantic) {
            selected.addAll(filterByBlockType(buildResult.midSemanticBlocks(), EvidenceBlockType.MID_SEMANTIC));
        }
        if (selected.isEmpty()) {
            return buildResult.primaryBlocks();
        }
        return List.copyOf(selected);
    }

    private boolean hasAny(Set<InfectionEventTriggerReasonCode> triggerReasons, InfectionEventTriggerReasonCode... expected) {
        if (triggerReasons == null || triggerReasons.isEmpty()) {
            return false;
        }
        for (InfectionEventTriggerReasonCode code : expected) {
            if (code != null && triggerReasons.contains(code)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasAny(EnumSet<PatientCourseDataType> changedTypes, PatientCourseDataType... expected) {
        if (changedTypes == null || changedTypes.isEmpty()) {
            return false;
        }
        for (PatientCourseDataType type : expected) {
            if (type != null && changedTypes.contains(type)) {
                return true;
            }
        }
        return false;
    }

    private List<EvidenceBlock> filterByBlockType(List<EvidenceBlock> blocks, EvidenceBlockType blockType) {
        if (blocks == null || blocks.isEmpty()) {
            return List.of();
        }
        List<EvidenceBlock> result = new ArrayList<>();
        for (EvidenceBlock block : blocks) {
            if (block != null && block.blockType() == blockType) {
                result.add(block);
            }
        }
        return result;
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
                                           boolean skipped,
                                           boolean rescheduled,
                                           String lastErrorMessage,
                                           String message) {

        private static CaseTaskExecutionResult success(List<Long> taskIds, String reqno) {
            return new CaseTaskExecutionResult(taskIds, reqno, 1, 0, false, false, null, "病例重算完成");
        }

        private static CaseTaskExecutionResult failed(List<Long> taskIds, String reqno, String lastErrorMessage) {
            return new CaseTaskExecutionResult(taskIds, reqno, 0, 1, false, false, lastErrorMessage, lastErrorMessage);
        }

        private static CaseTaskExecutionResult skipped(List<Long> taskIds, String reqno, String message) {
            return new CaseTaskExecutionResult(taskIds, reqno, 0, 0, true, false, null, message);
        }

        private static CaseTaskExecutionResult rescheduled(List<Long> taskIds, String reqno, String message) {
            return new CaseTaskExecutionResult(taskIds, reqno, 0, 0, false, true, null, message);
        }
    }

    private void persistAlertResult(InfectionCaseSnapshotEntity snapshot,
                                    InfectionEventTaskEntity taskEntity,
                                    InfectionEvidencePacket packet,
                                    JudgeDecisionResult decision) {
        InfectionAlertResultEntity result = new InfectionAlertResultEntity();
        result.setReqno(taskEntity.getReqno());
        result.setDataDate(taskEntity.getDataDate());
        result.setResultVersion(decision.resultVersion());
        result.setAlertStatus(decision.decisionStatus());
        result.setOverallRiskLevel(decision.warningLevel());
        result.setPrimarySite(decision.primarySite());
        result.setNewOnsetFlag(decision.newOnsetFlag());
        result.setAfter48hFlag(decision.after48hFlag());
        result.setProcedureRelatedFlag(decision.procedureRelatedFlag());
        result.setDeviceRelatedFlag(decision.deviceRelatedFlag());
        result.setInfectionPolarity(decision.infectionPolarity());
        result.setSourceSnapshotId(snapshot == null ? null : snapshot.getId());
        result.setResultJson(writeJson(decision));
        result.setDiffJson(writeJson(packet));
        infectionAlertResultService.saveResult(result);
    }

    private void updateSnapshot(InfectionCaseSnapshotEntity snapshot,
                                Long latestEventPoolVersion,
                                LocalDateTime judgeTime,
                                JudgeDecisionResult decision) {
        if (snapshot == null || decision == null) {
            return;
        }
        snapshot.setCaseState(decision.decisionStatus());
        snapshot.setWarningLevel(decision.warningLevel());
        snapshot.setPrimarySite(decision.primarySite());
        snapshot.setNosocomialLikelihood(decision.nosocomialLikelihood());
        snapshot.setCurrentNewOnsetFlag(decision.newOnsetFlag());
        snapshot.setCurrentAfter48hFlag(decision.after48hFlag());
        snapshot.setCurrentProcedureRelatedFlag(decision.procedureRelatedFlag());
        snapshot.setCurrentDeviceRelatedFlag(decision.deviceRelatedFlag());
        snapshot.setCurrentInfectionPolarity(decision.infectionPolarity());
        snapshot.setActiveEventKeysJson(writeJson(decision.newSupportingKeys()));
        snapshot.setActiveRiskKeysJson(writeJson(decision.newRiskKeys()));
        snapshot.setActiveAgainstKeysJson(writeJson(decision.newAgainstKeys()));
        snapshot.setLastJudgeTime(judgeTime);
        snapshot.setLastResultVersion(decision.resultVersion());
        snapshot.setLastEventPoolVersion(latestEventPoolVersion == null ? 0L : latestEventPoolVersion);
        snapshot.setJudgeDebounceUntil(null);
        InfectionCaseState state = InfectionCaseState.fromCodeOrDefault(decision.decisionStatus(), null);
        if (state == InfectionCaseState.CANDIDATE && snapshot.getLastCandidateSince() == null) {
            snapshot.setLastCandidateSince(judgeTime);
        }
        if (state == InfectionCaseState.WARNING && snapshot.getLastWarningSince() == null) {
            snapshot.setLastWarningSince(judgeTime);
        }
        if (state != null && !state.isActiveRisk()) {
            snapshot.setLastCandidateSince(null);
        }
        infectionCaseSnapshotService.saveOrUpdateSnapshot(snapshot);
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return null;
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
