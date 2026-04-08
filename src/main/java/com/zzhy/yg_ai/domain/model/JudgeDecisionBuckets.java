package com.zzhy.yg_ai.domain.model;

import java.util.List;
import lombok.Builder;

@Builder
public record JudgeDecisionBuckets(
        List<String> newGroupIds,
        List<String> supportGroupIds,
        List<String> againstGroupIds,
        List<String> riskGroupIds
) {

    public JudgeDecisionBuckets {
        newGroupIds = newGroupIds == null ? List.of() : List.copyOf(newGroupIds);
        supportGroupIds = supportGroupIds == null ? List.of() : List.copyOf(supportGroupIds);
        againstGroupIds = againstGroupIds == null ? List.of() : List.copyOf(againstGroupIds);
        riskGroupIds = riskGroupIds == null ? List.of() : List.copyOf(riskGroupIds);
    }
}
