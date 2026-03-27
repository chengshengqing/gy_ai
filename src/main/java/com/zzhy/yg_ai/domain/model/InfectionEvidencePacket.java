package com.zzhy.yg_ai.domain.model;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * 院感法官节点统一证据包。
 * 后续由事件池、病例快照和 summary 上下文共同组装。
 */
@Data
public class InfectionEvidencePacket {

    private String reqno;
    private String activeEventsJson;
    private String newEventsJson;
    private String excludedEventsJson;
    private String timelineSummaryJson;
    private String exposureSummaryJson;
    private String inspectionSummaryJson;
    private LocalDateTime generatedAt;
}
