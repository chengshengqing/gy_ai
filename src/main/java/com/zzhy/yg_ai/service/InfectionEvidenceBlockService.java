package com.zzhy.yg_ai.service;

import com.zzhy.yg_ai.domain.entity.PatientRawDataEntity;
import com.zzhy.yg_ai.domain.entity.PatientSummaryEntity;
import com.zzhy.yg_ai.domain.model.EvidenceBlockBuildResult;

public interface InfectionEvidenceBlockService {

    EvidenceBlockBuildResult buildBlocks(PatientRawDataEntity rawData, PatientSummaryEntity latestSummary);
}
