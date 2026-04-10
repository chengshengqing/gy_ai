package com.zzhy.yg_ai.service.impl;

import com.zzhy.yg_ai.ai.gateway.AiGateway;
import com.zzhy.yg_ai.ai.prompt.WarningPromptCatalog;
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
import com.zzhy.yg_ai.service.event.LlmEventExtractionSupport;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import com.zzhy.yg_ai.pipeline.scheduler.policy.PipelineStage;

@Slf4j
@Service
@RequiredArgsConstructor
public class LlmEventExtractorServiceImpl implements LlmEventExtractorService {

    private static final String MODEL_NAME = "warning-agent-chat-model";

    private final AiGateway aiGateway;
    private final InfectionLlmNodeRunService infectionLlmNodeRunService;
    private final EventNormalizerService eventNormalizerService;
    private final InfectionEventPoolService infectionEventPoolService;
    private final LlmEventExtractionSupport llmEventExtractionSupport;

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
                llmEventExtractionSupport.buildAggregatedEventJson(allNormalizedEvents),
                allNormalizedEvents,
                allPersistedEvents,
                effectivePrimaryBlocks.size()
        );
    }

    private LlmBlockExtractionResult extractSingleBlock(EvidenceBlock block,
                                                       EvidenceBlock structuredFactContext,
                                                       EvidenceBlock timelineContext) {
        String promptVersion = WarningPromptCatalog.EVENT_EXTRACTOR_PROMPT_VERSION;
        String inputPayload = llmEventExtractionSupport.buildInputPayload(block, structuredFactContext, timelineContext);
        InfectionLlmNodeRunEntity runEntity = buildPendingRun(block, promptVersion, inputPayload);
        infectionLlmNodeRunService.createPendingRun(runEntity);
        long startedAt = System.currentTimeMillis();
        String rawOutput = null;
        LlmEventExtractionSupport.PreparedExtractorOutput preparedOutput = null;
        try {
            String prompt = WarningPromptCatalog.buildEventExtractorPrompt(block.blockType());
            rawOutput = aiGateway.callSystem(
                    PipelineStage.EVENT_EXTRACT,
                    InfectionNodeType.EVENT_EXTRACTOR.name(),
                    prompt,
                    inputPayload
            );
            preparedOutput = llmEventExtractionSupport.prepareOutput(rawOutput);
            rawOutput = preparedOutput.outputJson();
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
                    llmEventExtractionSupport.buildRunPayload(preparedOutput, normalizedEvents, persistedEvents.size(), null),
                    confidence,
                    System.currentTimeMillis() - startedAt
            );
            return new LlmBlockExtractionResult(normalizedEvents, persistedEvents);
        } catch (Exception e) {
            String detailedError = llmEventExtractionSupport.buildFailureMessage(block, e, rawOutput);
            try {
                infectionLlmNodeRunService.markFailed(
                        runEntity.getId(),
                        rawOutput,
                        llmEventExtractionSupport.buildRunPayload(preparedOutput, List.of(), 0, detailedError),
                        "EVENT_EXTRACT_FAILED",
                        detailedError,
                        System.currentTimeMillis() - startedAt
                );
            } catch (Exception markFailedError) {
                e.addSuppressed(markFailedError);
                log.error("Failed to persist extractor failure audit, blockKey={}, runId={}",
                        block.blockKey(), runEntity.getId(), markFailedError);
            }
            log.error("LlmEventExtractor failed, blockKey={}, reqno={}, rawDataId={}, blockType={}, sourceRef={}, detail={}",
                    block.blockKey(),
                    block.reqno(),
                    block.rawDataId(),
                    block.blockType(),
                    block.sourceRef(),
                    detailedError,
                    e);
            throw new IllegalStateException(detailedError, e);
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

    private record LlmBlockExtractionResult(
            List<NormalizedInfectionEvent> normalizedEvents,
            List<InfectionEventPoolEntity> persistedEvents
    ) {
    }
}
