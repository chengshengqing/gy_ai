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
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class LlmEventExtractorServiceImpl implements LlmEventExtractorService {

    private static final String MODEL_NAME = "warning-agent-chat-model";

    private final WarningAgent warningAgent;
    private final InfectionLlmNodeRunService infectionLlmNodeRunService;
    private final EventNormalizerService eventNormalizerService;
    private final InfectionEventPoolService infectionEventPoolService;
    private final ObjectMapper objectMapper;

    @Override
    public LlmEventExtractorResult extractAndSave(EvidenceBlockBuildResult blockBuildResult) {
        EvidenceBlockBuildResult safeBuildResult = blockBuildResult == null
                ? new EvidenceBlockBuildResult(null, null, null, null)
                : blockBuildResult;
        List<EvidenceBlock> primaryBlocks = safeBuildResult.primaryBlocks();
        if (primaryBlocks.isEmpty()) {
            return new LlmEventExtractorResult(null, List.of(), List.of(), 0);
        }

        EvidenceBlock timelineContext = safeBuildResult.timelineContextBlocks().isEmpty()
                ? null
                : safeBuildResult.timelineContextBlocks().get(0);

        List<NormalizedInfectionEvent> allNormalizedEvents = new ArrayList<>();
        List<InfectionEventPoolEntity> allPersistedEvents = new ArrayList<>();
        for (EvidenceBlock block : primaryBlocks) {
            LlmBlockExtractionResult blockResult = extractSingleBlock(block, timelineContext);
            allNormalizedEvents.addAll(blockResult.normalizedEvents());
            allPersistedEvents.addAll(blockResult.persistedEvents());
        }

        return new LlmEventExtractorResult(
                buildAggregatedEventJson(allNormalizedEvents),
                allNormalizedEvents,
                allPersistedEvents,
                primaryBlocks.size()
        );
    }

    private LlmBlockExtractionResult extractSingleBlock(EvidenceBlock block, EvidenceBlock timelineContext) {
        String promptVersion = WarningAgentPrompt.EVENT_EXTRACTOR_PROMPT_VERSION;
        String inputPayload = buildInputPayload(block, timelineContext);
        InfectionLlmNodeRunEntity runEntity = buildPendingRun(block, promptVersion, inputPayload);
        infectionLlmNodeRunService.createPendingRun(runEntity);
        long startedAt = System.currentTimeMillis();
        try {
            String prompt = WarningAgentPrompt.buildEventExtractorPrompt(block.blockType());
            String rawOutput = warningAgent.callEventExtractor(prompt, inputPayload);
            BigDecimal confidence = extractConfidence(rawOutput);
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
                    writeJson(normalizedEvents),
                    confidence,
                    System.currentTimeMillis() - startedAt
            );
            return new LlmBlockExtractionResult(normalizedEvents, persistedEvents);
        } catch (Exception e) {
            infectionLlmNodeRunService.markFailed(
                    runEntity.getId(),
                    null,
                    "EVENT_EXTRACT_FAILED",
                    e.getMessage(),
                    System.currentTimeMillis() - startedAt
            );
            throw new IllegalStateException("LlmEventExtractor failed for block=" + block.blockKey(), e);
        }
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

    private String buildInputPayload(EvidenceBlock block, EvidenceBlock timelineContext) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("reqno", block.reqno());
        root.put("rawDataId", block.rawDataId());
        root.put("dataDate", block.dataDate() == null ? null : block.dataDate().toString());
        root.put("blockType", block.blockType().name());
        root.put("sourceType", block.sourceType().code());
        root.put("sourceRef", block.sourceRef());
        root.put("title", block.title());
        root.set("blockPayload", parseJson(block.payloadJson()));
        if (timelineContext != null) {
            root.set("timelineContext", parseJson(timelineContext.payloadJson()));
        } else {
            root.putNull("timelineContext");
        }
        return writeJson(root);
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
}
