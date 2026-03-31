package com.zzhy.yg_ai.service;

import com.zzhy.yg_ai.domain.enums.InfectionExtractorType;
import com.zzhy.yg_ai.domain.model.EvidenceBlock;
import com.zzhy.yg_ai.domain.model.NormalizedInfectionEvent;
import java.math.BigDecimal;
import java.util.List;

public interface EventNormalizerService {

    List<NormalizedInfectionEvent> normalize(EvidenceBlock block,
                                             String extractorOutputJson,
                                             InfectionExtractorType extractorType,
                                             String promptVersion,
                                             String modelName,
                                             BigDecimal confidence);
}
