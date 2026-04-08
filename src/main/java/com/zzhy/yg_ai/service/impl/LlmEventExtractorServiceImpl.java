package com.zzhy.yg_ai.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzhy.yg_ai.ai.agent.WarningAgent;
import com.zzhy.yg_ai.ai.prompt.WarningAgentPrompt;
import com.zzhy.yg_ai.domain.entity.InfectionEventPoolEntity;
import com.zzhy.yg_ai.domain.entity.InfectionLlmNodeRunEntity;
import com.zzhy.yg_ai.domain.enums.InfectionExtractorType;
import com.zzhy.yg_ai.domain.enums.InfectionNodeType;
import com.zzhy.yg_ai.domain.enums.InfectionSourceSection;
import com.zzhy.yg_ai.domain.model.EvidenceBlock;
import com.zzhy.yg_ai.domain.model.EvidenceBlockBuildResult;
import com.zzhy.yg_ai.domain.model.LlmEventExtractorResult;
import com.zzhy.yg_ai.domain.model.NormalizedInfectionEvent;
import com.zzhy.yg_ai.service.EventNormalizerService;
import com.zzhy.yg_ai.service.InfectionEventPoolService;
import com.zzhy.yg_ai.service.InfectionLlmNodeRunService;
import com.zzhy.yg_ai.service.LlmEventExtractorService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class LlmEventExtractorServiceImpl implements LlmEventExtractorService {

    private static final String MODEL_NAME = "warning-agent-chat-model";
    private static final String STATUS_SUCCESS = "success";
    private static final String STATUS_SKIPPED = "skipped";
    private static final BigDecimal DEFAULT_SKIPPED_CONFIDENCE = new BigDecimal("0.20");
    private static final int MAX_ERROR_PREVIEW_LENGTH = 600;

    private final WarningAgent warningAgent;
    private final InfectionLlmNodeRunService infectionLlmNodeRunService;
    private final EventNormalizerService eventNormalizerService;
    private final InfectionEventPoolService infectionEventPoolService;
    private final ObjectMapper objectMapper;

    @Override
    public LlmEventExtractorResult extractAndSave(EvidenceBlockBuildResult blockBuildResult, List<EvidenceBlock> primaryBlocks) {
        EvidenceBlockBuildResult safeBuildResult = blockBuildResult == null
                ? new EvidenceBlockBuildResult(null, null, null, null)
                : blockBuildResult;
        List<EvidenceBlock> effectivePrimaryBlocks = primaryBlocks == null ? List.of() : List.copyOf(primaryBlocks);
        if (effectivePrimaryBlocks.isEmpty()) {
            return new LlmEventExtractorResult(null, List.of(), List.of(), 0);
        }

        EvidenceBlock timelineContext = safeBuildResult.timelineContextBlocks().isEmpty()
                ? null
                : safeBuildResult.timelineContextBlocks().get(0);
        EvidenceBlock structuredFactContext = safeBuildResult.structuredFactBlocks().isEmpty()
                ? null
                : safeBuildResult.structuredFactBlocks().get(0);

        List<NormalizedInfectionEvent> allNormalizedEvents = new ArrayList<>();
        List<InfectionEventPoolEntity> allPersistedEvents = new ArrayList<>();
        for (EvidenceBlock block : effectivePrimaryBlocks) {
            LlmBlockExtractionResult blockResult = extractSingleBlock(block, structuredFactContext, timelineContext);
            allNormalizedEvents.addAll(blockResult.normalizedEvents());
            allPersistedEvents.addAll(blockResult.persistedEvents());
        }

        return new LlmEventExtractorResult(
                buildAggregatedEventJson(allNormalizedEvents),
                allNormalizedEvents,
                allPersistedEvents,
                effectivePrimaryBlocks.size()
        );
    }

    private LlmBlockExtractionResult extractSingleBlock(EvidenceBlock block,
                                                       EvidenceBlock structuredFactContext,
                                                       EvidenceBlock timelineContext) {
        String promptVersion = WarningAgentPrompt.EVENT_EXTRACTOR_PROMPT_VERSION;
        String inputPayload = buildInputPayload(block, structuredFactContext, timelineContext);
        InfectionLlmNodeRunEntity runEntity = buildPendingRun(block, promptVersion, inputPayload);
        infectionLlmNodeRunService.createPendingRun(runEntity);
        long startedAt = System.currentTimeMillis();
        String rawOutput = null;
        int rawEventCount = 0;
        try {
            String prompt = WarningAgentPrompt.buildEventExtractorPrompt(block.blockType());
            rawOutput = warningAgent.callEventExtractor(prompt, inputPayload);
            PreparedExtractorOutput preparedOutput = prepareExtractorOutput(rawOutput);
            rawOutput = preparedOutput.outputJson();
            rawEventCount = preparedOutput.rawEventCount();
            BigDecimal confidence = preparedOutput.confidence();
            List<NormalizedInfectionEvent> normalizedEvents = eventNormalizerService.normalize(
                    block,
                    rawOutput,
                    InfectionExtractorType.LLM_EVENT_EXTRACTOR,
                    promptVersion,
                    MODEL_NAME,
                    confidence
            );
            List<InfectionEventPoolEntity> persistedEvents = infectionEventPoolService.saveNormalizedEvents(normalizedEvents);
            infectionLlmNodeRunService.markSuccess(
                    runEntity.getId(),
                    rawOutput,
                    buildExtractionRunPayload(rawEventCount, normalizedEvents.size(),
                            Math.max(0, rawEventCount - normalizedEvents.size()),
                            persistedEvents.size(),
                            normalizedEvents,
                            null),
                    confidence,
                    System.currentTimeMillis() - startedAt
            );
            return new LlmBlockExtractionResult(normalizedEvents, persistedEvents);
        } catch (Exception e) {
            String detailedError = buildFailureMessage(block, e, rawOutput);
            try {
                infectionLlmNodeRunService.markFailed(
                        runEntity.getId(),
                        rawOutput,
                        buildExtractionRunPayload(rawEventCount, 0, rawEventCount, 0, List.of(), detailedError),
                        "EVENT_EXTRACT_FAILED",
                        detailedError,
                        System.currentTimeMillis() - startedAt
                );
            } catch (Exception markFailedError) {
                e.addSuppressed(markFailedError);
                log.error("Failed to persist extractor failure audit, blockKey={}, runId={}",
                        block.blockKey(), runEntity.getId(), markFailedError);
            }
            log.error("LlmEventExtractor failed, blockKey={}, reqno={}, rawDataId={}, blockType={}, sourceRef={}, rawOutputPreview={}",
                    block.blockKey(),
                    block.reqno(),
                    block.rawDataId(),
                    block.blockType(),
                    block.sourceRef(),
                    abbreviate(rawOutput),
                    e);
            throw new IllegalStateException(detailedError, e);
        }
    }

    private PreparedExtractorOutput prepareExtractorOutput(String rawOutput) {
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

    private String buildFailureMessage(EvidenceBlock block, Exception exception, String rawOutput) {
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

    private InfectionLlmNodeRunEntity buildPendingRun(EvidenceBlock block, String promptVersion, String inputPayload) {
        InfectionLlmNodeRunEntity entity = new InfectionLlmNodeRunEntity();
        entity.setReqno(block.reqno());
        entity.setRawDataId(block.rawDataId());
        entity.setNodeRunKey(UUID.randomUUID().toString());
        entity.setNodeType(InfectionNodeType.EVENT_EXTRACTOR.name());
        entity.setNodeName("llm-event-extractor");
        entity.setPromptVersion(promptVersion);
        entity.setModelName(MODEL_NAME);
        entity.setInputPayload(inputPayload);
        return entity;
    }

    private String buildInputPayload(EvidenceBlock block,
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
        if (block.blockType() == com.zzhy.yg_ai.domain.enums.EvidenceBlockType.CLINICAL_TEXT) {
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
        if (json == null || json.isBlank()) {
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

    private BigDecimal extractConfidence(String rawOutput) {
        try {
            JsonNode root = objectMapper.readTree(rawOutput);
            JsonNode confidenceNode = root.get("confidence");
            if (confidenceNode != null && confidenceNode.isNumber()) {
                return confidenceNode.decimalValue();
            }
        } catch (Exception e) {
            log.debug("extract confidence failed", e);
        }
        return null;
    }

    private String buildAggregatedEventJson(List<NormalizedInfectionEvent> normalizedEvents) {
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode events = objectMapper.valueToTree(normalizedEvents == null ? List.of() : normalizedEvents);
        root.set("events", events);
        return writeJson(root);
    }

    private int countRawEvents(String rawOutput) {
        if (rawOutput == null || rawOutput.isBlank()) {
            return 0;
        }
        try {
            JsonNode root = objectMapper.readTree(rawOutput);
            JsonNode events = root.path("events");
            return events.isArray() ? events.size() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private String buildExtractionRunPayload(int rawEventCount,
                                             int normalizedEventCount,
                                             int rejectedEventCount,
                                             int persistedEventCount,
                                             List<NormalizedInfectionEvent> normalizedEvents,
                                             String errorMessage) {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode stats = objectMapper.createObjectNode();
        stats.put("raw_event_count", Math.max(0, rawEventCount));
        stats.put("normalized_event_count", Math.max(0, normalizedEventCount));
        stats.put("rejected_event_count", Math.max(0, rejectedEventCount));
        stats.put("persisted_event_count", Math.max(0, persistedEventCount));
        root.set("stats", stats);
        root.set("events", objectMapper.valueToTree(normalizedEvents == null ? List.of() : normalizedEvents));
        if (errorMessage != null && !errorMessage.isBlank()) {
            root.put("error_message", errorMessage);
        }
        return writeJson(root);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize event extractor payload", e);
        }
    }

    private record LlmBlockExtractionResult(
            List<NormalizedInfectionEvent> normalizedEvents,
            List<InfectionEventPoolEntity> persistedEvents
    ) {
    }

    private record PreparedExtractorOutput(
            String outputJson,
            int rawEventCount,
            BigDecimal confidence
    ) {
    }
}
