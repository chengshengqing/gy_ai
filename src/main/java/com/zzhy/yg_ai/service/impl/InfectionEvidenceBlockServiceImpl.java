package com.zzhy.yg_ai.service.impl;

import com.zzhy.yg_ai.domain.entity.PatientRawDataEntity;
import com.zzhy.yg_ai.domain.entity.PatientSummaryEntity;
import com.zzhy.yg_ai.domain.model.EvidenceBlockBuildResult;
import com.zzhy.yg_ai.service.InfectionEvidenceBlockService;
import com.zzhy.yg_ai.service.evidence.ClinicalTextBlockBuilder;
import com.zzhy.yg_ai.service.evidence.MidSemanticBlockBuilder;
import com.zzhy.yg_ai.service.evidence.StructuredFactBlockBuilder;
import com.zzhy.yg_ai.service.evidence.TimelineContextBlockBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class InfectionEvidenceBlockServiceImpl implements InfectionEvidenceBlockService {

    private final StructuredFactBlockBuilder structuredFactBlockBuilder;
    private final ClinicalTextBlockBuilder clinicalTextBlockBuilder;
    private final MidSemanticBlockBuilder midSemanticBlockBuilder;
    private final TimelineContextBlockBuilder timelineContextBlockBuilder;

    @Override
    public EvidenceBlockBuildResult buildBlocks(PatientRawDataEntity rawData, PatientSummaryEntity latestSummary) {
        if (rawData == null) {
            return new EvidenceBlockBuildResult(null, null, null, null);
        }
        return new EvidenceBlockBuildResult(
                structuredFactBlockBuilder.build(rawData, latestSummary),
                clinicalTextBlockBuilder.build(rawData, latestSummary),
                midSemanticBlockBuilder.build(rawData, latestSummary),
                timelineContextBlockBuilder.build(rawData, latestSummary)
        );
    }
}
