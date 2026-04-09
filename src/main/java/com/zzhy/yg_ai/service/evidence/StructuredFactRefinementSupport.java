package com.zzhy.yg_ai.service.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzhy.yg_ai.domain.model.EvidenceBlock;
import com.zzhy.yg_ai.domain.schema.InfectionEventSchema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class StructuredFactRefinementSupport {

    private static final int MAX_CANDIDATES = 12;
    private static final int MAX_LEAF_VALUES = 8;
    private static final String PROMOTION_PROMOTE = "promote";
    private static final String PROMOTION_KEEP_REFERENCE = "keep_reference";
    private static final String PROMOTION_DROP = "drop";

    private final ObjectMapper objectMapper;

    public PreparedRefinement prepare(EvidenceBlock structuredFactBlock, EvidenceBlock timelineContextBlock) {
        ObjectNode payload = parseObject(structuredFactBlock == null ? null : structuredFactBlock.payloadJson());
        JsonNode dataNode = payload.path("data");
        if (!(dataNode instanceof ObjectNode data)) {
            return PreparedRefinement.empty(payload);
        }

        JsonNode timelineNode = timelineContextBlock == null
                ? null
                : parseNode(timelineContextBlock.payloadJson());

        LinkedHashMap<String, SectionContext> sectionContexts = new LinkedHashMap<>();
        ArrayNode sectionsInput = objectMapper.createArrayNode();
        for (String sectionName : InfectionEventSchema.refinementSourceSectionCodes()) {
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
            return PreparedRefinement.empty(payload);
        }

        ObjectNode input = objectMapper.createObjectNode();
        input.put("reqno", structuredFactBlock == null ? null : structuredFactBlock.reqno());
        input.put("dataDate", structuredFactBlock == null || structuredFactBlock.dataDate() == null
                ? null
                : structuredFactBlock.dataDate().toString());
        input.set("sections", sectionsInput);
        if (timelineNode != null && !timelineNode.isMissingNode() && !timelineNode.isNull()) {
            input.set("timelineContext", timelineNode.deepCopy());
        } else {
            input.putNull("timelineContext");
        }

        return new PreparedRefinement(payload, writeJson(input), sectionContexts);
    }

    public ParsedAssignments parseAssignments(String rawOutput, PreparedRefinement preparedRefinement) {
        JsonNode root = parseNodeOrThrow(rawOutput, "StructuredFact refinement response is not valid JSON");
        JsonNode items = root.path("items");
        if (!items.isArray()) {
            throw new IllegalStateException("StructuredFact refinement response items must be an array");
        }

        Map<String, Set<String>> promoteIdsBySection = new LinkedHashMap<>();
        Map<String, Set<String>> keepIdsBySection = new LinkedHashMap<>();
        Map<String, Set<String>> dropIdsBySection = new LinkedHashMap<>();
        List<String> invalidItems = new ArrayList<>();
        for (JsonNode item : items) {
            String sourceSection = item.path("source_section").asText("");
            String candidateId = item.path("candidate_id").asText("");
            String promotion = item.path("promotion").asText("").trim().toLowerCase(Locale.ROOT);
            SectionContext context = preparedRefinement.sectionContexts().get(sourceSection);
            if (!StringUtils.hasText(sourceSection)
                    || !StringUtils.hasText(candidateId)
                    || !candidateId.startsWith(sourceSection + ":")
                    || context == null
                    || !context.candidates().containsKey(candidateId)) {
                invalidItems.add(buildInvalidSummary(sourceSection, candidateId, promotion));
                continue;
            }
            switch (promotion) {
                case PROMOTION_PROMOTE -> promoteIdsBySection
                        .computeIfAbsent(sourceSection, key -> new LinkedHashSet<>())
                        .add(candidateId);
                case PROMOTION_KEEP_REFERENCE -> keepIdsBySection
                        .computeIfAbsent(sourceSection, key -> new LinkedHashSet<>())
                        .add(candidateId);
                case PROMOTION_DROP -> dropIdsBySection
                        .computeIfAbsent(sourceSection, key -> new LinkedHashSet<>())
                        .add(candidateId);
                default -> invalidItems.add(buildInvalidSummary(sourceSection, candidateId, promotion));
            }
        }

        return new ParsedAssignments(promoteIdsBySection, keepIdsBySection, dropIdsBySection, invalidItems);
    }

    public AppliedRefinement applyAssignments(PreparedRefinement preparedRefinement, ParsedAssignments assignments) {
        if (!preparedRefinement.hasCandidates() || !assignments.hasAssignments()) {
            return AppliedRefinement.unchanged();
        }

        boolean changed = false;
        Set<String> changedSections = new LinkedHashSet<>();
        for (Map.Entry<String, SectionContext> entry : preparedRefinement.sectionContexts().entrySet()) {
            String sectionName = entry.getKey();
            SectionContext context = entry.getValue();
            SectionRefinement refinement = applyRefinement(
                    context,
                    assignments.promoteIdsBySection().getOrDefault(sectionName, Set.of()),
                    assignments.keepIdsBySection().getOrDefault(sectionName, Set.of()),
                    assignments.dropIdsBySection().getOrDefault(sectionName, Set.of())
            );
            if (refinement == null || !refinement.changed()) {
                continue;
            }
            context.section().set("priority_facts", refinement.priorityFacts());
            context.section().set("reference_facts", refinement.referenceFacts());
            changedSections.add(sectionName);
            changed = true;
        }
        return new AppliedRefinement(changed, changedSections);
    }

    public String writePayload(ObjectNode payload) {
        return writeJson(payload);
    }

    private SectionRefinement applyRefinement(SectionContext context,
                                              Set<String> promoteIds,
                                              Set<String> keepIds,
                                              Set<String> dropIds) {
        if (context == null || (promoteIds.isEmpty() && keepIds.isEmpty() && dropIds.isEmpty())) {
            return null;
        }
        ArrayNode originalPriority = copyTextArray(context.section().path("priority_facts"));
        ArrayNode originalReference = copyTextArray(context.section().path("reference_facts"));

        LinkedHashSet<String> promotedTexts = new LinkedHashSet<>();
        for (String candidateId : promoteIds) {
            promotedTexts.add(context.candidates().get(candidateId));
        }
        ArrayNode newPriority = mergeUniqueTexts(promotedTexts, originalPriority);

        Set<String> promotedNormalized = normalizeSet(promotedTexts);
        Set<String> droppedNormalized = normalizeIds(dropIds, context.candidates());
        ArrayNode newReference = objectMapper.createArrayNode();
        Set<String> seenReference = new LinkedHashSet<>();
        for (JsonNode item : originalReference) {
            if (item == null || !item.isTextual() || !StringUtils.hasText(item.asText())) {
                continue;
            }
            String text = item.asText().trim();
            String normalized = normalize(text);
            if (promotedNormalized.contains(normalized)
                    || droppedNormalized.contains(normalized)
                    || !seenReference.add(normalized)) {
                continue;
            }
            newReference.add(text);
        }
        for (String candidateId : keepIds) {
            String text = context.candidates().get(candidateId);
            String normalized = normalize(text);
            if (promotedNormalized.contains(normalized)
                    || droppedNormalized.contains(normalized)
                    || !seenReference.add(normalized)) {
                continue;
            }
            newReference.add(text);
        }

        boolean changed = !jsonEquals(originalPriority, newPriority) || !jsonEquals(originalReference, newReference);
        return new SectionRefinement(newPriority, newReference, changed);
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

    private boolean containsText(Iterable<String> texts, String target) {
        String normalizedTarget = normalize(target);
        for (String text : texts) {
            if (normalize(text).equals(normalizedTarget)) {
                return true;
            }
        }
        return false;
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

    private String buildInvalidSummary(String sourceSection, String candidateId, String promotion) {
        return "source_section=%s,candidate_id=%s,promotion=%s"
                .formatted(sourceSection, candidateId, promotion);
    }

    private String normalize(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
    }

    private ObjectNode parseObject(String json) {
        JsonNode node = parseNode(json);
        return node != null && node.isObject() ? (ObjectNode) node : objectMapper.createObjectNode();
    }

    private JsonNode parseNode(String json) {
        if (!StringUtils.hasText(json)) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    private JsonNode parseNodeOrThrow(String json, String errorMessage) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(errorMessage, e);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("StructuredFact refinement json write failed", e);
        }
    }

    private boolean jsonEquals(JsonNode left, JsonNode right) {
        return left == right || (left != null && left.equals(right));
    }

    public record PreparedRefinement(
            ObjectNode payload,
            String inputPayload,
            Map<String, SectionContext> sectionContexts
    ) {

        public static PreparedRefinement empty(ObjectNode payload) {
            return new PreparedRefinement(payload, "{}", Map.of());
        }

        public boolean hasCandidates() {
            return sectionContexts != null && !sectionContexts.isEmpty();
        }

        public int sectionCount() {
            return sectionContexts == null ? 0 : sectionContexts.size();
        }

        public int candidateCount() {
            if (sectionContexts == null || sectionContexts.isEmpty()) {
                return 0;
            }
            int total = 0;
            for (SectionContext context : sectionContexts.values()) {
                total += context == null || context.candidates() == null ? 0 : context.candidates().size();
            }
            return total;
        }
    }

    public record ParsedAssignments(
            Map<String, Set<String>> promoteIdsBySection,
            Map<String, Set<String>> keepIdsBySection,
            Map<String, Set<String>> dropIdsBySection,
            List<String> invalidItems
    ) {

        public boolean hasAssignments() {
            return !promoteIdsBySection.isEmpty() || !keepIdsBySection.isEmpty() || !dropIdsBySection.isEmpty();
        }

        public int promotedCount() {
            return count(promoteIdsBySection);
        }

        public int keptCount() {
            return count(keepIdsBySection);
        }

        public int droppedCount() {
            return count(dropIdsBySection);
        }

        public int invalidItemCount() {
            return invalidItems == null ? 0 : invalidItems.size();
        }

        public String invalidSummary() {
            if (invalidItems == null || invalidItems.isEmpty()) {
                return "";
            }
            return String.join("; ", invalidItems.subList(0, Math.min(3, invalidItems.size())));
        }

        private static int count(Map<String, Set<String>> valuesBySection) {
            if (valuesBySection == null || valuesBySection.isEmpty()) {
                return 0;
            }
            int total = 0;
            for (Set<String> values : valuesBySection.values()) {
                total += values == null ? 0 : values.size();
            }
            return total;
        }
    }

    public record AppliedRefinement(boolean changed, Set<String> changedSections) {

        public static AppliedRefinement unchanged() {
            return new AppliedRefinement(false, Set.of());
        }
    }

    public record SectionContext(ObjectNode section, LinkedHashMap<String, String> candidates) {
    }

    private record SectionRefinement(ArrayNode priorityFacts, ArrayNode referenceFacts, boolean changed) {
    }
}
