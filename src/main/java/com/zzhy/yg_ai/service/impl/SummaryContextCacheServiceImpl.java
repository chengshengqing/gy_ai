package com.zzhy.yg_ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzhy.yg_ai.common.InfectionRedisKeys;
import com.zzhy.yg_ai.domain.entity.PatientRawDataEntity;
import com.zzhy.yg_ai.domain.enums.SummaryContextType;
import com.zzhy.yg_ai.mapper.PatientRawDataMapper;
import com.zzhy.yg_ai.service.SummaryContextCacheService;
import java.time.LocalDate;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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

    private static final int MAX_CHANGE_ITEMS = 8;
    private static final int MAX_FUSION_SENTENCES = 4;
    private static final int MAX_LINE_LENGTH = 48;
    private static final int MAX_FUSION_LIST_ITEMS = 2;

    private final StringRedisTemplate stringRedisTemplate;
    private final PatientRawDataMapper patientRawDataMapper;
    private final ObjectMapper objectMapper;

    @Override
    public String getOrBuildEventExtractorContext(String reqno, LocalDate anchorDate, int windowDays) {
        if (!StringUtils.hasText(reqno) || anchorDate == null) {
            return null;
        }
        int effectiveWindowDays = windowDays <= 0 ? 7 : windowDays;
        String cacheKey = InfectionRedisKeys.summaryContext(
                SummaryContextType.EVENT_EXTRACTOR_CONTEXT,
                reqno,
                anchorDate,
                effectiveWindowDays);
        String cached = stringRedisTemplate.opsForValue().get(cacheKey);
        if (StringUtils.hasText(cached)) {
            return cached;
        }
        String summary = buildEventExtractorContext(reqno, anchorDate, effectiveWindowDays);
        if (StringUtils.hasText(summary)) {
            stringRedisTemplate.opsForValue().set(cacheKey, summary);
        }
        return summary;
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

    private String buildEventExtractorContext(String reqno, LocalDate anchorDate, int windowDays) {
        LocalDate windowStart = anchorDate.minusDays(windowDays - 1L);
        List<PatientRawDataEntity> rows = patientRawDataMapper.selectList(new QueryWrapper<PatientRawDataEntity>()
                .eq("reqno", reqno.trim())
                .ge("data_date", windowStart)
                .le("data_date", anchorDate)
                .isNotNull("event_json")
                .orderByAsc("data_date", "id"));

        ObjectNode root = objectMapper.createObjectNode();
        root.put("reqno", reqno.trim());
        root.put("anchorDate", anchorDate.toString());
        root.put("windowDays", windowDays);
        root.put("summaryType", SummaryContextType.EVENT_EXTRACTOR_CONTEXT.name());
        ArrayNode changes = root.putArray("changes");
        Set<String> seen = new LinkedHashSet<>();

        for (PatientRawDataEntity row : rows) {
            if (row == null || !StringUtils.hasText(row.getEventJson())) {
                continue;
            }
            JsonNode eventNode = parseJsonQuietly(row.getEventJson());
            if (eventNode == null || !eventNode.isObject()) {
                continue;
            }
            collectEventSummaryLines(row.getDataDate(), eventNode, seen, changes);
            if (changes.size() >= MAX_CHANGE_ITEMS) {
                break;
            }
        }
        return writeJsonQuietly(root);
    }

    private void collectEventSummaryLines(LocalDate dataDate,
                                          JsonNode eventNode,
                                          Set<String> seen,
                                          ArrayNode changes) {
        if (changes.size() >= MAX_CHANGE_ITEMS) {
            return;
        }
        JsonNode eventsNode = eventNode.path("events");
        if (eventsNode.isArray()) {
            for (JsonNode item : eventsNode) {
                appendIfUseful(dataDate, buildLineFromEvent(item), seen, changes);
                if (changes.size() >= MAX_CHANGE_ITEMS) {
                    return;
                }
            }
            if (changes.size() > 0) {
                return;
            }
        }
        appendFusionSummary(dataDate, eventNode, seen, changes);
        if (changes.size() > 0) {
            return;
        }
        appendIfUseful(dataDate, firstNonBlank(
                eventNode.path("summary").asText(""),
                eventNode.path("title").asText(""),
                eventNode.path("source_text").asText("")
        ), seen, changes);
    }

    private String buildLineFromEvent(JsonNode eventNode) {
        if (eventNode == null || !eventNode.isObject()) {
            return null;
        }
        String eventName = firstNonBlank(
                eventNode.path("event_name").asText(""),
                eventNode.path("name").asText(""),
                eventNode.path("title").asText(""),
                eventNode.path("summary").asText(""),
                eventNode.path("source_text").asText("")
        );
        String eventSubtype = eventNode.path("event_subtype").asText("");
        String clinicalMeaning = eventNode.path("clinical_meaning").asText("");
        String bodySite = eventNode.path("body_site").asText("");
        String joined = joinNonBlank(eventName, eventSubtype, clinicalMeaning, bodySite);
        return StringUtils.hasText(joined) ? joined : null;
    }

    private void appendIfUseful(LocalDate dataDate,
                                String line,
                                Set<String> seen,
                                ArrayNode changes) {
        if (!StringUtils.hasText(line) || changes.size() >= MAX_CHANGE_ITEMS) {
            return;
        }
        String compact = normalizeLine(line);
        if (!StringUtils.hasText(compact)) {
            return;
        }
        String lowered = compact.toLowerCase(Locale.ROOT);
        if (lowered.contains("background")
                || lowered.contains("baseline_problem")
                || lowered.contains("screening")
                || lowered.contains("daily_fusion")) {
            return;
        }
        String normalizedLine = (dataDate == null ? "" : dataDate + " ") + compact;
        String dedupeKey = normalizedLine.toLowerCase(Locale.ROOT);
        if (!seen.add(dedupeKey)) {
            return;
        }
        changes.add(normalizedLine);
    }

    private void appendFusionSummary(LocalDate dataDate,
                                     JsonNode eventNode,
                                     Set<String> seen,
                                     ArrayNode changes) {
        if (eventNode == null || !eventNode.isObject() || changes.size() >= MAX_CHANGE_ITEMS) {
            return;
        }
        JsonNode fusionNode = pickFlatDailyFusion(eventNode);
        if (fusionNode == null || !fusionNode.isObject()) {
            return;
        }
        appendIfUseful(dataDate, fusionNode.path("day_summary").asText(""), seen, changes);
        appendFusionArray(dataDate, fusionNode.path("key_evidence"), seen, changes);
        appendFusionArray(dataDate, fusionNode.path("major_actions"), seen, changes);
        appendFusionArray(dataDate, fusionNode.path("risk_flags"), seen, changes);
        appendFusionArray(dataDate, fusionNode.path("next_focus_24h"), seen, changes);
        if (changes.size() >= MAX_CHANGE_ITEMS) {
            return;
        }
        String fusionText = summarizeFusionProblems(fusionNode.path("problem_list"));
        if (!StringUtils.hasText(fusionText)) {
            return;
        }
        String[] sentences = fusionText.split("[。；;！!？?\\n]+");
        int added = 0;
        for (String sentence : sentences) {
            String normalized = normalizeLine(sentence);
            if (!StringUtils.hasText(normalized)) {
                continue;
            }
            appendIfUseful(dataDate, normalized, seen, changes);
            if (changes.size() >= MAX_CHANGE_ITEMS) {
                return;
            }
            added++;
            if (added >= MAX_FUSION_SENTENCES) {
                return;
            }
        }
    }

    private void appendFusionArray(LocalDate dataDate,
                                   JsonNode node,
                                   Set<String> seen,
                                   ArrayNode changes) {
        if (node == null || !node.isArray() || changes.size() >= MAX_CHANGE_ITEMS) {
            return;
        }
        int count = 0;
        for (JsonNode item : node) {
            if (item != null && item.isTextual()) {
                appendIfUseful(dataDate, item.asText(""), seen, changes);
                count++;
            }
            if (changes.size() >= MAX_CHANGE_ITEMS || count >= MAX_FUSION_LIST_ITEMS) {
                return;
            }
        }
    }

    private String summarizeFusionProblems(JsonNode problemListNode) {
        if (problemListNode == null || !problemListNode.isArray()) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        int count = 0;
        for (JsonNode item : problemListNode) {
            if (item == null || !item.isObject()) {
                continue;
            }
            String name = firstNonBlank(
                    item.path("problem_name").asText(""),
                    item.path("problem_key").asText("")
            );
            String certainty = item.path("certainty").asText("");
            String status = item.path("status").asText("");
            String line = joinNonBlank(name, certainty, status);
            if (!StringUtils.hasText(line)) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append('；');
            }
            builder.append(line);
            count++;
            if (count >= MAX_FUSION_LIST_ITEMS) {
                break;
            }
        }
        return builder.toString();
    }

    private JsonNode pickFlatDailyFusion(JsonNode timelineNode) {
        JsonNode current = timelineNode;
        for (int i = 0; i < 4; i++) {
            if (current == null || !current.isObject()) {
                break;
            }
            if (current.has("day_summary")
                    || current.has("problem_list")
                    || current.has("key_evidence")
                    || current.has("major_actions")
                    || current.has("risk_flags")
                    || current.has("next_focus_24h")) {
                return current;
            }
            JsonNode next = current.path("daily_fusion");
            if (next.isMissingNode() || next.isNull() || !next.isObject()) {
                break;
            }
            current = next;
        }
        return current != null && current.isObject() ? current : null;
    }

    private String normalizeLine(String line) {
        if (!StringUtils.hasText(line)) {
            return null;
        }
        String compact = line
                .replace('\r', ' ')
                .replace('\n', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        if (!StringUtils.hasText(compact)) {
            return null;
        }
        if (compact.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return null;
        }
        if (compact.length() > MAX_LINE_LENGTH) {
            return compact.substring(0, MAX_LINE_LENGTH);
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
            log.debug("parse event_json failed", e);
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

    private String joinNonBlank(String... values) {
        StringBuilder builder = new StringBuilder();
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (!StringUtils.hasText(value)) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(value.trim());
        }
        return builder.toString();
    }
}
