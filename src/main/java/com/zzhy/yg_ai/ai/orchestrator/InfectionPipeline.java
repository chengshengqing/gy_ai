package com.zzhy.yg_ai.ai.orchestrator;

import com.zzhy.yg_ai.ai.agent.AuditAgent;
import com.zzhy.yg_ai.ai.agent.FormatAgent;
import com.zzhy.yg_ai.ai.agent.SummaryAgent;
import com.zzhy.yg_ai.ai.agent.WarningAgent;
import com.zzhy.yg_ai.ai.tools.LoadDataTool;
import com.zzhy.yg_ai.domain.entity.PatientRawDataEntity;
import com.zzhy.yg_ai.domain.entity.PatientSummaryEntity;
import com.zzhy.yg_ai.service.PatientService;
import com.fasterxml.jackson.core.type.TypeReference;
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
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
public class InfectionPipeline {

    private static final String REDIS_EVENTS_PREFIX = "patient_events:";
    private static final int MAX_EVENT_JSON_LENGTH = 6000;

    private final LoadDataTool loadDataTool;
    private final FormatAgent formatAgent;
    private final SummaryAgent summaryAgent;
    private final WarningAgent warningAgent;
    private final AuditAgent auditAgent;
    private final PatientService patientService;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    public InfectionPipeline(LoadDataTool loadDataTool,
                             FormatAgent formatAgent,
                             SummaryAgent summaryAgent,
                             WarningAgent warningAgent,
                             AuditAgent auditAgent,
                             PatientService patientService,
                             StringRedisTemplate stringRedisTemplate,
                             ObjectMapper objectMapper) {
        this.loadDataTool = loadDataTool;
        this.formatAgent = formatAgent;
        this.summaryAgent = summaryAgent;
        this.warningAgent = warningAgent;
        this.auditAgent = auditAgent;
        this.patientService = patientService;
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
    }

    public void processPatient(String reqno) {
        if (!StringUtils.hasText(reqno)) {
            log.warn("processPatient 跳过，reqno为空");
            return;
        }
        loadDataTool.loadPatientRawData(reqno);
        log.info("患者语义块采集完成，reqno={}", reqno);
    }

    public int processPendingStructData() {
        List<String> pendingReqnos = patientService.listPendingStructRawData(20);
        if (pendingReqnos == null || pendingReqnos.isEmpty()) {
            return 0;
        }

        ExecutorService executor = Executors.newFixedThreadPool(6);
        List<CompletableFuture<Integer>> futures = new ArrayList<>();
        for (String reqno : pendingReqnos) {
            CompletableFuture<Integer> task = CompletableFuture.supplyAsync(() -> {
                if (!StringUtils.hasText(reqno)) {
                    return 0;
                }
                List<PatientRawDataEntity> rows = patientService.listPendingStructRawData(reqno);
                if (rows == null || rows.isEmpty()) {
                    return 0;
                }
                String firstCourse = patientService.getInhosdateRaw(reqno);
                PatientSummaryEntity latestSummary = patientService.getLatestSummary(reqno);
                int successCount = 0;
                for (PatientRawDataEntity rawData : rows) {
                    if (rawData == null || !StringUtils.hasText(rawData.getDataJson())) {
                        continue;
                    }
                    if (!StringUtils.hasText(rawData.getFilterDataJson())) {
                        PatientRawDataEntity filterRaw = formatAgent.filterIllnessCourse(firstCourse, rawData);
                        rawData.setFilterDataJson(filterRaw.getFilterDataJson());
                        patientService.saveFilterJson(rawData.getId(), rawData.getFilterDataJson());
                    }

                    try {
                        SummaryAgent.DailyIllnessResult dailyIllnessResult = summaryAgent.extractDailyIllness(latestSummary, rawData);
                        String sortedSummaryJson = sortTimelineByTimeAsc(dailyIllnessResult.updatedSummaryJson());
                        PatientSummaryEntity newSummary = new PatientSummaryEntity();
                        newSummary.setReqno(reqno);
                        newSummary.setSummaryJson(sortedSummaryJson);
                        newSummary.setTokenCount(sortedSummaryJson.length());
                        newSummary.setUpdateTime(rawData.getLastTime() == null ? LocalDateTime.now() : rawData.getLastTime());

                        patientService.saveStructDataJson(rawData.getId(), dailyIllnessResult.structDataJson(), null);
                        patientService.saveOrUpdateLatestSummary(newSummary);
                        latestSummary = newSummary;
                        successCount++;
                    } catch (Exception e) {
                        log.error("病程增量摘要失败，rowId={}, reqno={}", rawData.getId(), rawData.getReqno(), e);
                    }
                }
                return successCount;
            }, executor);
            futures.add(task);
        }

        int formattedCount;
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            formattedCount = futures.stream().mapToInt(CompletableFuture::join).sum();
        } finally {
            executor.shutdown();
        }

        if (formattedCount > 0) {
            log.info("患者结构化格式化完成，formattedCount={}", formattedCount);
        }
        return formattedCount;
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

}
