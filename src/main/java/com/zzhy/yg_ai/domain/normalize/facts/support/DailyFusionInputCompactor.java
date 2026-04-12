package com.zzhy.yg_ai.domain.normalize.facts.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Slf4j
@Component
public class DailyFusionInputCompactor {

    private static final String EMPTY_JSON = "{}";
    private static final int DEFAULT_MAX_INPUT_CHARS = 7000;

    private static final List<String> HIGH_VALUE_KEYWORDS = List.of(
            "危急", "阳性", "检出", "耐药", "感染", "发热", "高热", "血培养", "培养", "脓毒",
            "抗菌", "抗病毒", "头孢", "青霉", "美罗", "万古", "利奈", "阿奇", "左氧", "莫西",
            "奥司他韦", "阿昔洛韦", "更昔洛韦", "甲泼尼龙", "激素", "利尿", "呋塞米",
            "抗凝", "利伐沙班", "肝素", "吸氧", "雾化", "布地奈德", "氨溴索", "茶碱",
            "手术", "置管", "引流", "输血", "胰岛素", "抢救", "会诊", "心衰", "呼吸"
    );

    private static final List<String> LOW_VALUE_ORDER_KEYWORDS = List.of(
            "护理", "陪护", "饮食", "诊查", "路径", "留置针护理", "卧床休息", "床位"
    );

    private final ObjectMapper objectMapper;
    private final int maxInputChars;

    public DailyFusionInputCompactor(ObjectMapper objectMapper,
                                     @Value("${infection.normalize.daily-fusion.max-input-chars:7000}") int maxInputChars) {
        this.objectMapper = objectMapper;
        this.maxInputChars = maxInputChars > 0 ? maxInputChars : DEFAULT_MAX_INPUT_CHARS;
    }

    public CompactionResult compactIfNeeded(String reqno, LocalDate dataDate, Map<String, Object> fusionReadyFacts) {
        String originalInputJson = toInputJson(fusionReadyFacts);
        int originalChars = originalInputJson.length();
        if (originalChars <= maxInputChars) {
            return new CompactionResult(originalInputJson, 0, originalChars, originalChars);
        }

        Map<String, Object> compactedFacts = deepCopy(fusionReadyFacts);
        applyLevelOneCompaction(compactedFacts);
        String compactedInputJson = toInputJson(compactedFacts);
        if (compactedInputJson.length() <= maxInputChars) {
            return logAndReturn(reqno, dataDate, originalChars, compactedInputJson, 1);
        }

        applyLevelTwoCompaction(compactedFacts);
        compactedInputJson = toInputJson(compactedFacts);
        if (compactedInputJson.length() <= maxInputChars) {
            return logAndReturn(reqno, dataDate, originalChars, compactedInputJson, 2);
        }

        applyLevelThreeCompaction(compactedFacts);
        compactedInputJson = toInputJson(compactedFacts);
        CompactionResult result = logAndReturn(reqno, dataDate, originalChars, compactedInputJson, 3);
        if (result.compactedChars() > maxInputChars) {
            log.warn("daily_fusion input still exceeds budget after compaction, reqno={}, date={}, maxInputChars={}, originalChars={}, compactedChars={}",
                    reqno, dataDate, maxInputChars, originalChars, result.compactedChars());
        }
        return result;
    }

    private void applyLevelOneCompaction(Map<String, Object> fusionFacts) {
        Map<String, Object> objectiveLayer = childMap(fusionFacts, "objective_fact_layer");
        Map<String, Object> ordersSummary = childMap(objectiveLayer, "orders_summary");
        ordersSummary.remove("all_orders");
        removeNestedSourceFields(fusionFacts, false);
        removeEmptyValues(fusionFacts);
    }

    private void applyLevelTwoCompaction(Map<String, Object> fusionFacts) {
        Map<String, Object> objectiveLayer = childMap(fusionFacts, "objective_fact_layer");
        Map<String, Object> labSummary = childMap(objectiveLayer, "lab_summary");
        limitStringList(labSummary, "abnormal_items", 8);
        limitStringList(labSummary, "panel_summaries", 4);

        Map<String, Object> ordersSummary = childMap(objectiveLayer, "orders_summary");
        prioritizeOrderList(ordersSummary, "long_term", 12);
        prioritizeOrderList(ordersSummary, "temporary", 12);
        prioritizeOrderList(ordersSummary, "sg", 12);
        removeEmptyValues(fusionFacts);
    }

    private void applyLevelThreeCompaction(Map<String, Object> fusionFacts) {
        Map<String, Object> clinicalLayer = childMap(fusionFacts, "clinical_reasoning_layer");
        limitMapList(clinicalLayer, "problem_candidates", 8);
        limitMapList(clinicalLayer, "differential_candidates", 4);
        limitMapList(clinicalLayer, "risk_candidates", 8);
        limitMapList(clinicalLayer, "pending_facts", 8);

        Map<String, Object> objectiveLayer = childMap(fusionFacts, "objective_fact_layer");
        limitMapList(objectiveLayer, "objective_evidence", 10);
        limitMapList(objectiveLayer, "action_facts", 10);
        removeEmptyValues(fusionFacts);
    }

    private void removeNestedSourceFields(Object value, boolean inMeta) {
        if (value instanceof Map<?, ?> source) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) source;
            if (!inMeta) {
                map.remove("source_note_refs");
                map.remove("source_types");
            }
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                removeNestedSourceFields(entry.getValue(), inMeta || "meta".equals(entry.getKey()));
            }
            return;
        }
        if (value instanceof List<?> list) {
            for (Object item : list) {
                removeNestedSourceFields(item, inMeta);
            }
        }
    }

    private boolean removeEmptyValues(Object value) {
        if (value instanceof Map<?, ?> source) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) source;
            map.entrySet().removeIf(entry -> removeEmptyValues(entry.getValue()));
            return map.isEmpty();
        }
        if (value instanceof List<?> list) {
            return list.isEmpty();
        }
        return false;
    }

    private void prioritizeOrderList(Map<String, Object> source, String key, int maxSize) {
        Object value = source.get(key);
        if (!(value instanceof List<?> list) || list.size() <= maxSize) {
            return;
        }
        List<String> highValue = new ArrayList<>();
        List<String> fallback = new ArrayList<>();
        for (Object item : list) {
            String text = item == null ? "" : String.valueOf(item).trim();
            if (!StringUtils.hasText(text)) {
                continue;
            }
            if (containsAny(text, HIGH_VALUE_KEYWORDS) && !containsAny(text, LOW_VALUE_ORDER_KEYWORDS)) {
                highValue.add(text);
            } else {
                fallback.add(text);
            }
        }
        List<String> result = new ArrayList<>();
        appendUntilLimit(result, highValue, maxSize);
        appendUntilLimit(result, fallback, maxSize);
        source.put(key, result);
    }

    private void limitStringList(Map<String, Object> source, String key, int maxSize) {
        Object value = source.get(key);
        if (!(value instanceof List<?> list) || list.size() <= maxSize) {
            return;
        }
        source.put(key, list.subList(0, maxSize));
    }

    private void limitMapList(Map<String, Object> source, String key, int maxSize) {
        Object value = source.get(key);
        if (!(value instanceof List<?> list) || list.size() <= maxSize) {
            return;
        }
        List<ScoredItem> scoredItems = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            scoredItems.add(new ScoredItem(item, i, priorityScore(item)));
        }
        scoredItems.sort(Comparator
                .comparingInt(ScoredItem::score).reversed()
                .thenComparingInt(ScoredItem::index));
        List<Object> limited = scoredItems.stream()
                .limit(maxSize)
                .sorted(Comparator.comparingInt(ScoredItem::index))
                .map(ScoredItem::item)
                .toList();
        source.put(key, limited);
    }

    private int priorityScore(Object item) {
        String text = String.valueOf(item);
        int score = 0;
        for (String keyword : HIGH_VALUE_KEYWORDS) {
            if (text.contains(keyword)) {
                score += 10;
            }
        }
        if (text.contains("high") || text.contains("confirmed") || text.contains("active")
                || text.contains("acute_exacerbation")) {
            score += 5;
        }
        return score;
    }

    private void appendUntilLimit(List<String> target, List<String> source, int maxSize) {
        for (String value : source) {
            if (target.size() >= maxSize) {
                return;
            }
            if (StringUtils.hasText(value)) {
                target.add(value);
            }
        }
    }

    private boolean containsAny(String text, List<String> keywords) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        for (String keyword : keywords) {
            if (StringUtils.hasText(keyword) && text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> childMap(Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (value instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) map;
            return typed;
        }
        Map<String, Object> created = new LinkedHashMap<>();
        source.put(key, created);
        return created;
    }

    private Map<String, Object> deepCopy(Map<String, Object> source) {
        return objectMapper.convertValue(source, new TypeReference<LinkedHashMap<String, Object>>() {
        });
    }

    private String toInputJson(Map<String, Object> fusionReadyFacts) {
        try {
            return objectMapper.writeValueAsString(Map.of("fusion_ready_facts", fusionReadyFacts));
        } catch (Exception e) {
            return EMPTY_JSON;
        }
    }

    private CompactionResult logAndReturn(String reqno,
                                          LocalDate dataDate,
                                          int originalChars,
                                          String compactedInputJson,
                                          int level) {
        CompactionResult result = new CompactionResult(
                compactedInputJson,
                level,
                originalChars,
                compactedInputJson.length()
        );
        log.info("daily_fusion input compacted, reqno={}, date={}, level={}, maxInputChars={}, originalChars={}, compactedChars={}",
                reqno, dataDate, level, maxInputChars, originalChars, result.compactedChars());
        return result;
    }

    private record ScoredItem(Object item, int index, int score) {
    }

    public record CompactionResult(
            String inputJson,
            int compactionLevel,
            int originalChars,
            int compactedChars
    ) {
    }
}
