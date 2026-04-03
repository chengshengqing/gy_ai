package com.zzhy.yg_ai.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzhy.yg_ai.ai.agent.WarningAgent;
import com.zzhy.yg_ai.ai.prompt.WarningAgentPrompt;
import com.zzhy.yg_ai.domain.enums.EvidenceBlockType;
import com.zzhy.yg_ai.domain.model.EvidenceBlock;
import com.zzhy.yg_ai.service.StructuredFactRefinementService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class StructuredFactRefinementServiceImpl implements StructuredFactRefinementService {

    private static final List<String> TARGET_SECTIONS = List.of(
            "doctor_orders",
            "use_medicine",
            "operation"
    );

    private static final int MAX_CANDIDATES = 12;
    private static final int MAX_LEAF_VALUES = 8;

    private final WarningAgent warningAgent;
    private final ObjectMapper objectMapper;

    @Override
    public EvidenceBlock refine(EvidenceBlock structuredFactBlock, EvidenceBlock timelineContextBlock) {
        if (structuredFactBlock == null || structuredFactBlock.blockType() != EvidenceBlockType.STRUCTURED_FACT) {
            return structuredFactBlock;
        }
        try {
            JsonNode payloadNode = parseJson(structuredFactBlock.payloadJson());
            if (!(payloadNode instanceof ObjectNode payload)) {
                return structuredFactBlock;
            }
            JsonNode dataNode = payload.path("data");
            if (!(dataNode instanceof ObjectNode data)) {
                return structuredFactBlock;
            }

            JsonNode timelineNode = timelineContextBlock == null ? null : parseJson(timelineContextBlock.payloadJson());
            LinkedHashMap<String, SectionContext> sectionContexts = new LinkedHashMap<>();
            ArrayNode sectionsInput = objectMapper.createArrayNode();
            for (String sectionName : TARGET_SECTIONS) {
                JsonNode sectionNode = data.path(sectionName);
                if (!(sectionNode instanceof ObjectNode section)) {
                    continue;
                }
                LinkedHashMap<String, String> candidates = buildCandidates(section, sectionName);
                if (candidates.isEmpty()) {
                    continue;
                }
                sectionContexts.put(sectionName, new SectionContext(section, candidates));
                sectionsInput.add(buildSectionInput(sectionName, section, candidates));
            }
            if (sectionContexts.isEmpty()) {
                return structuredFactBlock;
            }
            BatchRefinement batchRefinement = refineBatch(
                    structuredFactBlock.reqno(),
                    structuredFactBlock.dataDate() == null ? null : structuredFactBlock.dataDate().toString(),
                    sectionsInput,
                    timelineNode,
                    sectionContexts);
            if (batchRefinement == null || !batchRefinement.changed()) {
                return structuredFactBlock;
            }
            for (Map.Entry<String, SectionRefinement> entry : batchRefinement.refinements().entrySet()) {
                SectionContext context = sectionContexts.get(entry.getKey());
                if (context == null) {
                    continue;
                }
                context.section().set("priority_facts", entry.getValue().priorityFacts());
                context.section().set("reference_facts", entry.getValue().referenceFacts());
            }
            return new EvidenceBlock(
                    structuredFactBlock.blockKey(),
                    structuredFactBlock.reqno(),
                    structuredFactBlock.rawDataId(),
                    structuredFactBlock.dataDate(),
                    structuredFactBlock.blockType(),
                    structuredFactBlock.sourceType(),
                    structuredFactBlock.sourceRef(),
                    structuredFactBlock.title(),
                    writeJson(payload),
                    structuredFactBlock.contextOnly()
            );
        } catch (Exception e) {
            log.warn("StructuredFact refinement failed, blockKey={}", structuredFactBlock.blockKey(), e);
            return structuredFactBlock;
        }
    }

    private BatchRefinement refineBatch(String reqno,
                                        String dataDate,
                                        ArrayNode sectionsInput,
                                        JsonNode timelineNode,
                                        Map<String, SectionContext> sectionContexts) {
        if (sectionsInput.isEmpty()) {
            return null;
        }
        ObjectNode input = objectMapper.createObjectNode();
        input.put("reqno", reqno);
        input.put("dataDate", dataDate);
        input.set("sections", sectionsInput);
        if (timelineNode != null && !timelineNode.isMissingNode() && !timelineNode.isNull()) {
            input.set("timelineContext", timelineNode.deepCopy());
        } else {
            input.putNull("timelineContext");
        }

        String prompt = WarningAgentPrompt.buildStructuredFactRefinementPrompt();
        String rawOutput = warningAgent.callStructuredFactRefinement(prompt, writeJson(input));
        JsonNode root = parseJson(rawOutput);
        JsonNode items = root.path("items");
        if (!items.isArray()) {
            return null;
        }

        Map<String, Set<String>> promoteIdsBySection = new LinkedHashMap<>();
        Map<String, Set<String>> keepIdsBySection = new LinkedHashMap<>();
        Map<String, Set<String>> dropIdsBySection = new LinkedHashMap<>();
        for (JsonNode item : items) {
            String sourceSection = item.path("source_section").asText("");
            String candidateId = item.path("candidate_id").asText("");
            SectionContext context = sectionContexts.get(sourceSection);
            if (!StringUtils.hasText(sourceSection)
                    || !StringUtils.hasText(candidateId)
                    || context == null
                    || !context.candidates().containsKey(candidateId)) {
                continue;
            }
            String promotion = item.path("promotion").asText("").trim().toLowerCase(Locale.ROOT);
            if ("promote".equals(promotion)) {
                promoteIdsBySection.computeIfAbsent(sourceSection, key -> new LinkedHashSet<>()).add(candidateId);
            } else if ("keep_reference".equals(promotion)) {
                keepIdsBySection.computeIfAbsent(sourceSection, key -> new LinkedHashSet<>()).add(candidateId);
            } else if ("drop".equals(promotion)) {
                dropIdsBySection.computeIfAbsent(sourceSection, key -> new LinkedHashSet<>()).add(candidateId);
            }
        }
        if (promoteIdsBySection.isEmpty() && keepIdsBySection.isEmpty() && dropIdsBySection.isEmpty()) {
            return null;
        }

        boolean changed = false;
        Map<String, SectionRefinement> refinements = new LinkedHashMap<>();
        for (Map.Entry<String, SectionContext> entry : sectionContexts.entrySet()) {
            String sectionName = entry.getKey();
            SectionContext context = entry.getValue();
            SectionRefinement refinement = applyRefinement(
                    context.section(),
                    context.candidates(),
                    promoteIdsBySection.getOrDefault(sectionName, Set.of()),
                    keepIdsBySection.getOrDefault(sectionName, Set.of()),
                    dropIdsBySection.getOrDefault(sectionName, Set.of())
            );
            if (refinement != null && refinement.changed()) {
                refinements.put(sectionName, refinement);
                changed = true;
            }
        }
        return new BatchRefinement(refinements, changed);
    }

    private SectionRefinement applyRefinement(ObjectNode section,
                                             LinkedHashMap<String, String> candidates,
                                             Set<String> promoteIds,
                                             Set<String> keepIds,
                                             Set<String> dropIds) {
        if (promoteIds.isEmpty() && keepIds.isEmpty() && dropIds.isEmpty()) {
            return null;
        }
        ArrayNode originalPriority = copyTextArray(section.path("priority_facts"));
        ArrayNode originalReference = copyTextArray(section.path("reference_facts"));

        LinkedHashSet<String> promotedTexts = new LinkedHashSet<>();
        for (String candidateId : promoteIds) {
            promotedTexts.add(candidates.get(candidateId));
        }
        ArrayNode newPriority = mergeUniqueTexts(promotedTexts, originalPriority);

        Set<String> promotedNormalized = normalizeSet(promotedTexts);
        Set<String> droppedNormalized = normalizeIds(dropIds, candidates);
        ArrayNode newReference = objectMapper.createArrayNode();
        Set<String> seenReference = new LinkedHashSet<>();
        for (JsonNode item : originalReference) {
            if (item == null || !item.isTextual() || !StringUtils.hasText(item.asText())) {
                continue;
            }
            String text = item.asText().trim();
            String normalized = normalize(text);
            if (promotedNormalized.contains(normalized) || droppedNormalized.contains(normalized) || !seenReference.add(normalized)) {
                continue;
            }
            newReference.add(text);
        }
        for (String candidateId : keepIds) {
            String text = candidates.get(candidateId);
            String normalized = normalize(text);
            if (promotedNormalized.contains(normalized) || droppedNormalized.contains(normalized) || !seenReference.add(normalized)) {
                continue;
            }
            newReference.add(text);
        }

        boolean changed = !jsonEquals(originalPriority, newPriority) || !jsonEquals(originalReference, newReference);
        return new SectionRefinement(newPriority, newReference, changed);
    }

    private ObjectNode buildSectionInput(String sectionName,
                                         ObjectNode section,
                                         LinkedHashMap<String, String> candidates) {
        ObjectNode sectionInput = objectMapper.createObjectNode();
        sectionInput.put("source_section", sectionName);
        sectionInput.set("sectionPayload", section.deepCopy());
        ArrayNode candidateItems = objectMapper.createArrayNode();
        for (Map.Entry<String, String> entry : candidates.entrySet()) {
            ObjectNode item = objectMapper.createObjectNode();
            item.put("candidate_id", entry.getKey());
            item.put("source_text", entry.getValue());
            candidateItems.add(item);
        }
        sectionInput.set("candidate_items", candidateItems);
        return sectionInput;
    }

    private LinkedHashMap<String, String> buildCandidates(ObjectNode section, String sectionName) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        ArrayNode referenceFacts = copyTextArray(section.path("reference_facts"));
        int nextId = 1;
        for (JsonNode item : referenceFacts) {
            if (!item.isTextual() || !StringUtils.hasText(item.asText())) {
                continue;
            }
            String text = item.asText().trim();
            if (containsText(result.values(), text)) {
                continue;
            }
            result.put(candidateId(sectionName, nextId++), text);
            if (result.size() >= MAX_CANDIDATES) {
                return result;
            }
        }

        List<String> rawTexts = new ArrayList<>();
        collectRawTexts(section.path("raw"), rawTexts, MAX_CANDIDATES * 2);
        for (String text : rawTexts) {
            if (!StringUtils.hasText(text) || containsText(result.values(), text)) {
                continue;
            }
            result.put(candidateId(sectionName, nextId++), text);
            if (result.size() >= MAX_CANDIDATES) {
                break;
            }
        }
        return result;
    }

    private void collectRawTexts(JsonNode node, List<String> collector, int limit) {
        if (node == null || node.isMissingNode() || node.isNull() || collector.size() >= limit) {
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
                collectRawTexts(item, collector, limit);
                if (collector.size() >= limit) {
                    break;
                }
            }
            return;
        }
        if (node.isObject()) {
            String summary = summarizeObject(node);
            if (StringUtils.hasText(summary)) {
                collector.add(summary);
            }
        }
    }

    private String summarizeObject(JsonNode node) {
        List<String> values = new ArrayList<>();
        collectLeafValues(node, values, MAX_LEAF_VALUES);
        return String.join(" ", values).trim();
    }

    private void collectLeafValues(JsonNode node, List<String> values, int limit) {
        if (node == null || node.isMissingNode() || node.isNull() || values.size() >= limit) {
            return;
        }
        if (node.isTextual()) {
            String text = node.asText().trim();
            if (StringUtils.hasText(text)) {
                values.add(text);
            }
            return;
        }
        if (node.isNumber() || node.isBoolean()) {
            values.add(node.asText());
            return;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                collectLeafValues(item, values, limit);
                if (values.size() >= limit) {
                    break;
                }
            }
            return;
        }
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext() && values.size() < limit) {
                collectLeafValues(fields.next().getValue(), values, limit);
            }
        }
    }

    private boolean containsText(Iterable<String> texts, String target) {
        String normalizedTarget = normalize(target);
        for (String text : texts) {
            if (normalize(text).equals(normalizedTarget)) {
                return true;
            }
        }
        return false;
    }

    private ArrayNode mergeUniqueTexts(Set<String> first, ArrayNode second) {
        ArrayNode result = objectMapper.createArrayNode();
        Set<String> seen = new LinkedHashSet<>();
        for (String text : first) {
            if (!StringUtils.hasText(text)) {
                continue;
            }
            String normalized = normalize(text);
            if (seen.add(normalized)) {
                result.add(text);
            }
        }
        for (JsonNode item : second) {
            if (item == null || !item.isTextual() || !StringUtils.hasText(item.asText())) {
                continue;
            }
            String text = item.asText().trim();
            String normalized = normalize(text);
            if (seen.add(normalized)) {
                result.add(text);
            }
        }
        return result;
    }

    private ArrayNode copyTextArray(JsonNode node) {
        ArrayNode result = objectMapper.createArrayNode();
        if (node == null || !node.isArray()) {
            return result;
        }
        for (JsonNode item : node) {
            if (item != null && item.isTextual() && StringUtils.hasText(item.asText())) {
                result.add(item.asText().trim());
            }
        }
        return result;
    }

    private Set<String> normalizeSet(Set<String> values) {
        Set<String> result = new LinkedHashSet<>();
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                result.add(normalize(value));
            }
        }
        return result;
    }

    private Set<String> normalizeIds(Set<String> ids, Map<String, String> candidates) {
        Set<String> result = new LinkedHashSet<>();
        for (String id : ids) {
            String text = candidates.get(id);
            if (StringUtils.hasText(text)) {
                result.add(normalize(text));
            }
        }
        return result;
    }

    private String candidateId(String sectionName, int index) {
        return sectionName + ":c" + index;
    }

    private String normalize(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
    }

    private JsonNode parseJson(String json) {
        if (!StringUtils.hasText(json)) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            log.warn("Parse refinement json failed", e);
            return objectMapper.createObjectNode();
        }
    }

    private String writeJson(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException("Write refinement payload failed", e);
        }
    }

    private boolean jsonEquals(JsonNode left, JsonNode right) {
        return left == right || (left != null && left.equals(right));
    }

    private record SectionRefinement(
            ArrayNode priorityFacts,
            ArrayNode referenceFacts,
            boolean changed
    ) {
    }

    private record SectionContext(
            ObjectNode section,
            LinkedHashMap<String, String> candidates
    ) {
    }

    private record BatchRefinement(
            Map<String, SectionRefinement> refinements,
            boolean changed
    ) {
    }
}
