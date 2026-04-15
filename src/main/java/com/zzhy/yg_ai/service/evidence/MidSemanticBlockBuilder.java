package com.zzhy.yg_ai.service.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzhy.yg_ai.domain.entity.PatientRawDataEntity;
import com.zzhy.yg_ai.domain.enums.EvidenceBlockType;
import com.zzhy.yg_ai.domain.enums.InfectionSourceType;
import com.zzhy.yg_ai.domain.model.EvidenceBlock;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class MidSemanticBlockBuilder extends AbstractEvidenceBlockBuilder {

    private static final int NOTE_FINDINGS_LIMIT = 3;
    private static final int NOTE_ACTIONS_LIMIT = 2;
    private static final int NOTE_RISKS_LIMIT = 3;
    private static final int DIAGNOSIS_LIMIT = 4;
    private static final int VITAL_MOST_ABNORMAL_LIMIT = 4;
    private static final int VITAL_MIN_MAX_LIMIT = 4;
    private static final int LAB_PATHOGEN_LIMIT = 4;
    private static final int LAB_ALERT_LIMIT = 4;
    private static final int LAB_TREND_LIMIT = 2;
    private static final int LAB_ABNORMAL_LIMIT = 4;
    private static final int LAB_PANEL_LIMIT = 1;
    private static final int IMAGING_LIMIT = 2;
    private static final int ORDER_SG_LIMIT = 4;
    private static final int ORDER_LONG_TERM_LIMIT = 4;
    private static final int ORDER_TEMPORARY_LIMIT = 4;

    private static final List<String> DIAGNOSIS_FIELDS = List.of(
            "diagnosis", "diagnosis_name", "name", "status", "certainty", "type", "source", "time", "timestamp", "basis"
    );
    private static final List<String> IMAGING_FIELDS = List.of(
            "time", "timestamp", "exam_type", "exam_name", "name", "modality", "part", "key_findings",
            "conclusion", "impression", "impression_level", "abnormal", "infectious_hint"
    );
    private static final List<String> ORDER_KEYWORDS = List.of(
            "导尿", "引流", "置管", "插管", "气管", "抗菌", "抗生素", "培养", "药敏", "隔离",
            "手术", "穿刺", "换药", "CRP", "PCT", "血培养", "尿培养", "痰培养"
    );

    public MidSemanticBlockBuilder(ObjectMapper objectMapper) {
        super(objectMapper);
    }

    @Override
    public List<EvidenceBlock> build(PatientRawDataEntity rawData, String timelineWindowJson) {
        ObjectNode data = buildContextData(rawData, timelineWindowJson);
        if (!hasMeaningfulContent(data)) {
            return List.of();
        }

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("section", "day_context_semantic");
        payload.put("source", "struct_data_json.day_context");
        payload.set("data", data);
        return List.of(createBlock(rawData,
                EvidenceBlockType.MID_SEMANTIC,
                InfectionSourceType.MID,
                "struct_data_json.day_context",
                "day_context_semantic",
                payload,
                false));
    }

    public ObjectNode buildContextData(PatientRawDataEntity rawData, String timelineWindowJson) {
        ObjectNode root = parseObject(rawData == null ? null : rawData.getStructDataJson(), "struct_data_json",
                rawData == null ? null : rawData.getId());
        JsonNode dayContext = root.path("day_context");
        if (!dayContext.isObject()) {
            return objectMapper.createObjectNode();
        }

        ObjectNode data = objectMapper.createObjectNode();
        setIfMeaningful(data, "structured_context", buildStructuredContext(dayContext));
        setIfMeaningful(data, "clinical_context", buildClinicalContext(dayContext));
        return data;
    }

    private ObjectNode buildStructuredContext(JsonNode dayContext) {
        ObjectNode context = objectMapper.createObjectNode();
        setIfMeaningful(context, "diagnosis_facts",
                compactArray(dayContext.path("diagnosis_facts"), DIAGNOSIS_LIMIT, DIAGNOSIS_FIELDS));
        setIfMeaningful(context, "vitals", buildVitals(dayContext.path("vitals_summary")));
        setIfMeaningful(context, "labs", buildLabs(dayContext.path("lab_summary")));
        setIfMeaningful(context, "imaging",
                compactArray(dayContext.path("imaging_summary"), IMAGING_LIMIT, IMAGING_FIELDS));
        setIfMeaningful(context, "orders", buildOrders(dayContext.path("orders_summary")));
        return context;
    }

    private ObjectNode buildClinicalContext(JsonNode dayContext) {
        ObjectNode context = objectMapper.createObjectNode();
        setIfMeaningful(context, "note_semantics", buildNoteSemantics(dayContext.path("structured")));
        return context;
    }

    private ArrayNode buildNoteSemantics(JsonNode notes) {
        ArrayNode result = objectMapper.createArrayNode();
        if (!notes.isArray()) {
            return result;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode note : notes) {
            JsonNode structured = note.path("structured");
            ObjectNode item = objectMapper.createObjectNode();
            putText(item, "summary", structured.path("change_summary").asText(""));
            setIfMeaningful(item, "findings", buildFindings(structured));
            setIfMeaningful(item, "actions", textArray(structured.path("treatment_adjustments"), NOTE_ACTIONS_LIMIT));
            setIfMeaningful(item, "risks", buildRiskAlerts(structured.path("risk_alerts")));
            if (!hasMeaningfulContent(item)) {
                continue;
            }
            putText(item, "ref", buildNoteRef(note));
            appendUnique(result, item, seen);
        }
        return result;
    }

    private ArrayNode buildFindings(JsonNode structured) {
        ArrayNode result = objectMapper.createArrayNode();
        Set<String> seen = new LinkedHashSet<>();
        appendTextItems(result, seen, structured.path("new_findings"), NOTE_FINDINGS_LIMIT);
        appendTextItems(result, seen, structured.path("worsening_points"), NOTE_FINDINGS_LIMIT);
        appendTextItems(result, seen, structured.path("improving_points"), NOTE_FINDINGS_LIMIT);
        appendTextItems(result, seen, structured.path("key_exam_changes"), NOTE_FINDINGS_LIMIT);
        return result;
    }

    private ArrayNode buildRiskAlerts(JsonNode risks) {
        ArrayNode result = objectMapper.createArrayNode();
        if (!risks.isArray()) {
            return result;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode risk : risks) {
            ObjectNode item = objectMapper.createObjectNode();
            if (risk.isObject()) {
                putText(item, "risk", risk.path("risk").asText(""));
                putText(item, "certainty", risk.path("certainty").asText(""));
                putText(item, "basis", risk.path("basis").asText(""));
            } else if (risk.isTextual()) {
                putText(item, "risk", risk.asText(""));
            }
            appendUnique(result, item, seen);
            if (result.size() >= NOTE_RISKS_LIMIT) {
                break;
            }
        }
        return result;
    }

    private ObjectNode buildVitals(JsonNode summary) {
        ObjectNode result = objectMapper.createObjectNode();
        if (!summary.path("has_data").asBoolean(false)) {
            return result;
        }
        boolean persistentAbnormal = summary.path("persistent_abnormal").asBoolean(false);
        boolean needAlert = summary.path("need_alert").asBoolean(false);
        ArrayNode mostAbnormal = textArray(summary.path("most_abnormal"), VITAL_MOST_ABNORMAL_LIMIT);
        if (!persistentAbnormal && !needAlert && !hasMeaningfulContent(mostAbnormal)) {
            return result;
        }
        setIfMeaningful(result, "most_abnormal", mostAbnormal);
        if (persistentAbnormal) {
            result.put("persistent_abnormal", true);
        }
        if (needAlert) {
            result.put("need_alert", true);
        }
        setIfMeaningful(result, "min_max", textArray(summary.path("min_max"), VITAL_MIN_MAX_LIMIT));
        return result;
    }

    private ObjectNode buildLabs(JsonNode summary) {
        ObjectNode result = objectMapper.createObjectNode();
        if (!summary.path("has_data").asBoolean(false)) {
            return result;
        }
        setIfMeaningful(result, "pathogen_findings", textArray(summary.path("pathogen_findings"), LAB_PATHOGEN_LIMIT));
        setIfMeaningful(result, "alert_flags", textArray(summary.path("alert_flags"), LAB_ALERT_LIMIT));
        setIfMeaningful(result, "trend_findings", textArray(summary.path("trend_findings"), LAB_TREND_LIMIT));
        setIfMeaningful(result, "abnormal_items", textArray(summary.path("abnormal_items"), LAB_ABNORMAL_LIMIT));
        setIfMeaningful(result, "panel_summaries", textArray(summary.path("panel_summaries"), LAB_PANEL_LIMIT));
        return result;
    }

    private ObjectNode buildOrders(JsonNode summary) {
        ObjectNode result = objectMapper.createObjectNode();
        if (!summary.path("has_data").asBoolean(false)) {
            return result;
        }
        ArrayNode sg = textArray(summary.path("sg"), ORDER_SG_LIMIT);
        setIfMeaningful(result, "sg", sg);
        if (!hasAnyOrderKeyword(summary)) {
            return result;
        }
        setIfMeaningful(result, "long_term", keywordFirstArray(summary.path("long_term"), ORDER_LONG_TERM_LIMIT));
        setIfMeaningful(result, "temporary", keywordFirstArray(summary.path("temporary"), ORDER_TEMPORARY_LIMIT));
        return result;
    }

    private boolean hasAnyOrderKeyword(JsonNode summary) {
        return hasOrderKeyword(summary.path("sg"))
                || hasOrderKeyword(summary.path("long_term"))
                || hasOrderKeyword(summary.path("temporary"));
    }

    private boolean hasOrderKeyword(JsonNode node) {
        if (!node.isArray()) {
            return false;
        }
        for (JsonNode item : node) {
            if (containsOrderKeyword(item.asText(""))) {
                return true;
            }
        }
        return false;
    }

    private ArrayNode keywordFirstArray(JsonNode node, int limit) {
        ArrayNode result = objectMapper.createArrayNode();
        if (!node.isArray()) {
            return result;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode item : node) {
            String text = item.asText("");
            if (containsOrderKeyword(text)) {
                addText(result, seen, text, limit);
            }
        }
        for (JsonNode item : node) {
            addText(result, seen, item.asText(""), limit);
        }
        return result;
    }

    private boolean containsOrderKeyword(String text) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        for (String keyword : ORDER_KEYWORDS) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private ArrayNode compactArray(JsonNode node, int limit, List<String> fields) {
        ArrayNode result = objectMapper.createArrayNode();
        if (!node.isArray()) {
            return result;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode item : node) {
            JsonNode compacted = compactItem(item, fields);
            appendUnique(result, compacted, seen);
            if (result.size() >= limit) {
                break;
            }
        }
        return result;
    }

    private JsonNode compactItem(JsonNode item, List<String> fields) {
        if (item == null || item.isMissingNode() || item.isNull()) {
            return objectMapper.createObjectNode();
        }
        if (item.isTextual()) {
            return item.deepCopy();
        }
        if (!item.isObject()) {
            return objectMapper.getNodeFactory().textNode(item.asText(""));
        }
        ObjectNode result = objectMapper.createObjectNode();
        for (String field : fields) {
            putCompactField(result, field, item.path(field));
        }
        return result;
    }

    private void putCompactField(ObjectNode target, String fieldName, JsonNode value) {
        if (!hasMeaningfulContent(value)) {
            return;
        }
        if (value.isTextual()) {
            putText(target, fieldName, value.asText(""));
            return;
        }
        if (value.isArray()) {
            setIfMeaningful(target, fieldName, textArray(value, 3));
            return;
        }
        if (value.isNumber() || value.isBoolean()) {
            target.set(fieldName, value.deepCopy());
            return;
        }
        if (value.isObject()) {
            putText(target, fieldName, firstNonBlank(
                    value.path("text").asText(""),
                    value.path("summary").asText(""),
                    value.path("name").asText(""),
                    value.path("value").asText("")
            ));
        }
    }

    private ArrayNode textArray(JsonNode node, int limit) {
        ArrayNode result = objectMapper.createArrayNode();
        Set<String> seen = new LinkedHashSet<>();
        appendTextItems(result, seen, node, limit);
        return result;
    }

    private void appendTextItems(ArrayNode target, Set<String> seen, JsonNode node, int limit) {
        if (target.size() >= limit || node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                addText(target, seen, item.asText(""), limit);
                if (target.size() >= limit) {
                    break;
                }
            }
            return;
        }
        addText(target, seen, node.asText(""), limit);
    }

    private void addText(ArrayNode target, Set<String> seen, String text, int limit) {
        if (target.size() >= limit || !StringUtils.hasText(text)) {
            return;
        }
        String normalized = text.trim();
        if (seen.add(normalized)) {
            target.add(normalized);
        }
    }

    private void appendUnique(ArrayNode target, JsonNode node, Set<String> seen) {
        if (!hasMeaningfulContent(node)) {
            return;
        }
        String signature = node.toString().replaceAll("\\s+", "");
        if (StringUtils.hasText(signature) && seen.add(signature)) {
            target.add(node.deepCopy());
        }
    }

    private void setIfMeaningful(ObjectNode target, String fieldName, JsonNode value) {
        if (hasMeaningfulContent(value)) {
            target.set(fieldName, value);
        }
    }

    private void putText(ObjectNode target, String fieldName, String value) {
        if (StringUtils.hasText(value)) {
            target.put(fieldName, value.trim());
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

    private String buildNoteRef(JsonNode note) {
        String noteType = note.path("note_type").asText("");
        String timestamp = note.path("timestamp").asText("");
        if (StringUtils.hasText(noteType) && StringUtils.hasText(timestamp)) {
            return noteType.trim() + "@" + timestamp.trim();
        }
        return StringUtils.hasText(noteType) ? noteType.trim() : timestamp.trim();
    }
}
