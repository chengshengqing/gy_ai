package com.zzhy.yg_ai.domain.normalize.validation;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.util.StringUtils;

public record NormalizeValidatedResult(boolean success,
                                       JsonNode outputNode,
                                       List<NormalizeAttemptReport> attempts,
                                       String failureReason) {

    public Map<String, Object> toReport() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", success);
        result.put("attempt_count", attempts.size());
        result.put("failure_reason", StringUtils.hasText(failureReason) ? failureReason : "");
        List<Map<String, Object>> attemptMaps = new ArrayList<>();
        for (NormalizeAttemptReport attempt : attempts) {
            attemptMaps.add(attempt.toMap());
        }
        result.put("attempts", attemptMaps);
        return result;
    }
}
