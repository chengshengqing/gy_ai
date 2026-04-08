package com.zzhy.yg_ai.service.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzhy.yg_ai.domain.entity.PatientRawDataEntity;
import com.zzhy.yg_ai.domain.enums.EvidenceBlockType;
import com.zzhy.yg_ai.domain.enums.InfectionSourceType;
import com.zzhy.yg_ai.domain.model.EvidenceBlock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class MidSemanticBlockBuilder extends AbstractEvidenceBlockBuilder {

    public MidSemanticBlockBuilder(ObjectMapper objectMapper) {
        super(objectMapper);
    }

    @Override
    public List<EvidenceBlock> build(PatientRawDataEntity rawData, String timelineWindowJson) {
        ObjectNode root = parseObject(rawData == null ? null : rawData.getStructDataJson(), "struct_data_json",
                rawData == null ? null : rawData.getId());
        List<EvidenceBlock> result = new ArrayList<>();
        addSectionBlock(result, rawData, root, "core_problems", "problem");
        addSectionBlock(result, rawData, root, "differential_diagnosis", "differential");
        addRiskBlock(result, rawData, root);
        return List.copyOf(result);
    }

    private void addSectionBlock(List<EvidenceBlock> result,
                                 PatientRawDataEntity rawData,
                                 ObjectNode root,
                                 String fieldName,
                                 String title) {
        JsonNode section = root.path(fieldName);
        if (!hasMeaningfulContent(section)) {
            return;
        }
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("section", fieldName);
        payload.put("source", "struct_data_json");
        payload.set("data", dedupeNode(section));
        result.add(createBlock(rawData,
                EvidenceBlockType.MID_SEMANTIC,
                InfectionSourceType.MID,
                "struct_data_json." + fieldName,
                title,
                payload,
                false));
    }

    private void addRiskBlock(List<EvidenceBlock> result, PatientRawDataEntity rawData, ObjectNode root) {
        ObjectNode riskPayload = objectMapper.createObjectNode();
        riskPayload.put("section", "risk");
        riskPayload.put("source", "struct_data_json");
        ArrayNode collected = objectMapper.createArrayNode();
        collectRiskField(root, collected, "risk");
        collectRiskField(root, collected, "risk_alerts");
        collectRiskField(root, collected, "risk_candidates");
        collectRiskField(root, collected, "risk_flags");
        collectRiskField(root, collected, "postop_risk_alerts");
        collectRiskField(root, collected, "risks");
        if (!hasMeaningfulContent(collected)) {
            return;
        }
        riskPayload.set("data", collected);
        result.add(createBlock(rawData,
                EvidenceBlockType.MID_SEMANTIC,
                InfectionSourceType.MID,
                "struct_data_json.risk",
                "risk",
                riskPayload,
                false));
    }

    private void collectRiskField(ObjectNode root, ArrayNode collected, String fieldName) {
        JsonNode node = root.path(fieldName);
        if (!hasMeaningfulContent(node)) {
            return;
        }
        if (node.isArray()) {
            appendUnique(collected, node);
            return;
        }
        if (node.isObject()) {
            appendUnique(collected, node);
            return;
        }
        ObjectNode item = objectMapper.createObjectNode();
        item.put("field", fieldName);
        item.put("value", node.asText(""));
        appendUnique(collected, item);
    }

    private JsonNode dedupeNode(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return objectMapper.createArrayNode();
        }
        if (!node.isArray()) {
            return node.deepCopy();
        }
        ArrayNode result = objectMapper.createArrayNode();
        appendUnique(result, node);
        return result;
    }

    private void appendUnique(ArrayNode target, JsonNode source) {
        if (source == null || source.isMissingNode() || source.isNull()) {
            return;
        }
        Set<String> seen = new LinkedHashSet<>();
        target.forEach(item -> seen.add(signature(item)));
        if (source.isArray()) {
            source.forEach(item -> appendOne(target, item, seen));
            return;
        }
        appendOne(target, source, seen);
    }

    private void appendOne(ArrayNode target, JsonNode node, Set<String> seen) {
        if (!hasMeaningfulContent(node)) {
            return;
        }
        String signature = signature(node);
        if (StringUtils.hasText(signature) && seen.add(signature)) {
            target.add(node.deepCopy());
        }
    }

    private String signature(JsonNode node) {
        return node == null ? "" : node.toString().replaceAll("\\s+", "");
    }
}
