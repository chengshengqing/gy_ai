package com.zzhy.yg_ai.domain.model;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Builder;

/**
 * 院感法官节点统一证据包。
 * 仅承载法官裁决需要的证据，不再直接回读原始大 JSON。
 */
@Builder
public record InfectionEvidencePacket(
        String reqno,
        LocalDateTime anchorTime,
        Integer packetVersion,
        Integer snapshotVersion,
        Long eventPoolVersion,
        String caseState,
        InfectionRecentChanges recentChanges,
        List<JudgeCatalogEvent> eventCatalog,
        List<JudgeEvidenceGroup> evidenceGroups,
        JudgeDecisionBuckets decisionBuckets,
        JudgeBackgroundSummary backgroundSummary,
        InfectionJudgeContext judgeContext,
        InfectionJudgePrecompute precomputed
) {

    public InfectionEvidencePacket {
        eventCatalog = eventCatalog == null ? List.of() : List.copyOf(eventCatalog);
        evidenceGroups = evidenceGroups == null ? List.of() : List.copyOf(evidenceGroups);
    }
}
