package com.zzhy.yg_ai.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzhy.yg_ai.common.InfectionRedisKeys;
import com.zzhy.yg_ai.config.InfectionMonitorProperties;
import com.zzhy.yg_ai.domain.enums.SummaryContextType;
import com.zzhy.yg_ai.service.SummaryContextCacheService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class SummaryContextCacheServiceImpl implements SummaryContextCacheService {

    private static final int DEFAULT_WINDOW_DAYS = 7;
    private static final int MAX_CHANGE_ITEMS = 8;

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final InfectionMonitorProperties infectionMonitorProperties;

    @Override
    public String getOrBuildEventExtractorContext(String reqno, LocalDate anchorDate) {
        if (!StringUtils.hasText(reqno) || anchorDate == null) {
            return null;
        }
        String trimmedReqno = reqno.trim();
        String cacheKey = InfectionRedisKeys.patientSummaryContext(SummaryContextType.EVENT_EXTRACTOR_CONTEXT, trimmedReqno);
        String cached = stringRedisTemplate.opsForValue().get(cacheKey);
        return buildSummary(trimmedReqno, anchorDate, cached);
    }

    @Override
    public void refreshEventExtractorContextDay(String reqno, LocalDate dataDate, String eventJson) {
        if (!StringUtils.hasText(reqno) || dataDate == null) {
            return;
        }
        String trimmedReqno = reqno.trim();
        String cacheKey = InfectionRedisKeys.patientSummaryContext(SummaryContextType.EVENT_EXTRACTOR_CONTEXT, trimmedReqno);
        List<String> existingChanges = readCachedChanges(stringRedisTemplate.opsForValue().get(cacheKey));
        List<CacheChangeLine> mergedChanges = new ArrayList<>();
        int order = 0;
        for (String line : existingChanges) {
            if (!StringUtils.hasText(line)) {
                continue;
            }
            String normalized = normalizeLine(line);
            if (!StringUtils.hasText(normalized)) {
                continue;
            }
            LocalDate lineDate = extractLineDate(normalized);
            if (dataDate.equals(lineDate)) {
                continue;
            }
            mergedChanges.add(new CacheChangeLine(normalized, lineDate, order++));
        }
        String changeLine = buildDailyChangeLine(dataDate, eventJson);
        if (StringUtils.hasText(changeLine)) {
            mergedChanges.add(new CacheChangeLine(changeLine, dataDate, order));
        }
        writeCache(trimmedReqno, cacheKey, mergedChanges);
    }

    @Override
    public void evictPatientSummaryContexts(String reqno) {
        if (!StringUtils.hasText(reqno)) {
            return;
        }
        Set<String> keys = stringRedisTemplate.keys(InfectionRedisKeys.patientSummaryContextPattern(reqno));
        if (keys == null || keys.isEmpty()) {
            return;
        }
        stringRedisTemplate.delete(keys);
    }

    private String buildSummary(String reqno, LocalDate anchorDate, String cached) {
        int windowDays = resolveWindowDays();
        LocalDate windowStart = anchorDate.minusDays(windowDays);
        LocalDate windowEnd = anchorDate.minusDays(1L);

        ObjectNode root = objectMapper.createObjectNode();
        root.put("reqno", reqno);
        ArrayNode changes = root.putArray("changes");
        Set<String> seen = new LinkedHashSet<>();
        appendCachedChanges(cached, windowStart, windowEnd, seen, changes);
        trimChanges(changes);
        return writeJsonQuietly(root);
    }

    private int resolveWindowDays() {
        return infectionMonitorProperties.getSummaryWindowDays() > 0
                ? infectionMonitorProperties.getSummaryWindowDays()
                : DEFAULT_WINDOW_DAYS;
    }

    private void writeCache(String reqno, String cacheKey, List<CacheChangeLine> changeLines) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("reqno", reqno);
        ArrayNode changesNode = root.putArray("changes");
        dedupeAndSort(changeLines).forEach(changesNode::add);
        String json = writeJsonQuietly(root);
        if (StringUtils.hasText(json)) {
            stringRedisTemplate.opsForValue().set(cacheKey, json);
        }
    }

    private void appendCachedChanges(String cached,
                                     LocalDate windowStart,
                                     LocalDate windowEnd,
                                     Set<String> seen,
                                     ArrayNode changes) {
        List<String> cachedChanges = readCachedChanges(cached);
        for (String line : cachedChanges) {
            LocalDate lineDate = extractLineDate(line);
            if (lineDate != null && (lineDate.isBefore(windowStart) || lineDate.isAfter(windowEnd))) {
                continue;
            }
            appendCachedLine(line, seen, changes);
        }
    }

    private List<String> readCachedChanges(String cached) {
        JsonNode cachedNode = parseJsonQuietly(cached);
        JsonNode cachedChanges = cachedNode == null ? null : cachedNode.path("changes");
        List<CacheChangeLine> changeLines = new ArrayList<>();
        if (cachedChanges == null || !cachedChanges.isArray()) {
            return List.of();
        }
        int order = 0;
        for (JsonNode item : cachedChanges) {
            if (item == null || !item.isTextual()) {
                continue;
            }
            String normalized = normalizeLine(item.asText(""));
            if (!StringUtils.hasText(normalized)) {
                continue;
            }
            changeLines.add(new CacheChangeLine(normalized, extractLineDate(normalized), order++));
        }
        return dedupeAndSort(changeLines);
    }

    private LocalDate extractLineDate(String line) {
        if (!StringUtils.hasText(line) || line.length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(line.substring(0, 10));
        } catch (Exception e) {
            return null;
        }
    }

    private void appendCachedLine(String line, Set<String> seen, ArrayNode changes) {
        if (!StringUtils.hasText(line)) {
            return;
        }
        String compact = normalizeLine(line);
        if (!StringUtils.hasText(compact)) {
            return;
        }
        if (!seen.add(compact)) {
            return;
        }
        changes.add(compact);
    }

    private String buildDailyChangeLine(LocalDate dataDate, String eventJson) {
        if (dataDate == null || !StringUtils.hasText(eventJson)) {
            return null;
        }
        JsonNode eventNode = parseJsonQuietly(eventJson);
        if (eventNode == null || !eventNode.isObject()) {
            return null;
        }
        String summary = firstNonBlank(
                eventNode.path("day_summary").asText(""),
                eventNode.path("summary").asText(""),
                eventNode.path("title").asText(""),
                eventNode.path("source_text").asText("")
        );
        if (!StringUtils.hasText(summary)) {
            return null;
        }
        String compactSummary = normalizeLine(summary);
        return StringUtils.hasText(compactSummary) ? dataDate + " " + compactSummary : null;
    }

    private void trimChanges(ArrayNode changes) {
        while (changes != null && changes.size() > MAX_CHANGE_ITEMS) {
            changes.remove(0);
        }
    }

    private List<String> dedupeAndSort(List<CacheChangeLine> changeLines) {
        List<CacheChangeLine> sorted = new ArrayList<>(changeLines);
        sorted.sort(Comparator
                .comparing(CacheChangeLine::date, Comparator.nullsLast(LocalDate::compareTo))
                .thenComparingInt(CacheChangeLine::order));
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<String> result = new ArrayList<>();
        for (CacheChangeLine line : sorted) {
            if (line == null || !StringUtils.hasText(line.text())) {
                continue;
            }
            if (seen.add(line.text())) {
                result.add(line.text());
            }
        }
        return result;
    }

    private String normalizeLine(String line) {
        if (!StringUtils.hasText(line)) {
            return null;
        }
        String compact = line.replace('\r', ' ').replace('\n', ' ').replaceAll("\\s+", " ").trim();
        if (!StringUtils.hasText(compact) || compact.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return null;
        }
        return compact;
    }

    private JsonNode parseJsonQuietly(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            log.debug("parse summary context cache failed", e);
            return null;
        }
    }

    private String writeJsonQuietly(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            log.warn("serialize summary context failed", e);
            return null;
        }
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private record CacheChangeLine(
            String text,
            LocalDate date,
            int order
    ) {
    }
}
