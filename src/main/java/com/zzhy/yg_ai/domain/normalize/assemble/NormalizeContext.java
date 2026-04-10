package com.zzhy.yg_ai.domain.normalize.assemble;

import com.fasterxml.jackson.databind.JsonNode;
import com.zzhy.yg_ai.domain.entity.PatientRawDataEntity;

public record NormalizeContext(
        PatientRawDataEntity rawData,
        String rawInputJson,
        JsonNode rawRoot
) {
}
