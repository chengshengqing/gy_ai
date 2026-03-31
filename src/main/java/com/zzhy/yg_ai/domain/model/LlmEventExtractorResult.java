package com.zzhy.yg_ai.domain.model;

import com.zzhy.yg_ai.domain.entity.InfectionEventPoolEntity;
import java.util.List;

public record LlmEventExtractorResult(
        String eventJson,
        List<NormalizedInfectionEvent> normalizedEvents,
        List<InfectionEventPoolEntity> persistedEvents,
        int processedBlockCount
) {

    public LlmEventExtractorResult {
        normalizedEvents = normalizedEvents == null ? List.of() : List.copyOf(normalizedEvents);
        persistedEvents = persistedEvents == null ? List.of() : List.copyOf(persistedEvents);
    }
}
