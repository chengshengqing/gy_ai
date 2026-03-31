package com.zzhy.yg_ai.service.evidence;

import com.zzhy.yg_ai.domain.entity.PatientRawDataEntity;
import com.zzhy.yg_ai.domain.entity.PatientSummaryEntity;
import com.zzhy.yg_ai.domain.model.EvidenceBlock;
import java.util.List;

public interface EvidenceBlockBuilder {

    List<EvidenceBlock> build(PatientRawDataEntity rawData, PatientSummaryEntity latestSummary);
}
