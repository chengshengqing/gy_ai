package com.zzhy.yg_ai.service.impl;

import com.zzhy.yg_ai.domain.entity.PatientRawDataEntity;
import com.zzhy.yg_ai.domain.model.EvidenceBlock;
import com.zzhy.yg_ai.domain.model.EvidenceBlockBuildResult;
import com.zzhy.yg_ai.service.InfectionEvidenceBlockService;
import com.zzhy.yg_ai.service.StructuredFactRefinementService;
import com.zzhy.yg_ai.service.evidence.ClinicalTextBlockBuilder;
import com.zzhy.yg_ai.service.evidence.MidSemanticBlockBuilder;
import com.zzhy.yg_ai.service.evidence.StructuredFactBlockBuilder;
import com.zzhy.yg_ai.service.evidence.TimelineContextBlockBuilder;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class InfectionEvidenceBlockServiceImpl implements InfectionEvidenceBlockService {

    private final StructuredFactBlockBuilder structuredFactBlockBuilder;
    private final ClinicalTextBlockBuilder clinicalTextBlockBuilder;
    private final MidSemanticBlockBuilder midSemanticBlockBuilder;
    private final TimelineContextBlockBuilder timelineContextBlockBuilder;
    private final StructuredFactRefinementService structuredFactRefinementService;

    @Override
    public EvidenceBlockBuildResult buildBlocks(PatientRawDataEntity rawData, String timelineWindowJson) {
        if (rawData == null) {
            return new EvidenceBlockBuildResult(null, null, null, null);
        }
        List<EvidenceBlock> timelineBlocks = timelineContextBlockBuilder.build(rawData, timelineWindowJson);
        EvidenceBlock timelineBlock = timelineBlocks.isEmpty() ? null : timelineBlocks.get(0);
        List<EvidenceBlock> structuredBlocks = structuredFactBlockBuilder.build(rawData, timelineWindowJson).stream()
                .map(block -> structuredFactRefinementService.refine(block, timelineBlock))
                .toList();
        return new EvidenceBlockBuildResult(
                structuredBlocks,
                clinicalTextBlockBuilder.build(rawData, timelineWindowJson),
                midSemanticBlockBuilder.build(rawData, timelineWindowJson),
                timelineBlocks
        );
    }
}
