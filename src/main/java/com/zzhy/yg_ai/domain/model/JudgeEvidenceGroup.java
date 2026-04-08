package com.zzhy.yg_ai.domain.model;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Builder;

@Builder
public record JudgeEvidenceGroup(
        String groupId,
        String eventType,
        String eventSubtype,
        String bodySite,
        String evidenceRole,
        String clinicalMeaning,
        LocalDateTime earliestTime,
        LocalDateTime latestTime,
        String representativeEventId,
        String representativeEventKey,
        List<String> memberEventIds,
        List<String> sourceKinds,
        String maxEvidenceTier,
        Boolean isNew,
        String summaryText
) {

    public JudgeEvidenceGroup {
        memberEventIds = memberEventIds == null ? List.of() : List.copyOf(memberEventIds);
        sourceKinds = sourceKinds == null ? List.of() : List.copyOf(sourceKinds);
    }
}
