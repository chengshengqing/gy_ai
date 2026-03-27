package com.zzhy.yg_ai.ai.orchestrator;

import com.zzhy.yg_ai.ai.agent.SummaryAgent;
import com.zzhy.yg_ai.config.InfectionMonitorProperties;
import com.zzhy.yg_ai.config.StructDataFormatProperties;
import com.zzhy.yg_ai.domain.entity.PatientRawDataCollectTaskEntity;
import com.zzhy.yg_ai.domain.entity.PatientRawDataEntity;
import com.zzhy.yg_ai.domain.entity.PatientSummaryEntity;
import com.zzhy.yg_ai.domain.entity.PatientStructDataTaskEntity;
import com.zzhy.yg_ai.domain.enums.InfectionJobStage;
import com.zzhy.yg_ai.domain.enums.InfectionJobStatus;
import com.zzhy.yg_ai.domain.model.RawDataCollectResult;
import com.zzhy.yg_ai.service.InfectionDailyJobLogService;
import com.zzhy.yg_ai.service.PatientRawDataCollectTaskService;
import com.zzhy.yg_ai.service.PatientService;
import com.zzhy.yg_ai.service.PatientStructDataTaskService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
/**
 * 当前主链路编排入口，仅负责患者原始数据采集和结构化摘要流程。
 * 预留的院感预警、审核等能力暂不在此类中启用。
 */
public class InfectionPipeline {

    private final InfectionMonitorProperties infectionMonitorProperties;
    private final SummaryAgent summaryAgent;
    private final PatientService patientService;
    private final PatientRawDataCollectTaskService patientRawDataCollectTaskService;
    private final PatientStructDataTaskService patientStructDataTaskService;
    private final InfectionDailyJobLogService infectionDailyJobLogService;
    private final StructDataFormatProperties structDataFormatProperties;
    private final ObjectMapper objectMapper;

    public InfectionPipeline(InfectionMonitorProperties infectionMonitorProperties,
                             SummaryAgent summaryAgent,
                             PatientService patientService,
                             PatientRawDataCollectTaskService patientRawDataCollectTaskService,
                             PatientStructDataTaskService patientStructDataTaskService,
                             InfectionDailyJobLogService infectionDailyJobLogService,
                             StructDataFormatProperties structDataFormatProperties,
                             ObjectMapper objectMapper) {
        this.infectionMonitorProperties = infectionMonitorProperties;
        this.summaryAgent = summaryAgent;
        this.patientService = patientService;
        this.patientRawDataCollectTaskService = patientRawDataCollectTaskService;
        this.patientStructDataTaskService = patientStructDataTaskService;
        this.infectionDailyJobLogService = infectionDailyJobLogService;
        this.structDataFormatProperties = structDataFormatProperties;
        this.objectMapper = objectMapper;
    }

    public int enqueueRawDataTasks(List<String> reqnos) {
        return patientRawDataCollectTaskService.enqueueBatch(reqnos, LocalDateTime.now());
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
        List<PatientStructDataTaskEntity> tasks =
                patientStructDataTaskService.claimPendingTasks(structDataFormatProperties.getBatchSize());
        if (tasks == null || tasks.isEmpty()) {
            return 0;
        }

        ExecutorService executor = Executors.newFixedThreadPool(Math.max(1, structDataFormatProperties.getWorkerThreads()));
        List<CompletableFuture<StructTaskExecutionResult>> futures = new ArrayList<>();
        for (PatientStructDataTaskEntity taskEntity : tasks) {
            CompletableFuture<StructTaskExecutionResult> task =
                    CompletableFuture.supplyAsync(() -> processStructTask(taskEntity), executor);
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

    private StructTaskExecutionResult processStructTask(PatientStructDataTaskEntity taskEntity) {
        String reqno = taskEntity == null ? null : taskEntity.getReqno();
        LocalDate replayFromDate = taskEntity == null ? null : taskEntity.getReplayFromDate();
        if (!StringUtils.hasText(reqno)) {
            return StructTaskExecutionResult.failed(taskEntity == null ? null : taskEntity.getId(),
                    reqno, 0, "reqno为空");
        }

        List<PatientRawDataEntity> rows = patientService.listPendingStructRawData(reqno, replayFromDate);
        if (rows == null || rows.isEmpty()) {
            return StructTaskExecutionResult.success(taskEntity.getId(), reqno, 0);
        }

        PatientSummaryEntity latestSummary = patientService.getLatestSummary(reqno);
        int successCount = 0;
        int failedCount = 0;
        String lastErrorMessage = null;
        for (PatientRawDataEntity rawData : rows) {
            PatientSummaryEntity updatedSummary = processPendingRawData(reqno, latestSummary, rawData);
            if (updatedSummary == null) {
                failedCount++;
                lastErrorMessage = "存在未完成的 rawData 行，需重试";
                continue;
            }
            latestSummary = updatedSummary;
            successCount++;
        }
        return new StructTaskExecutionResult(taskEntity.getId(), reqno, rows.size(), successCount, failedCount, lastErrorMessage);
    }

    private PatientSummaryEntity processPendingRawData(String reqno,
                                                       PatientSummaryEntity latestSummary,
                                                       PatientRawDataEntity rawData) {
        if (rawData == null || !StringUtils.hasText(rawData.getDataJson())) {
            return null;
        }

        try {
            SummaryAgent.DailyIllnessResult dailyIllnessResult = summaryAgent.extractDailyIllness(latestSummary, rawData);
            PatientSummaryEntity newSummary = buildUpdatedSummary(reqno, rawData, dailyIllnessResult.updatedSummaryJson());
            patientService.saveStructDataJson(rawData.getId(), dailyIllnessResult.structDataJson(), null);
            patientService.saveOrUpdateLatestSummary(newSummary);
            return newSummary;
        } catch (Exception e) {
            log.error("病程增量摘要失败，rowId={}, reqno={}", rawData.getId(), rawData.getReqno(), e);
            return null;
        }
    }

    private void finalizeStructTask(StructTaskExecutionResult result) {
        if (result == null || result.taskId() == null) {
            return;
        }
        if (result.failedCount() > 0) {
            String message = StringUtils.hasText(result.lastErrorMessage())
                    ? result.lastErrorMessage()
                    : "部分结构化处理失败";
            patientStructDataTaskService.markFailed(result.taskId(), message);
            infectionDailyJobLogService.log(InfectionJobStage.NORMALIZE,
                    InfectionJobStatus.ERROR,
                    result.reqno(),
                    message);
            return;
        }
        String message = result.totalRows() == 0
                ? "无待处理结构化数据"
                : "结构化处理成功，successCount=" + result.successCount();
        patientStructDataTaskService.markSuccess(result.taskId(), message);
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

    private PatientSummaryEntity buildUpdatedSummary(String reqno,
                                                    PatientRawDataEntity rawData,
                                                    String updatedSummaryJson) {
        String sortedSummaryJson = sortTimelineByTimeAsc(updatedSummaryJson);
        PatientSummaryEntity newSummary = new PatientSummaryEntity();
        newSummary.setReqno(reqno);
        newSummary.setSummaryJson(sortedSummaryJson);
        newSummary.setTokenCount(sortedSummaryJson.length());
        newSummary.setUpdateTime(rawData.getLastTime() == null ? LocalDateTime.now() : rawData.getLastTime());
        return newSummary;
    }


    private String sortTimelineByTimeAsc(String summaryJson) {
        if (!StringUtils.hasText(summaryJson)) {
            return summaryJson;
        }
        try {
            JsonNode root = objectMapper.readTree(summaryJson);
            if (root == null) {
                return summaryJson;
            }
            if (root.isArray()) {
                ArrayNode sorted = sortTimelineArray((ArrayNode) root);
                return objectMapper.writeValueAsString(sorted);
            }
            if (root.isObject()) {
                ObjectNode objectNode = (ObjectNode) root;
                JsonNode timelineNode = objectNode.get("timeline");
                if (timelineNode != null && timelineNode.isArray()) {
                    objectNode.set("timeline", sortTimelineArray((ArrayNode) timelineNode));
                }
                return objectMapper.writeValueAsString(objectNode);
            }
            return summaryJson;
        } catch (Exception e) {
            log.warn("timeline排序失败，使用原摘要");
            return summaryJson;
        }
    }

    private ArrayNode sortTimelineArray(ArrayNode timelineArray) {
        if (timelineArray == null || timelineArray.size() <= 1) {
            return timelineArray;
        }
        List<JsonNode> items = new ArrayList<>();
        timelineArray.forEach(items::add);
        items.sort((left, right) -> {
            long leftEpoch = extractTimelineNodeEpoch(left);
            long rightEpoch = extractTimelineNodeEpoch(right);
            if (leftEpoch == rightEpoch) {
                return 0;
            }
            if (leftEpoch == Long.MIN_VALUE) {
                return 1;
            }
            if (rightEpoch == Long.MIN_VALUE) {
                return -1;
            }
            return Long.compare(leftEpoch, rightEpoch);
        });
        ArrayNode sorted = objectMapper.createArrayNode();
        items.forEach(sorted::add);
        return sorted;
    }

    private long extractTimelineNodeEpoch(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Long.MIN_VALUE;
        }
        List<String> keys = List.of("time", "dataDate", "date", "updateTime");
        for (String key : keys) {
            JsonNode valueNode = node.get(key);
            if (valueNode == null || valueNode.isNull()) {
                continue;
            }
            String value = valueNode.asText();
            if (!StringUtils.hasText(value)) {
                continue;
            }
            long epoch = parseTimeToEpoch(value.trim());
            if (epoch != Long.MIN_VALUE) {
                return epoch;
            }
        }
        return Long.MIN_VALUE;
    }

    private long parseTimeToEpoch(String value) {
        List<DateTimeFormatter> dateTimeFormatters = List.of(
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
                DateTimeFormatter.ISO_LOCAL_DATE_TIME
        );
        for (DateTimeFormatter formatter : dateTimeFormatters) {
            try {
                return LocalDateTime.parse(value, formatter)
                        .atZone(java.time.ZoneId.systemDefault())
                        .toInstant()
                        .toEpochMilli();
            } catch (DateTimeParseException ignored) {
                // try next format
            }
        }
        try {
            return LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE)
                    .atTime(LocalTime.MIN)
                    .atZone(java.time.ZoneId.systemDefault())
                    .toInstant().toEpochMilli();
        } catch (DateTimeParseException ignored) {
            return Long.MIN_VALUE;
        }
    }

    private record StructTaskExecutionResult(Long taskId,
                                             String reqno,
                                             int totalRows,
                                             int successCount,
                                             int failedCount,
                                             String lastErrorMessage) {

        private static StructTaskExecutionResult success(Long taskId, String reqno, int successCount) {
            return new StructTaskExecutionResult(taskId, reqno, successCount, successCount, 0, null);
        }

        private static StructTaskExecutionResult failed(Long taskId, String reqno, int totalRows, String lastErrorMessage) {
            return new StructTaskExecutionResult(taskId, reqno, totalRows, 0, Math.max(1, totalRows), lastErrorMessage);
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
