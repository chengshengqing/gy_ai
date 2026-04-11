package com.zzhy.yg_ai.domain.normalize.assemble;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;

public record NormalizeContext(
        String reqno,
        LocalDate dataDate,
        JsonNode rawRoot,
        boolean hasRawInput
) {
}
