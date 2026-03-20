package com.zzhy.yg_ai.ai.orchestrator;

import com.zzhy.yg_ai.ai.agent.AuditAgent;
import com.zzhy.yg_ai.ai.agent.FormatAgent;
import com.zzhy.yg_ai.ai.agent.SummaryAgent;
import com.zzhy.yg_ai.ai.agent.WarningAgent;
import com.zzhy.yg_ai.ai.rule.RuleEngine;
import com.zzhy.yg_ai.ai.tools.LoadDataTool;
import com.zzhy.yg_ai.domain.entity.InfectionAlertEntity;
import com.zzhy.yg_ai.domain.entity.PatientRawDataEntity;
import com.zzhy.yg_ai.domain.entity.PatientSummaryEntity;
import com.zzhy.yg_ai.domain.model.PatientContext;
import com.zzhy.yg_ai.domain.model.InfectionAlert;
import com.zzhy.yg_ai.service.AlertService;
import com.zzhy.yg_ai.service.PatientService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final RuleEngine ruleEngine;
    private final PatientService patientService;
    private final AlertService alertService;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    public InfectionPipeline(LoadDataTool loadDataTool,
                             FormatAgent formatAgent,
                             SummaryAgent summaryAgent,
                             WarningAgent warningAgent,
                             AuditAgent auditAgent,
                             RuleEngine ruleEngine,
                             PatientService patientService,
                             AlertService alertService,
                             StringRedisTemplate stringRedisTemplate,
                             ObjectMapper objectMapper) {
        this.loadDataTool = loadDataTool;
        this.formatAgent = formatAgent;
        this.summaryAgent = summaryAgent;
        this.warningAgent = warningAgent;
        this.auditAgent = auditAgent;
        this.ruleEngine = ruleEngine;
        this.patientService = patientService;
        this.alertService = alertService;
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
        List<PatientRawDataEntity> pendingList = patientService.listPendingStructRawData(20);
        if (pendingList == null || pendingList.isEmpty()) {
            return 0;
        }

        ExecutorService executor = Executors.newFixedThreadPool(6);
        List<CompletableFuture<Integer>> futures = new ArrayList<>();
        for (PatientRawDataEntity rawData : pendingList) {
            CompletableFuture<Integer> task = CompletableFuture.supplyAsync(() -> {
                if (rawData == null || !StringUtils.hasText(rawData.getDataJson())) {
                    return 0;
                }
                try {
                    PatientRawDataEntity inhosdateRaw = patientService.getInhosdateRaw(rawData.getReqno());
                    PatientContext context = formatAgent.format(
                            rawData.getDataJson(),
                            inhosdateRaw != null ? inhosdateRaw.getDataJson() : null
                    );
                    patientService.saveStructDataJson(rawData.getId(), context.getContextJson(), context.getEventJson());
                    return 1;
                } catch (Exception e) {
                    log.error("结构化格式化失败，rowId={}, reqno={}", rawData.getId(), rawData.getReqno(), e);
                    return 0;
                }
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

    public int extractEventsToRedis(int limit) {
        List<String> reqnos = patientService.listReqnosWithUnprocessedStructData(limit);
        if (reqnos == null || reqnos.isEmpty()) {
            return 0;
        }

        int processed = 0;
        for (String reqno : reqnos) {
            List<PatientRawDataEntity> rows = patientService.listUnprocessedStructDataByReqno(reqno);
            for (PatientRawDataEntity row : rows) {
                if (!StringUtils.hasText(row.getReqno()) || !StringUtils.hasText(row.getDataJson())) {
                    continue;
                }
                try {
                    PatientSummaryEntity latestSummary = patientService.getLatestSummary(reqno);
                    String eventsJson = summaryAgent.extractDailyEvents(row, latestSummary);
                    mergeDailyEventsToRedis(row.getReqno(), row.getDataDate(), eventsJson);
                    patientService.markSummaryUpdateTime(row.getReqno(), eventsJson, row.getLastTime());
                    processed++;
                } catch (Exception e) {
                    log.error("事件提取失败，reqno={}, rowId={}", row.getReqno(), row.getId(), e);
                }
            }
        }

        if (processed > 0) {
            log.info("SummaryAgent事件提取完成，reqnoCount={}, processed={}", reqnos.size(), processed);
        }
        return processed;
    }

    public int syncRedisToSummary() {
        List<String> reqnos = patientService.listActiveReqnos();
        int synced = 0;
        for (String reqno : reqnos) {
            if (!StringUtils.hasText(reqno)) {
                continue;
            }
            String timelineJson = buildTimelineJsonFromRedis(reqno);
            if (!StringUtils.hasText(timelineJson) || "{}".equals(timelineJson)) {
                continue;
            }
            PatientSummaryEntity summary = new PatientSummaryEntity();
            summary.setReqno(reqno);
            summary.setSummaryJson(timelineJson);
            summary.setTokenCount(timelineJson.length());
            summary.setUpdateTime(LocalDateTime.now());
            patientService.saveSummary(summary);
            synced++;
        }
        if (synced > 0) {
            log.info("Redis同步PatientSummary完成，synced={}", synced);
        }
        return synced;
    }

    public int evaluateWarnings() {
        List<String> reqnos = patientService.listActiveReqnos();
        int alerted = 0;
        for (String reqno : reqnos) {
            if (!StringUtils.hasText(reqno)) {
                continue;
            }
            String timelineJson = buildTimelineJsonFromRedis(reqno);
            if (!StringUtils.hasText(timelineJson) || "{}".equals(timelineJson)) {
                PatientSummaryEntity latestSummary = patientService.getLatestSummary(reqno);
                if (latestSummary != null) {
                    timelineJson = latestSummary.getSummaryJson();
                }
            }
            if (!StringUtils.hasText(timelineJson)) {
                continue;
            }
            InfectionAlert alert = warningAgent.evaluateTimeline(reqno, timelineJson);
            InfectionAlertEntity entity = new InfectionAlertEntity();
            entity.setReqno(reqno);
            entity.setRiskLevel(alert.getRiskLevel() == null ? "LOW" : alert.getRiskLevel().name());
            entity.setInfectionType(alert.getInfectionType() == null ? "OTHER" : alert.getInfectionType().name());
            entity.setEvidence(alert.getEvidence());
            entity.setAlertTime(alert.getAlertTime() == null ? LocalDateTime.now() : alert.getAlertTime());
            entity.setStatus(alert.getStatus() == null ? "NEW" : alert.getStatus());

            LocalDateTime dayStart = entity.getAlertTime().toLocalDate().atStartOfDay();
            LocalDateTime dayEnd = dayStart.plusDays(1);
            boolean duplicated = alertService.existsAlertInWindow(
                    entity.getReqno(),
                    entity.getRiskLevel(),
                    entity.getInfectionType(),
                    dayStart,
                    dayEnd
            );
            if (duplicated) {
                log.info("预警去重跳过，reqno={}, risk={}, type={}",
                        entity.getReqno(), entity.getRiskLevel(), entity.getInfectionType());
                continue;
            }

            alertService.saveAlert(entity);
            alerted++;
        }
        if (alerted > 0) {
            log.info("WarningAgent预警完成，alerted={}", alerted);
        }
        return alerted;
    }

    private void mergeDailyEventsToRedis(String reqno, LocalDate dataDate, String eventsJson) throws Exception {
        JsonNode node = objectMapper.readTree(eventsJson);
        Map<String, String> fieldMapping = new LinkedHashMap<>();
        fieldMapping.put("symptoms", "symptoms");
        fieldMapping.put("abnormal_labs", "labs");
        fieldMapping.put("vital_abnormal", "labs");
        fieldMapping.put("pathogen_results", "pathogens");
        fieldMapping.put("devices", "devices");
        fieldMapping.put("procedures", "procedures");
        fieldMapping.put("antibiotics", "antibiotics");
        fieldMapping.put("imaging_findings", "imaging");

        String redisKey = REDIS_EVENTS_PREFIX + reqno;
        for (Map.Entry<String, String> entry : fieldMapping.entrySet()) {
            String srcField = entry.getKey();
            String targetField = entry.getValue();
            JsonNode arrNode = node.path(srcField);
            if (!arrNode.isArray() || arrNode.isEmpty()) {
                continue;
            }

            List<Map<String, Object>> incoming = new ArrayList<>();
            for (JsonNode item : arrNode) {
                Map<String, Object> map = objectMapper.convertValue(item, new TypeReference<Map<String, Object>>() {});
                if (!map.containsKey("time") && dataDate != null) {
                    map.put("time", dataDate.toString());
                }
                map.put("dataDate", dataDate == null ? null : dataDate.toString());
                incoming.add(map);
            }

            String existed = stringRedisTemplate.opsForHash().get(redisKey, targetField) == null
                    ? null
                    : String.valueOf(stringRedisTemplate.opsForHash().get(redisKey, targetField));
            List<Map<String, Object>> merged = new ArrayList<>();
            if (StringUtils.hasText(existed)) {
                try {
                    merged.addAll(objectMapper.readValue(existed, new TypeReference<List<Map<String, Object>>>() {}));
                } catch (Exception ignored) {
                    // ignore broken old value
                }
            }
            for (Map<String, Object> item : incoming) {
                String newSig = objectMapper.writeValueAsString(item);
                boolean duplicate = false;
                for (Map<String, Object> oldItem : merged) {
                    String oldSig = objectMapper.writeValueAsString(oldItem);
                    if (oldSig.equals(newSig)) {
                        duplicate = true;
                        break;
                    }
                }
                if (!duplicate) {
                    merged.add(item);
                }
            }
            merged = trimMergedEventsForLength(merged, MAX_EVENT_JSON_LENGTH);
            stringRedisTemplate.opsForHash().put(redisKey, targetField, objectMapper.writeValueAsString(merged));
        }
    }

    private List<Map<String, Object>> trimMergedEventsForLength(List<Map<String, Object>> merged, int maxLength)
            throws Exception {
        if (merged == null || merged.size() <= 1) {
            return merged;
        }
        String serialized = objectMapper.writeValueAsString(merged);
        if (serialized.length() <= maxLength) {
            return merged;
        }

        Map<String, Object> first = merged.get(0);
        List<Map<String, Object>> candidates = new ArrayList<>(merged.subList(1, merged.size()));
        long latestEpoch = Long.MIN_VALUE;
        for (Map<String, Object> item : merged) {
            latestEpoch = Math.max(latestEpoch, extractEventEpoch(item));
        }
        long finalLatestEpoch = latestEpoch == Long.MIN_VALUE ? System.currentTimeMillis() : latestEpoch;

        while (!candidates.isEmpty()) {
            candidates.sort(Comparator.comparingLong(item ->
                    -Math.abs(finalLatestEpoch - extractEventEpoch(item))));
            candidates.remove(0);

            List<Map<String, Object>> current = new ArrayList<>();
            current.add(first);
            current.addAll(candidates);
            String currentJson = objectMapper.writeValueAsString(current);
            if (currentJson.length() <= maxLength) {
                return current;
            }
        }
        return Collections.singletonList(first);
    }

    private long extractEventEpoch(Map<String, Object> item) {
        if (item == null) {
            return Long.MIN_VALUE;
        }
        Object timeObj = item.get("time");
        if (timeObj == null) {
            timeObj = item.get("dataDate");
        }
        if (timeObj == null) {
            return Long.MIN_VALUE;
        }
        String value = String.valueOf(timeObj).trim();
        if (!StringUtils.hasText(value)) {
            return Long.MIN_VALUE;
        }

        List<DateTimeFormatter> dateTimeFormatters = List.of(
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
                DateTimeFormatter.ISO_LOCAL_DATE_TIME
        );
        for (DateTimeFormatter formatter : dateTimeFormatters) {
            try {
                return LocalDateTime.parse(value, formatter).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
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

    private String buildTimelineJsonFromRedis(String reqno) {
        String redisKey = REDIS_EVENTS_PREFIX + reqno;
        Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(redisKey);
        if (entries == null || entries.isEmpty()) {
            return "{}";
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reqno", reqno);
        result.put("symptoms", parseList(entries.get("symptoms")));
        result.put("labs", parseList(entries.get("labs")));
        result.put("pathogens", parseList(entries.get("pathogens")));
        result.put("devices", parseList(entries.get("devices")));
        result.put("procedures", parseList(entries.get("procedures")));
        result.put("antibiotics", parseList(entries.get("antibiotics")));
        result.put("imaging", parseList(entries.get("imaging")));
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            return "{}";
        }
    }

    private List<Map<String, Object>> parseList(Object value) {
        if (value == null) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(String.valueOf(value), new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
