package com.zzhy.yg_ai.domain.model;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Builder;

@Builder
public record JudgeDecisionResult(
        String decisionStatus,
        String warningLevel,
        String primarySite,
        String nosocomialLikelihood,
        Boolean newOnsetFlag,
        String after48hFlag,
        Boolean procedureRelatedFlag,
        Boolean deviceRelatedFlag,
        String infectionPolarity,
        List<String> decisionReason,
        List<String> newSupportingKeys,
        List<String> newAgainstKeys,
        List<String> newRiskKeys,
        List<String> dismissedKeys,
        Boolean requiresFollowUp,
        LocalDateTime nextSuggestedJudgeAt,
        Integer resultVersion
) {
    public JudgeDecisionResult {
        decisionReason = decisionReason == null ? List.of() : List.copyOf(decisionReason);
        newSupportingKeys = newSupportingKeys == null ? List.of() : List.copyOf(newSupportingKeys);
        newAgainstKeys = newAgainstKeys == null ? List.of() : List.copyOf(newAgainstKeys);
        newRiskKeys = newRiskKeys == null ? List.of() : List.copyOf(newRiskKeys);
        dismissedKeys = dismissedKeys == null ? List.of() : List.copyOf(dismissedKeys);
    }
}
