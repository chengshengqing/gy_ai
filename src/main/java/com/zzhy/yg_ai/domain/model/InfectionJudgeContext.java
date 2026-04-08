package com.zzhy.yg_ai.domain.model;

import java.util.List;
import lombok.Builder;

@Builder
public record InfectionJudgeContext(
        List<String> recentOperations,
        List<String> recentDevices,
        List<String> recentAntibiotics,
        List<String> majorSites
) {
    public InfectionJudgeContext {
        recentOperations = recentOperations == null ? List.of() : List.copyOf(recentOperations);
        recentDevices = recentDevices == null ? List.of() : List.copyOf(recentDevices);
        recentAntibiotics = recentAntibiotics == null ? List.of() : List.copyOf(recentAntibiotics);
        majorSites = majorSites == null ? List.of() : List.copyOf(majorSites);
    }
}
