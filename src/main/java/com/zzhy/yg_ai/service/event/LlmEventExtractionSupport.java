package com.zzhy.yg_ai.service.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzhy.yg_ai.domain.enums.EvidenceBlockType;
import com.zzhy.yg_ai.domain.enums.InfectionSourceSection;
import com.zzhy.yg_ai.domain.model.EvidenceBlock;
import com.zzhy.yg_ai.domain.model.NormalizedInfectionEvent;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class LlmEventExtractionSupport {

    private static final String STATUS_SUCCESS = "success";
    private static final String STATUS_SKIPPED = "skipped";
    private static final BigDecimal DEFAULT_SKIPPED_CONFIDENCE = new BigDecimal("0.20");
    private static final int MAX_ERROR_PREVIEW_LENGTH = 600;

    private final ObjectMapper objectMapper;

    public String buildInputPayload(EvidenceBlock block,
                                    EvidenceBlock structuredFactContext,
                                    EvidenceBlock timelineContext) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("reqno", block.reqno());
        root.put("rawDataId", block.rawDataId());
        root.put("dataDate", block.dataDate() == null ? null : block.dataDate().toString());
        root.put("blockType", block.blockType().name());
        root.put("sourceType", block.sourceType().code());
        root.put("sourceRef", block.sourceRef());
        root.put("title", block.title());
        root.set("blockPayload", parseJson(block.payloadJson()));
        if (block.blockType() == EvidenceBlockType.CLINICAL_TEXT) {
            root.set("structuredContext", buildStructuredContext(structuredFactContext));
            if (timelineContext != null) {
                root.set("recentChangeContext", buildRecentChangeContext(timelineContext));
            } else {
                root.putNull("recentChangeContext");
            }
            root.putNull("timelineContext");
        } else if (timelineContext != null) {
            root.set("timelineContext", parseJson(timelineContext.payloadJson()));
        } else {
            root.putNull("timelineContext");
        }
        return writeJson(root);
    }

    public PreparedExtractorOutput prepareOutput(String rawOutput) {
        if (!StringUtils.hasText(rawOutput)) {
            throw new IllegalStateException("Event extractor model output is blank");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(rawOutput);
        } catch (Exception e) {
            throw new IllegalStateException("Event extractor model output is not valid JSON", e);
        }
        if (!root.isObject()) {
            throw new IllegalStateException("Event extractor model output must be a JSON object");
        }

        ObjectNode normalizedRoot = ((ObjectNode) root).deepCopy();
        ArrayNode events = normalizeEvents(normalizedRoot.get("events"));
        normalizedRoot.set("events", events);

        String status = normalizeResponseStatus(normalizedRoot.path("status").asText(null), events);
        normalizedRoot.put("status", status);

        BigDecimal confidence = normalizeConfidence(normalizedRoot.get("confidence"), status);
        if (confidence != null) {
            normalizedRoot.put("confidence", confidence);
        } else {
            normalizedRoot.putNull("confidence");
        }
        return new PreparedExtractorOutput(writeJson(normalizedRoot), events.size(), confidence);
    }

    public String buildRunPayload(PreparedExtractorOutput preparedOutput,
                                  List<NormalizedInfectionEvent> normalizedEvents,
                                  int persistedEventCount,
                                  String errorMessage) {
        int rawEventCount = preparedOutput == null ? 0 : preparedOutput.rawEventCount();
        int normalizedEventCount = normalizedEvents == null ? 0 : normalizedEvents.size();
        int rejectedEventCount = Math.max(0, rawEventCount - normalizedEventCount);

        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode stats = objectMapper.createObjectNode();
        stats.put("raw_event_count", rawEventCount);
        stats.put("normalized_event_count", normalizedEventCount);
        stats.put("rejected_event_count", rejectedEventCount);
        stats.put("persisted_event_count", Math.max(0, persistedEventCount));
        root.set("stats", stats);
        root.set("events", objectMapper.valueToTree(normalizedEvents == null ? List.of() : normalizedEvents));
        if (StringUtils.hasText(errorMessage)) {
            root.put("error_message", errorMessage);
        }
        return writeJson(root);
    }

    public String buildAggregatedEventJson(List<NormalizedInfectionEvent> normalizedEvents) {
        ObjectNode root = objectMapper.createObjectNode();
        root.set("events", objectMapper.valueToTree(normalizedEvents == null ? List.of() : normalizedEvents));
        return writeJson(root);
    }

    public String buildFailureMessage(EvidenceBlock block, Exception exception, String rawOutput) {
        String rootMessage = exception.getMessage();
        if (!StringUtils.hasText(rootMessage)) {
            rootMessage = exception.getClass().getSimpleName();
        }
        return "LlmEventExtractor failed for block=%s, blockType=%s, sourceRef=%s, cause=%s, rawOutputPreview=%s"
                .formatted(
                        block.blockKey(),
                        block.blockType(),
                        block.sourceRef(),
                        rootMessage,
                        abbreviate(rawOutput)
                );
    }

    private JsonNode buildStructuredContext(EvidenceBlock structuredFactContext) {
        ObjectNode result = objectMapper.createObjectNode();
        if (structuredFactContext == null) {
            return result;
        }
        JsonNode payload = parseJson(structuredFactContext.payloadJson());
        JsonNode dataNode = payload.path("data");
        if (!dataNode.isObject()) {
            return result;
        }
        dataNode.fields().forEachRemaining(entry -> {
            if (InfectionSourceSection.DIAGNOSIS.code().equals(entry.getKey())
                    || InfectionSourceSection.VITAL_SIGNS.code().equals(entry.getKey())
                    || InfectionSourceSection.TRANSFER.code().equals(entry.getKey())) {
                return;
            }
            JsonNode sectionNode = entry.getValue();
            if (!sectionNode.isObject()) {
                return;
            }
            ObjectNode sectionSummary = objectMapper.createObjectNode();
            ArrayNode priorityFacts = limitTextArray(sectionNode.path("priority_facts"), 4);
            ArrayNode referenceFacts = limitTextArray(sectionNode.path("reference_facts"), 2);
            if (!priorityFacts.isEmpty()) {
                sectionSummary.set("priority_facts", priorityFacts);
            }
            if (!referenceFacts.isEmpty()) {
                sectionSummary.set("reference_facts", referenceFacts);
            }
            if (!sectionSummary.isEmpty()) {
                result.set(entry.getKey(), sectionSummary);
            }
        });
        return result;
    }

    private JsonNode buildRecentChangeContext(EvidenceBlock timelineContext) {
        JsonNode parsed = parseJson(timelineContext.payloadJson());
        ObjectNode result = objectMapper.createObjectNode();
        JsonNode dataNode = parsed.path("data");
        JsonNode changesNode = dataNode.path("changes");
        if (changesNode.isArray()) {
            result.set("changes", limitTextArray(changesNode, 5));
            return result;
        }
        JsonNode directChanges = parsed.path("changes");
        if (directChanges.isArray()) {
            result.set("changes", limitTextArray(directChanges, 5));
            return result;
        }
        result.set("changes", objectMapper.createArrayNode());
        return result;
    }

    private ArrayNode limitTextArray(JsonNode node, int limit) {
        ArrayNode result = objectMapper.createArrayNode();
        if (node == null || !node.isArray() || limit <= 0) {
            return result;
        }
        int count = 0;
        for (JsonNode item : node) {
            if (item != null && item.isTextual() && !item.asText("").isBlank()) {
                result.add(item.asText().trim());
                count++;
            }
            if (count >= limit) {
                break;
            }
        }
        return result;
    }

    private JsonNode parseJson(String json) {
        if (!StringUtils.hasText(json)) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            ObjectNode fallback = objectMapper.createObjectNode();
            fallback.put("raw", json);
            return fallback;
        }
    }

    private ArrayNode normalizeEvents(JsonNode eventsNode) {
        if (eventsNode == null || eventsNode.isNull() || eventsNode.isMissingNode()) {
            return objectMapper.createArrayNode();
        }
        if (!eventsNode.isArray()) {
            throw new IllegalStateException("Event extractor response events must be an array");
        }
        return (ArrayNode) eventsNode.deepCopy();
    }

    private String normalizeResponseStatus(String rawStatus, ArrayNode events) {
        if (StringUtils.hasText(rawStatus)) {
            String normalized = rawStatus.trim().toLowerCase(Locale.ROOT);
            if (STATUS_SUCCESS.equals(normalized) || STATUS_SKIPPED.equals(normalized)) {
                if (STATUS_SUCCESS.equals(normalized) && events.isEmpty()) {
                    return STATUS_SKIPPED;
                }
                return normalized;
            }
        }
        return events.isEmpty() ? STATUS_SKIPPED : STATUS_SUCCESS;
    }

    private BigDecimal normalizeConfidence(JsonNode confidenceNode, String status) {
        BigDecimal confidence = parseConfidence(confidenceNode);
        if (confidence != null) {
            return confidence;
        }
        if (STATUS_SKIPPED.equals(status)) {
            return DEFAULT_SKIPPED_CONFIDENCE;
        }
        throw new IllegalStateException("Event extractor response confidence is invalid");
    }

    private BigDecimal parseConfidence(JsonNode confidenceNode) {
        if (confidenceNode == null || confidenceNode.isNull() || confidenceNode.isMissingNode()) {
            return null;
        }
        try {
            BigDecimal value;
            if (confidenceNode.isNumber()) {
                value = confidenceNode.decimalValue();
            } else if (confidenceNode.isTextual() && StringUtils.hasText(confidenceNode.asText())) {
                value = new BigDecimal(confidenceNode.asText().trim());
            } else {
                return null;
            }
            if (value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(BigDecimal.ONE) > 0) {
                return null;
            }
            return value;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String abbreviate(String value) {
        if (!StringUtils.hasText(value)) {
            return "<empty>";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= MAX_ERROR_PREVIEW_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, MAX_ERROR_PREVIEW_LENGTH) + "...";
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize event extractor payload", e);
        }
    }

    public record PreparedExtractorOutput(
            String outputJson,
            int rawEventCount,
            BigDecimal confidence
    ) {
    }
}
