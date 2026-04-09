package com.zzhy.yg_ai.domain.normalize.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class NotePreparationSupport {

    private final ObjectMapper objectMapper;

    public NotePreparationSupport(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> readStructuredNotes(Map<String, Object> standardizedDayFacts) {
        List<Map<String, Object>> notes = new ArrayList<>();
        Object structured = standardizedDayFacts.get("structured");
        if (structured instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    notes.add((Map<String, Object>) map);
                }
            }
        }
        return notes;
    }

    public JsonNode toStructuredNode(Object structuredNode) {
        if (structuredNode instanceof JsonNode node) {
            return node;
        }
        return objectMapper.valueToTree(structuredNode);
    }

    public String buildNoteRef(String noteType, String timestamp) {
        String type = defaultIfBlank(noteType, "");
        String time = defaultIfBlank(timestamp, "");
        return StringUtils.hasText(time) ? type + "@" + time : type;
    }

    public List<Map<String, Object>> deduplicateMapList(List<Map<String, Object>> values, String keyField, int maxSize) {
        LinkedHashMap<String, Map<String, Object>> deduplicated = new LinkedHashMap<>();
        for (Map<String, Object> value : values) {
            String key = valueAsString(value.get(keyField)).trim();
            if (!StringUtils.hasText(key) || deduplicated.containsKey(key)) {
                continue;
            }
            deduplicated.put(key, value);
            if (deduplicated.size() >= maxSize) {
                break;
            }
        }
        return new ArrayList<>(deduplicated.values());
    }

    public void addAllStrings(Collection<String> collector, Object source) {
        if (source instanceof Collection<?> values) {
            for (Object value : values) {
                if (value == null) {
                    continue;
                }
                String text = String.valueOf(value).trim();
                if (StringUtils.hasText(text)) {
                    collector.add(text);
                }
            }
        }
    }

    public void collectFieldText(JsonNode node, Set<String> collector, String... fieldNames) {
        for (String fieldName : fieldNames) {
            flattenText(node.path(fieldName), collector);
        }
    }

    public void flattenText(JsonNode node, Set<String> collector) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if (node.isTextual()) {
            String text = node.asText().trim();
            if (StringUtils.hasText(text)) {
                collector.add(text);
            }
            return;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                flattenText(item, collector);
            }
            return;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> flattenText(entry.getValue(), collector));
        }
    }

    public String valueAsString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    public String defaultIfBlank(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value : defaultValue;
    }
}
