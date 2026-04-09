package com.zzhy.yg_ai.domain.normalize.validation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record NormalizeAttemptReport(int attempt, boolean valid, List<NormalizeValidationIssue> issues) {

    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("attempt", attempt);
        result.put("valid", valid);
        List<Map<String, Object>> issueMaps = new ArrayList<>();
        for (NormalizeValidationIssue issue : issues) {
            issueMaps.add(issue.toMap());
        }
        result.put("issues", issueMaps);
        return result;
    }
}
