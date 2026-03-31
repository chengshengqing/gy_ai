package com.zzhy.yg_ai.domain.model;

import com.zzhy.yg_ai.domain.enums.EvidenceBlockType;
import com.zzhy.yg_ai.domain.enums.InfectionSourceType;
import java.time.LocalDate;

public record EvidenceBlock(
        String blockKey,
        String reqno,
        Long rawDataId,
        LocalDate dataDate,
        EvidenceBlockType blockType,
        InfectionSourceType sourceType,
        String sourceRef,
        String title,
        String payloadJson,
        boolean contextOnly
) {
}
