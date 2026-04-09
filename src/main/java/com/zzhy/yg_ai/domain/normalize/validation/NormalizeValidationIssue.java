package com.zzhy.yg_ai.domain.normalize.validation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record NormalizeValidationIssue(
        String jsonPath,
        String invalidValue,
        List<String> allowedValues,
        String reason
) {

    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("json_path", jsonPath);
        result.put("invalid_value", invalidValue);
        result.put("allowed_values", allowedValues);
        result.put("reason", reason);
        return result;
    }
}
