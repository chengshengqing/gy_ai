package com.zzhy.yg_ai.domain.model;

import java.time.LocalDateTime;
import lombok.Builder;

@Builder
public record JudgeCatalogEvent(
        String eventId,
        String eventKey,
        Boolean isNew,
        LocalDateTime eventTime,
        String eventType,
        String eventSubtype,
        String bodySite,
        String eventName,
        String clinicalMeaning,
        String evidenceTier,
        String evidenceRole,
        String sourceKind,
        String summaryText
) {
}
