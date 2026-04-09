package com.zzhy.yg_ai.service.impl;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzhy.yg_ai.ai.gateway.WarningModelGateway;
import com.zzhy.yg_ai.ai.prompt.WarningPromptCatalog;
import com.zzhy.yg_ai.domain.entity.InfectionLlmNodeRunEntity;
import com.zzhy.yg_ai.domain.enums.EvidenceBlockType;
import com.zzhy.yg_ai.domain.enums.InfectionNodeType;
import com.zzhy.yg_ai.domain.model.EvidenceBlock;
import com.zzhy.yg_ai.service.InfectionLlmNodeRunService;
import com.zzhy.yg_ai.service.StructuredFactRefinementService;
import com.zzhy.yg_ai.service.evidence.StructuredFactRefinementSupport;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class StructuredFactRefinementServiceImpl implements StructuredFactRefinementService {

    private static final String MODEL_NAME = "warning-agent-chat-model";

    private final WarningModelGateway warningModelGateway;
    private final InfectionLlmNodeRunService infectionLlmNodeRunService;
    private final StructuredFactRefinementSupport structuredFactRefinementSupport;
    private final ObjectMapper objectMapper;

    @Override
    public EvidenceBlock refine(EvidenceBlock structuredFactBlock, EvidenceBlock timelineContextBlock) {
        if (structuredFactBlock == null || structuredFactBlock.blockType() != EvidenceBlockType.STRUCTURED_FACT) {
            return structuredFactBlock;
        }
        StructuredFactRefinementSupport.PreparedRefinement preparedRefinement =
                structuredFactRefinementSupport.prepare(structuredFactBlock, timelineContextBlock);
        if (!preparedRefinement.hasCandidates()) {
            return structuredFactBlock;
        }

        InfectionLlmNodeRunEntity runEntity = buildPendingRun(structuredFactBlock, preparedRefinement.inputPayload());
        infectionLlmNodeRunService.createPendingRun(runEntity);
        long startedAt = System.currentTimeMillis();
        String rawOutput = null;
        try {
            rawOutput = warningModelGateway.callStructuredFactRefinement(
                    WarningPromptCatalog.buildStructuredFactRefinementPrompt(),
                    preparedRefinement.inputPayload()
            );
            StructuredFactRefinementSupport.ParsedAssignments assignments =
                    structuredFactRefinementSupport.parseAssignments(rawOutput, preparedRefinement);
            if (assignments.invalidItemCount() > 0) {
                throw new IllegalStateException(
                        "StructuredFact refinement response contains invalid items: " + assignments.invalidSummary()
                );
            }

            StructuredFactRefinementSupport.AppliedRefinement appliedRefinement =
                    structuredFactRefinementSupport.applyAssignments(preparedRefinement, assignments);

            infectionLlmNodeRunService.markSuccess(
                    runEntity.getId(),
                    rawOutput,
                    buildRunPayload(preparedRefinement, assignments, appliedRefinement.changedSections(), null),
                    null,
                    System.currentTimeMillis() - startedAt
            );

            if (!appliedRefinement.changed()) {
                return structuredFactBlock;
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
                    structuredFactRefinementSupport.writePayload(preparedRefinement.payload()),
                    structuredFactBlock.contextOnly()
            );
        } catch (Exception e) {
            try {
                infectionLlmNodeRunService.markFailed(
                        runEntity.getId(),
                        rawOutput,
                        buildRunPayload(
                                preparedRefinement,
                                null,
                                null,
                                e.getMessage()
                        ),
                        "STRUCTURED_FACT_REFINEMENT_FAILED",
                        e.getMessage(),
                        System.currentTimeMillis() - startedAt
                );
            } catch (Exception markFailedError) {
                e.addSuppressed(markFailedError);
                log.error("Failed to persist refinement failure audit, blockKey={}, runId={}",
                        structuredFactBlock.blockKey(), runEntity.getId(), markFailedError);
            }
            log.warn("StructuredFact refinement failed, blockKey={}", structuredFactBlock.blockKey(), e);
            return structuredFactBlock;
        }
    }

    private InfectionLlmNodeRunEntity buildPendingRun(EvidenceBlock block, String inputPayload) {
        InfectionLlmNodeRunEntity entity = new InfectionLlmNodeRunEntity();
        entity.setReqno(block.reqno());
        entity.setRawDataId(block.rawDataId());
        entity.setNodeRunKey(UUID.randomUUID().toString());
        entity.setNodeType(InfectionNodeType.STRUCTURED_FACT_REFINEMENT.name());
        entity.setNodeName("structured-fact-refinement");
        entity.setPromptVersion(WarningPromptCatalog.STRUCTURED_FACT_REFINEMENT_PROMPT_VERSION);
        entity.setModelName(MODEL_NAME);
        entity.setInputPayload(inputPayload);
        return entity;
    }

    private String buildRunPayload(StructuredFactRefinementSupport.PreparedRefinement preparedRefinement,
                                   StructuredFactRefinementSupport.ParsedAssignments assignments,
                                   java.util.Set<String> changedSections,
                                   String errorMessage) {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode stats = objectMapper.createObjectNode();
        stats.put("raw_section_count", preparedRefinement == null ? 0 : preparedRefinement.sectionCount());
        stats.put("raw_candidate_count", preparedRefinement == null ? 0 : preparedRefinement.candidateCount());
        stats.put("promoted_candidate_count", assignments == null ? 0 : assignments.promotedCount());
        stats.put("kept_reference_count", assignments == null ? 0 : assignments.keptCount());
        stats.put("dropped_candidate_count", assignments == null ? 0 : assignments.droppedCount());
        stats.put("changed_section_count", changedSections == null ? 0 : changedSections.size());
        root.set("stats", stats);
        root.set("refinements", objectMapper.valueToTree(changedSections == null ? java.util.Set.of() : changedSections));
        if (StringUtils.hasText(errorMessage)) {
            root.put("error_message", errorMessage);
        }
        return writeJson(root);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Write structured fact refinement audit payload failed", e);
        }
    }
}
