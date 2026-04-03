package com.zzhy.yg_ai.service;

import com.zzhy.yg_ai.domain.model.EvidenceBlock;

public interface StructuredFactRefinementService {

    EvidenceBlock refine(EvidenceBlock structuredFactBlock, EvidenceBlock timelineContextBlock);
}
