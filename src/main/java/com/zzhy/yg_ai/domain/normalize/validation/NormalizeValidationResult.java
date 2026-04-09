package com.zzhy.yg_ai.domain.normalize.validation;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record NormalizeValidationResult(
        boolean valid,
        JsonNode parsedNode,
        List<NormalizeValidationIssue> issues
) {
}
