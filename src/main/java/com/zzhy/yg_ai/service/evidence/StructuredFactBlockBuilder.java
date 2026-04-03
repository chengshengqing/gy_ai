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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class StructuredFactBlockBuilder extends AbstractEvidenceBlockBuilder {

    private static final List<String> SECTION_ORDER = List.of(
            "diagnosis",
            "vital_signs",
            "lab_results",
            "imaging",
            "doctor_orders",
            "use_medicine",
            "transfer",
            "operation"
    );

    private static final Map<String, Integer> PRIORITY_LIMITS = Map.of(
            "diagnosis", 3,
            "vital_signs", 3,
            "lab_results", 6,
            "imaging", 3,
            "doctor_orders", 4,
            "use_medicine", 4,
            "transfer", 2,
            "operation", 3
    );

    private static final Map<String, Integer> REFERENCE_LIMITS = Map.of(
            "diagnosis", 1,
            "vital_signs", 2,
            "lab_results", 3,
            "imaging", 1,
            "doctor_orders", 1,
            "use_medicine", 1,
            "transfer", 1,
            "operation", 1
    );

    private static final Map<String, Integer> RAW_LIMITS = Map.of(
            "diagnosis", 6,
            "vital_signs", 4,
            "lab_results", 4,
            "imaging", 4,
            "doctor_orders", 4,
            "use_medicine", 4,
            "transfer", 3,
            "operation", 3
    );

    private static final int PANEL_RESULTS_LIMIT = 6;

    private static final Comparator<CandidateFact> CANDIDATE_COMPARATOR = Comparator
            .comparingInt(CandidateFact::priorityScore).reversed()
            .thenComparingInt(CandidateFact::completenessScore).reversed()
            .thenComparing(CandidateFact::summaryText);

    public StructuredFactBlockBuilder(ObjectMapper objectMapper) {
        super(objectMapper);
    }

    @Override
    public List<EvidenceBlock> build(PatientRawDataEntity rawData, String timelineWindowJson) {
        ObjectNode root = parseObject(rawData == null ? null : rawData.getFilterDataJson(), "filter_data_json",
                rawData == null ? null : rawData.getId());
        if (!hasStructuredFactContent(root)) {
            return List.of();
        }

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("section", "structured_fact_bundle");
        payload.put("source", "filter_data_json");
        if (rawData != null && rawData.getDataDate() != null) {
            payload.put("dataDate", rawData.getDataDate().toString());
        } else {
            payload.putNull("dataDate");
        }

        ObjectNode data = objectMapper.createObjectNode();
        data.set("diagnosis", buildDiagnosisSection(root.path("diagnosis")));
        data.set("vital_signs", buildVitalSignsSection(root.path("vital_signs")));
        data.set("lab_results", buildLabResultsSection(root.path("lab_results")));
        data.set("imaging", buildListSection("imaging", root.path("imaging"), this::summarizeImaging));
        data.set("doctor_orders", buildDoctorOrdersSection(root.path("doctor_orders")));
        data.set("use_medicine", buildListSection("use_medicine", root.path("use_medicine"), this::summarizeMedication));
        data.set("transfer", buildListSection("transfer", root.path("transfer"), this::summarizeTransfer));
        data.set("operation", buildListSection("operation", root.path("operation"), this::summarizeOperation));
        payload.set("data", data);

        return List.of(createBlock(rawData,
                EvidenceBlockType.STRUCTURED_FACT,
                InfectionSourceType.RAW,
                "filter_data_json.structured_fact_bundle",
                "structured_fact_bundle",
                payload,
                false));
    }

    private boolean hasStructuredFactContent(ObjectNode root) {
        for (String section : SECTION_ORDER) {
            if (hasMeaningfulContent(root.path(section))) {
                return true;
            }
        }
        return false;
    }

    private ObjectNode buildDiagnosisSection(JsonNode section) {
        ArrayNode rawItems = copyArray(section);
        List<CandidateFact> candidates = new ArrayList<>();
        for (JsonNode item : rawItems) {
            String summary = item == null ? "" : item.asText("");
            if (!StringUtils.hasText(summary)) {
                continue;
            }
            candidates.add(candidate("diagnosis", item, summary, 0));
        }
        return buildArraySection("diagnosis", candidates, selectTopRawCandidates(candidates, rawItems, "diagnosis"));
    }

    private ObjectNode buildVitalSignsSection(JsonNode section) {
        ArrayNode rawItems = copyArray(section);
        List<CandidateFact> abnormalCandidates = new ArrayList<>();
        List<CandidateFact> normalCandidates = new ArrayList<>();
        for (JsonNode item : rawItems) {
            String summary = joinParts(
                    item.path("time").asText(""),
                    fieldPair("temp", item.path("temp").asText("")),
                    fieldPair("pulse", item.path("pulse").asText("")),
                    fieldPair("resp", item.path("resp").asText("")),
                    fieldPair("bp", item.path("bp").asText("")),
                    fieldPair("stool", item.path("stool").asText(""))
            );
            if (!StringUtils.hasText(summary)) {
                continue;
            }
            if (hasVitalSignAbnormality(item)) {
                abnormalCandidates.add(candidate("vital_signs", item, summary, 8));
            } else {
                normalCandidates.add(candidate("vital_signs", item, summary, 0));
            }
        }
        ObjectNode node = objectMapper.createObjectNode();
        List<CandidateFact> rankedAbnormal = rankCandidates(abnormalCandidates);
        node.set("priority_facts", selectSummaries(rankedAbnormal, priorityLimit("vital_signs"), Set.of()));
        List<CandidateFact> rankedNormal = rankCandidates(normalCandidates);
        node.set("reference_facts", selectSummaries(rankedNormal, referenceLimit("vital_signs"), Set.of()));
        List<CandidateFact> rawCandidates = !abnormalCandidates.isEmpty() ? abnormalCandidates : normalCandidates;
        node.set("raw", selectTopRawCandidates(rawCandidates, rawItems, "vital_signs"));
        return node;
    }

    private ObjectNode buildLabResultsSection(JsonNode section) {
        List<CandidateFact> abnormalTestCandidates = new ArrayList<>();
        List<CandidateFact> normalTestCandidates = new ArrayList<>();
        List<CandidateFact> positiveMicrobeCandidates = new ArrayList<>();
        List<CandidateFact> negativeMicrobeCandidates = new ArrayList<>();
        JsonNode testPanels = section.path("test_panels");
        if (testPanels.isArray()) {
            for (JsonNode panel : testPanels) {
                String panelName = panel.path("panel_name").asText("");
                JsonNode results = panel.path("results");
                if (results.isArray()) {
                    for (JsonNode result : results) {
                        String summary = joinParts(
                                panelName,
                                result.path("name").asText(""),
                                result.path("value").asText(""),
                                result.path("unit").asText(""),
                                result.path("flag").asText("")
                        );
                        if (!StringUtils.hasText(summary)) {
                            continue;
                        }
                        int salienceBoost = result.path("is_abnormal").asBoolean(false) ? 5 : 0;
                        salienceBoost += panel.path("abnormal_count").asInt(0);
                        CandidateFact candidate = candidate("lab_results", result, summary, salienceBoost);
                        if (result.path("is_abnormal").asBoolean(false)) {
                            abnormalTestCandidates.add(candidate);
                        } else {
                            normalTestCandidates.add(candidate);
                        }
                    }
                } else if (StringUtils.hasText(panelName)) {
                    CandidateFact candidate = candidate("lab_results",
                            panel,
                            joinParts(panelName, fieldPair("abnormal_count",
                                    String.valueOf(panel.path("abnormal_count").asInt(0)))),
                            panel.path("abnormal_count").asInt(0));
                    if (panel.path("abnormal_count").asInt(0) > 0) {
                        abnormalTestCandidates.add(candidate);
                    } else {
                        normalTestCandidates.add(candidate);
                    }
                }
            }
        }
        JsonNode microbePanels = section.path("microbe_panels");
        if (microbePanels.isArray()) {
            for (JsonNode panel : microbePanels) {
                String panelName = panel.path("panel_name").asText("");
                JsonNode results = panel.path("results");
                if (results.isArray()) {
                    for (JsonNode result : results) {
                        String summary = joinParts(
                                panelName,
                                result.path("organism").asText(""),
                                result.path("result").asText(""),
                                result.path("flag").asText("")
                        );
                        if (!StringUtils.hasText(summary)) {
                            continue;
                        }
                        int salienceBoost = result.path("is_abnormal").asBoolean(false) ? 12 : 6;
                        if (hasMeaningfulContent(result.path("drug_sensitivity"))) {
                            salienceBoost += 2;
                        }
                        CandidateFact candidate = candidate("lab_results", result, summary, salienceBoost);
                        if (result.path("is_abnormal").asBoolean(false)) {
                            positiveMicrobeCandidates.add(candidate);
                        } else {
                            negativeMicrobeCandidates.add(candidate);
                        }
                    }
                }
            }
        }

        ObjectNode node = objectMapper.createObjectNode();
        List<CandidateFact> rankedPositiveMicrobe = rankCandidates(positiveMicrobeCandidates);
        List<CandidateFact> rankedAbnormalTest = rankCandidates(abnormalTestCandidates);
        ArrayNode priority = buildLabPriorityFacts(rankedPositiveMicrobe,
                rankedAbnormalTest,
                priorityLimit("lab_results"));
        node.set("priority_facts", priority);

        Set<String> excluded = summarySet(priority);
        List<CandidateFact> referenceCandidates = new ArrayList<>();
        referenceCandidates.addAll(rankCandidates(negativeMicrobeCandidates));
        referenceCandidates.addAll(rankCandidates(normalTestCandidates));
        referenceCandidates.addAll(rankedPositiveMicrobe);
        referenceCandidates.addAll(rankedAbnormalTest);
        referenceCandidates = rankCandidates(referenceCandidates);
        node.set("reference_facts", selectSummaries(referenceCandidates, referenceLimit("lab_results"), excluded));

        ObjectNode raw = objectMapper.createObjectNode();
        raw.set("test_panels", limitPanels(testPanels, rawLimit("lab_results"), PANEL_RESULTS_LIMIT));
        raw.set("microbe_panels", limitPanels(microbePanels, rawLimit("lab_results"), PANEL_RESULTS_LIMIT));
        node.set("raw", raw);
        return node;
    }

    private ObjectNode buildDoctorOrdersSection(JsonNode section) {
        List<CandidateFact> candidates = new ArrayList<>();
        candidates.addAll(extractTextCandidates(section.path("long_term"), "doctor_orders"));
        candidates.addAll(extractTextCandidates(section.path("temporary"), "doctor_orders"));
        candidates.addAll(extractTextCandidates(section.path("sg"), "doctor_orders"));

        ObjectNode node = objectMapper.createObjectNode();
        List<CandidateFact> ranked = rankCandidates(candidates);
        node.set("priority_facts", objectMapper.createArrayNode());
        node.set("reference_facts", selectSummaries(ranked, referenceLimit("doctor_orders"), Set.of()));

        ObjectNode raw = objectMapper.createObjectNode();
        raw.set("long_term", limitTextArray(section.path("long_term"), rawLimit("doctor_orders")));
        raw.set("temporary", limitTextArray(section.path("temporary"), rawLimit("doctor_orders")));
        raw.set("sg", limitTextArray(section.path("sg"), rawLimit("doctor_orders")));
        node.set("raw", raw);
        return node;
    }

    private ObjectNode buildListSection(String sectionName, JsonNode section, SectionSummarizer summarizer) {
        ArrayNode rawItems = copyArray(section);
        List<CandidateFact> candidates = new ArrayList<>();
        for (JsonNode item : rawItems) {
            String summary = summarizer.summarize(item);
            if (!StringUtils.hasText(summary)) {
                continue;
            }
            candidates.add(candidate(sectionName, item, summary, 0));
        }
        return buildArraySection(sectionName, candidates, selectTopRawCandidates(candidates, rawItems, sectionName));
    }

    private ObjectNode buildArraySection(String sectionName, List<CandidateFact> candidates, ArrayNode raw) {
        ObjectNode node = objectMapper.createObjectNode();
        List<CandidateFact> ranked = rankCandidates(candidates);
        node.set("priority_facts", selectSummaries(ranked, priorityLimit(sectionName), Set.of()));
        node.set("reference_facts", selectSummaries(ranked,
                referenceLimit(sectionName),
                summarySet(node.path("priority_facts"))));
        node.set("raw", raw);
        return node;
    }

    private List<CandidateFact> extractTextCandidates(JsonNode node, String sectionName) {
        List<CandidateFact> candidates = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return candidates;
        }
        for (JsonNode item : node) {
            if (item != null && item.isTextual() && StringUtils.hasText(item.asText())) {
                candidates.add(candidate(sectionName, item, item.asText().trim(), 0));
            }
        }
        return candidates;
    }

    private CandidateFact candidate(String sectionName, JsonNode rawNode, String summary, int salienceBoost) {
        int completeness = completenessScore(rawNode);
        int salience = salienceScore(rawNode) + salienceBoost;
        int novelty = noveltyScore(summary);
        int priorityScore = salience * 10 + completeness * 2 + novelty;
        return new CandidateFact(sectionName,
                summary.trim(),
                rawNode == null ? null : rawNode.deepCopy(),
                salience,
                completeness,
                novelty,
                priorityScore);
    }

    private int salienceScore(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return 0;
        }
        int score = 0;
        if (hasMeaningfulContent(node.path("abn"))) {
            score += 3;
        }
        if (node.path("abnormal_count").asInt(0) > 0) {
            score += 3;
        }
        if (node.path("is_abnormal").asBoolean(false)) {
            score += 3;
        }
        if (hasMeaningfulContent(node.path("result"))) {
            score += 1;
        }
        if (hasMeaningfulContent(node.path("drug_sensitivity"))) {
            score += 1;
        }
        return score;
    }

    private int completenessScore(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return 0;
        }
        if (node.isTextual()) {
            return StringUtils.hasText(node.asText()) ? 1 : 0;
        }
        if (node.isArray()) {
            int score = 0;
            for (JsonNode item : node) {
                if (hasMeaningfulContent(item)) {
                    score++;
                }
            }
            return score;
        }
        if (!node.isObject()) {
            return hasMeaningfulContent(node) ? 1 : 0;
        }
        int score = 0;
        var fields = node.fields();
        while (fields.hasNext()) {
            if (hasMeaningfulContent(fields.next().getValue())) {
                score++;
            }
        }
        return score;
    }

    private int noveltyScore(String summary) {
        return 0;
    }

    private ArrayNode buildLabPriorityFacts(List<CandidateFact> rankedPositiveMicrobe,
                                            List<CandidateFact> rankedAbnormalTest,
                                            int totalLimit) {
        ArrayNode result = objectMapper.createArrayNode();
        Set<String> seen = new LinkedHashSet<>();

        int reservedMicrobe = rankedPositiveMicrobe.isEmpty() ? 0 : Math.min(2, totalLimit);
        appendTopSummaries(result, rankedPositiveMicrobe, reservedMicrobe, seen);
        appendTopSummaries(result, rankedAbnormalTest, totalLimit - result.size(), seen);
        if (result.size() < totalLimit) {
            appendTopSummaries(result, rankedPositiveMicrobe, totalLimit - result.size(), seen);
        }
        return result;
    }

    private void appendTopSummaries(ArrayNode target,
                                    List<CandidateFact> candidates,
                                    int limit,
                                    Set<String> seen) {
        if (limit <= 0) {
            return;
        }
        int added = 0;
        for (CandidateFact candidate : candidates) {
            String normalized = normalizeSummary(candidate.summaryText());
            if (!seen.add(normalized)) {
                continue;
            }
            target.add(candidate.summaryText());
            added++;
            if (added >= limit) {
                break;
            }
        }
    }

    private List<CandidateFact> rankCandidates(List<CandidateFact> candidates) {
        Map<String, CandidateFact> bestBySummary = new LinkedHashMap<>();
        for (CandidateFact candidate : candidates) {
            String key = normalizeSummary(candidate.summaryText());
            CandidateFact existing = bestBySummary.get(key);
            if (existing == null || CANDIDATE_COMPARATOR.compare(candidate, existing) < 0) {
                bestBySummary.put(key, candidate);
            }
        }
        List<CandidateFact> ranked = new ArrayList<>(bestBySummary.values());
        ranked.sort(CANDIDATE_COMPARATOR);
        return ranked;
    }

    private ArrayNode selectSummaries(List<CandidateFact> ranked, int limit, Set<String> excludedSummaries) {
        ArrayNode result = objectMapper.createArrayNode();
        Set<String> seen = new LinkedHashSet<>(excludedSummaries);
        for (CandidateFact candidate : ranked) {
            String normalized = normalizeSummary(candidate.summaryText());
            if (seen.contains(normalized)) {
                continue;
            }
            result.add(candidate.summaryText());
            seen.add(normalized);
            if (result.size() >= limit) {
                break;
            }
        }
        return result;
    }

    private Set<String> summarySet(JsonNode node) {
        Set<String> result = new LinkedHashSet<>();
        if (node == null || !node.isArray()) {
            return result;
        }
        for (JsonNode item : node) {
            if (item != null && item.isTextual() && StringUtils.hasText(item.asText())) {
                result.add(normalizeSummary(item.asText()));
            }
        }
        return result;
    }

    private ArrayNode selectTopRawCandidates(List<CandidateFact> candidates, ArrayNode fallback, String sectionName) {
        ArrayNode raw = objectMapper.createArrayNode();
        List<CandidateFact> ranked = rankCandidates(candidates);
        Set<String> seen = new LinkedHashSet<>();
        for (CandidateFact candidate : ranked) {
            if (candidate.rawNode() == null) {
                continue;
            }
            String key = normalizeSummary(candidate.summaryText());
            if (!seen.add(key)) {
                continue;
            }
            raw.add(candidate.rawNode().deepCopy());
            if (raw.size() >= rawLimit(sectionName)) {
                return raw;
            }
        }
        if (raw.isEmpty() && fallback != null) {
            for (JsonNode item : fallback) {
                if (!hasMeaningfulContent(item)) {
                    continue;
                }
                raw.add(item.deepCopy());
                if (raw.size() >= rawLimit(sectionName)) {
                    break;
                }
            }
        }
        return raw;
    }

    private ArrayNode limitPanels(JsonNode panels, int panelLimit, int resultLimit) {
        ArrayNode result = objectMapper.createArrayNode();
        if (panels == null || !panels.isArray()) {
            return result;
        }

        List<CandidateFact> panelCandidates = new ArrayList<>();
        for (JsonNode panel : panels) {
            String summary = joinParts(
                    panel.path("panel_name").asText(""),
                    fieldPair("abnormal_count", String.valueOf(panel.path("abnormal_count").asInt(0))),
                    panel.path("sample_type").asText(""),
                    panel.path("test_time").asText(""),
                    panel.path("sample_time").asText("")
            );
            if (!StringUtils.hasText(summary)) {
                summary = panel.path("panel_name").asText("");
            }
            panelCandidates.add(candidate("lab_results", panel, summary, panel.path("abnormal_count").asInt(0)));
        }

        for (CandidateFact candidate : rankCandidates(panelCandidates)) {
            JsonNode rawPanel = candidate.rawNode();
            if (rawPanel == null || !rawPanel.isObject()) {
                continue;
            }
            ObjectNode panelCopy = ((ObjectNode) rawPanel).deepCopy();
            JsonNode rawResults = rawPanel.path("results");
            if (rawResults.isArray()) {
                panelCopy.set("results", limitPanelResults(rawResults, resultLimit));
            }
            result.add(panelCopy);
            if (result.size() >= panelLimit) {
                break;
            }
        }
        return result;
    }

    private ArrayNode limitPanelResults(JsonNode results, int limit) {
        ArrayNode result = objectMapper.createArrayNode();
        if (results == null || !results.isArray()) {
            return result;
        }
        List<CandidateFact> candidates = new ArrayList<>();
        for (JsonNode item : results) {
            String summary = joinParts(
                    item.path("name").asText(""),
                    item.path("organism").asText(""),
                    item.path("value").asText(""),
                    item.path("unit").asText(""),
                    item.path("result").asText(""),
                    item.path("flag").asText("")
            );
            if (!StringUtils.hasText(summary)) {
                continue;
            }
            candidates.add(candidate("lab_results", item, summary, 0));
        }
        for (CandidateFact candidate : rankCandidates(candidates)) {
            if (candidate.rawNode() == null) {
                continue;
            }
            result.add(candidate.rawNode().deepCopy());
            if (result.size() >= limit) {
                break;
            }
        }
        return result;
    }

    private ArrayNode limitTextArray(JsonNode node, int limit) {
        ArrayNode result = objectMapper.createArrayNode();
        if (node == null || !node.isArray()) {
            return result;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode item : node) {
            if (item == null || !item.isTextual() || !StringUtils.hasText(item.asText())) {
                continue;
            }
            String text = item.asText().trim();
            if (!seen.add(text.toLowerCase(Locale.ROOT))) {
                continue;
            }
            result.add(text);
            if (result.size() >= limit) {
                break;
            }
        }
        return result;
    }

    private ArrayNode copyArray(JsonNode node) {
        if (node != null && node.isArray()) {
            return (ArrayNode) node.deepCopy();
        }
        return objectMapper.createArrayNode();
    }

    private int priorityLimit(String sectionName) {
        return PRIORITY_LIMITS.getOrDefault(sectionName, 3);
    }

    private int referenceLimit(String sectionName) {
        return REFERENCE_LIMITS.getOrDefault(sectionName, 1);
    }

    private int rawLimit(String sectionName) {
        return RAW_LIMITS.getOrDefault(sectionName, 3);
    }

    private String summarizeImaging(JsonNode item) {
        return joinParts(item.path("type").asText(""), joinArray(item.path("result"), "；"));
    }

    private String summarizeMedication(JsonNode item) {
        return joinParts(
                item.path("medication_name").asText(""),
                item.path("category").asText(""),
                item.path("route").asText(""),
                item.path("frequency").asText(""),
                item.path("start_time").asText(""),
                item.path("purpose").asText("")
        );
    }

    private String summarizeTransfer(JsonNode item) {
        return joinParts(
                item.path("transfer_time").asText(""),
                item.path("from_department").asText(""),
                "->",
                item.path("to_department").asText("")
        );
    }

    private String summarizeOperation(JsonNode item) {
        return joinParts(
                item.path("operation_time").asText(""),
                item.path("operation_name").asText(""),
                item.path("cut_type").asText(""),
                item.path("anesthesia_mode").asText("")
        );
    }

    private String fieldPair(String key, String value) {
        if (!StringUtils.hasText(value) || "0".equals(value.trim())) {
            return "";
        }
        return key + "=" + value.trim();
    }

    private String joinArray(JsonNode node, String delimiter) {
        if (node == null || !node.isArray()) {
            return "";
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (item != null && item.isTextual() && StringUtils.hasText(item.asText())) {
                values.add(item.asText().trim());
            }
        }
        return String.join(delimiter, values);
    }

    private String joinParts(String... parts) {
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (!StringUtils.hasText(part)) {
                continue;
            }
            String value = part.trim();
            if (builder.length() > 0 && !"->".equals(value)) {
                builder.append(' ');
            }
            builder.append(value);
        }
        return builder.toString().trim();
    }

    private String normalizeSummary(String summary) {
        if (!StringUtils.hasText(summary)) {
            return "";
        }
        return summary.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private boolean hasVitalSignAbnormality(JsonNode item) {
        JsonNode abn = item.path("abn");
        return abn.isArray() && !abn.isEmpty();
    }

    private record CandidateFact(
            String sectionName,
            String summaryText,
            JsonNode rawNode,
            int salienceScore,
            int completenessScore,
            int noveltyScore,
            int priorityScore
    ) {
    }

    @FunctionalInterface
    private interface SectionSummarizer {
        String summarize(JsonNode item);
    }
}
