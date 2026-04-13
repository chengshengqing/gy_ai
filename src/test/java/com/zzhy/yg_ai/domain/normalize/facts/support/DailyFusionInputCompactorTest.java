package com.zzhy.yg_ai.domain.normalize.facts.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DailyFusionInputCompactorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void compactIfNeededKeepsShortInputUnchanged() throws Exception {
        Map<String, Object> fusionFacts = buildFusionFacts(1);
        DailyFusionInputCompactor compactor = new DailyFusionInputCompactor(objectMapper, 10000);
        String expectedJson = objectMapper.writeValueAsString(Map.of("fusion_ready_facts", fusionFacts));

        DailyFusionInputCompactor.CompactionResult result = compactor.compactIfNeeded(
                "REQ-1",
                LocalDate.of(2026, 1, 27),
                fusionFacts
        );

        assertEquals(0, result.compactionLevel());
        assertEquals(expectedJson, result.inputJson());
        assertEquals(result.originalChars(), result.compactedChars());
    }

    @Test
    void compactIfNeededCompactsOnlyAfterInputExceedsBudget() throws Exception {
        Map<String, Object> fusionFacts = buildFusionFacts(14);
        DailyFusionInputCompactor compactor = new DailyFusionInputCompactor(objectMapper, 1200);

        DailyFusionInputCompactor.CompactionResult result = compactor.compactIfNeeded(
                "REQ-1",
                LocalDate.of(2026, 1, 27),
                fusionFacts
        );

        JsonNode root = objectMapper.readTree(result.inputJson()).path("fusion_ready_facts");
        assertTrue(result.compactionLevel() > 0);
        assertTrue(result.compactedChars() < result.originalChars());
        assertTrue(root.path("meta").path("source_note_refs").isArray());
        assertFalse(root.path("objective_fact_layer").path("orders_summary").has("all_orders"));
        assertFalse(root.path("clinical_reasoning_layer").path("problem_candidates").get(0).has("source_note_refs"));
        assertFalse(root.path("clinical_reasoning_layer").path("problem_candidates").get(0).has("source_types"));
    }

    private Map<String, Object> buildFusionFacts(int itemCount) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("reqno", "REQ-1");
        facts.put("date", "2026-01-27");
        facts.put("meta", Map.of(
                "source_note_refs",
                List.of("入院记录@2026-01-27 08:00:00.000", "日常病程记录@2026-01-27 10:00:00.000")
        ));

        Map<String, Object> clinicalLayer = new LinkedHashMap<>();
        clinicalLayer.put("problem_candidates", candidateList("problem", "感染发热", itemCount));
        clinicalLayer.put("risk_candidates", candidateList("risk", "脓毒风险", itemCount));
        clinicalLayer.put("pending_facts", candidateList("item", "复查血常规", itemCount));
        facts.put("clinical_reasoning_layer", clinicalLayer);

        Map<String, Object> ordersSummary = new LinkedHashMap<>();
        ordersSummary.put("long_term", List.of("注射用头孢噻肟钠", "一级护理", "低盐低脂饮食"));
        ordersSummary.put("temporary", List.of("血培养及鉴定", "一般物理降温", "住院主治医师诊查"));
        ordersSummary.put("sg", List.of());
        ordersSummary.put("all_orders", List.of("注射用头孢噻肟钠", "一级护理", "低盐低脂饮食", "血培养及鉴定", "一般物理降温", "住院主治医师诊查"));

        Map<String, Object> objectiveLayer = new LinkedHashMap<>();
        objectiveLayer.put("orders_summary", ordersSummary);
        objectiveLayer.put("objective_evidence", candidateList("fact", "体温最高39℃", itemCount));
        objectiveLayer.put("action_facts", candidateList("action", "注射用头孢噻肟钠抗感染", itemCount));
        facts.put("objective_fact_layer", objectiveLayer);
        facts.put("fusion_control_layer", Map.of());
        return facts;
    }

    private List<Map<String, Object>> candidateList(String textKey, String textPrefix, int itemCount) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < itemCount; i++) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put(textKey, textPrefix + i);
            item.put("source_types", List.of("日常病程记录"));
            item.put("source_note_refs", List.of("日常病程记录@2026-01-27 10:00:00.000"));
            result.add(item);
        }
        return result;
    }
}
