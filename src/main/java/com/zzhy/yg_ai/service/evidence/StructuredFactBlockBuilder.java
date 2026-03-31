package com.zzhy.yg_ai.service.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzhy.yg_ai.domain.entity.PatientRawDataEntity;
import com.zzhy.yg_ai.domain.entity.PatientSummaryEntity;
import com.zzhy.yg_ai.domain.enums.EvidenceBlockType;
import com.zzhy.yg_ai.domain.enums.InfectionSourceType;
import com.zzhy.yg_ai.domain.model.EvidenceBlock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class StructuredFactBlockBuilder extends AbstractEvidenceBlockBuilder {

    private static final Map<String, List<String>> SECTION_ALIASES = createSectionAliases();

    public StructuredFactBlockBuilder(ObjectMapper objectMapper) {
        super(objectMapper);
    }

    @Override
    public List<EvidenceBlock> build(PatientRawDataEntity rawData, PatientSummaryEntity latestSummary) {
        ObjectNode root = parseObject(rawData == null ? null : rawData.getFilterDataJson(), "filter_data_json",
                rawData == null ? null : rawData.getId());
        List<EvidenceBlock> result = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : SECTION_ALIASES.entrySet()) {
            JsonNode section = firstNonEmpty(root, entry.getValue());
            if (!hasMeaningfulContent(section)) {
                continue;
            }
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("section", entry.getKey());
            payload.put("source", "filter_data_json");
            payload.set("data", section.deepCopy());
            result.add(createBlock(rawData,
                    EvidenceBlockType.STRUCTURED_FACT,
                    InfectionSourceType.RAW,
                    "filter_data_json." + entry.getKey(),
                    entry.getKey(),
                    payload,
                    false));
        }
        return List.copyOf(result);
    }

    private JsonNode firstNonEmpty(ObjectNode root, List<String> fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode node = root.path(fieldName);
            if (hasMeaningfulContent(node)) {
                return node;
            }
        }
        return null;
    }

    private static Map<String, List<String>> createSectionAliases() {
        Map<String, List<String>> aliases = new LinkedHashMap<>();
        aliases.put("diagnosis", List.of("diagnosis"));
        aliases.put("vital_signs", List.of("vital_signs"));
        aliases.put("lab_results", List.of("lab_results"));
        aliases.put("imaging", List.of("imaging"));
        aliases.put("doctor_orders", List.of("doctor_orders"));
        aliases.put("transfer", List.of("transfer", "transfers", "pat_transfer"));
        aliases.put("use_medicine", List.of("use_medicine", "medications", "pat_useMedicine"));
        aliases.put("operation", List.of("operation", "operations", "pat_opsCutInfor"));
        aliases.put("microbiology", List.of("microbiology", "microbe", "pat_test"));
        return aliases;
    }
}
