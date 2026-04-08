package com.zzhy.yg_ai.domain.model;

import java.util.List;
import java.util.Map;
import lombok.Builder;

@Builder
public record JudgeBackgroundSummary(
        Integer backgroundCount,
        Map<String, Integer> backgroundByType,
        List<String> backgroundExamples
) {

    public JudgeBackgroundSummary {
        backgroundByType = backgroundByType == null ? Map.of() : Map.copyOf(backgroundByType);
        backgroundExamples = backgroundExamples == null ? List.of() : List.copyOf(backgroundExamples);
    }
}
