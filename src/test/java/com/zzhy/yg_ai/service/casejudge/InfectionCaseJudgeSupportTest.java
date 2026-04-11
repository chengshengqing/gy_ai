package com.zzhy.yg_ai.service.casejudge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzhy.yg_ai.domain.enums.InfectionBodySite;
import com.zzhy.yg_ai.domain.model.InfectionEvidencePacket;
import com.zzhy.yg_ai.domain.model.InfectionJudgePrecompute;
import com.zzhy.yg_ai.domain.model.JudgeDecisionResult;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class InfectionCaseJudgeSupportTest {

    private final InfectionCaseJudgeSupport support = new InfectionCaseJudgeSupport(new ObjectMapper());

    @Test
    void parseDecisionAcceptsBodySiteEnumCodes() throws Exception {
        InfectionEvidencePacket packet = InfectionEvidencePacket.builder()
                .snapshotVersion(0)
                .precomputed(InfectionJudgePrecompute.builder()
                        .newOnsetFlag(Boolean.TRUE)
                        .after48hFlag("false")
                        .procedureRelatedFlag(Boolean.FALSE)
                        .deviceRelatedFlag(Boolean.FALSE)
                        .build())
                .build();
        String rawOutput = """
                {
                  "decisionStatus": "candidate",
                  "warningLevel": "medium",
                  "primarySite": "lower_respiratory",
                  "nosocomialLikelihood": "low",
                  "infectionPolarity": "support",
                  "decisionReason": ["影像学明确提示双肺上叶炎症。", "C反应蛋白升高支持感染活动。"],
                  "newSupportingKeys": [],
                  "newAgainstKeys": [],
                  "newRiskKeys": [],
                  "dismissedKeys": [],
                  "requiresFollowUp": true,
                  "nextSuggestedJudgeAt": "2026-02-27 10:00:00.000",
                  "resultVersion": 1
                }
                """;

        JudgeDecisionResult result = support.parseDecision(rawOutput, packet, LocalDateTime.of(2026, 2, 27, 9, 50));

        assertEquals(InfectionBodySite.LOWER_RESPIRATORY.code(), result.primarySite());
        assertEquals(2, result.decisionReason().size());
        assertEquals(LocalDateTime.of(2026, 2, 27, 10, 0), result.nextSuggestedJudgeAt());
    }
}
