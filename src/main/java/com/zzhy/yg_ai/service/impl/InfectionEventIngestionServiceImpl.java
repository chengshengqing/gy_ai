package com.zzhy.yg_ai.service.impl;

import com.zzhy.yg_ai.domain.entity.InfectionEventPoolEntity;
import com.zzhy.yg_ai.domain.enums.InfectionExtractorType;
import com.zzhy.yg_ai.domain.model.EvidenceBlock;
import com.zzhy.yg_ai.domain.model.NormalizedInfectionEvent;
import com.zzhy.yg_ai.service.EventNormalizerService;
import com.zzhy.yg_ai.service.InfectionEventIngestionService;
import com.zzhy.yg_ai.service.InfectionEventPoolService;
import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class InfectionEventIngestionServiceImpl implements InfectionEventIngestionService {

    private final EventNormalizerService eventNormalizerService;
    private final InfectionEventPoolService infectionEventPoolService;

    @Override
    public List<InfectionEventPoolEntity> normalizeAndSave(EvidenceBlock block,
                                                           String extractorOutputJson,
                                                           InfectionExtractorType extractorType,
                                                           String promptVersion,
                                                           String modelName,
                                                           BigDecimal confidence) {
        List<NormalizedInfectionEvent> normalizedEvents = eventNormalizerService.normalize(
                block,
                extractorOutputJson,
                extractorType,
                promptVersion,
                modelName,
                confidence
        );
        return infectionEventPoolService.saveNormalizedEvents(normalizedEvents);
    }
}
