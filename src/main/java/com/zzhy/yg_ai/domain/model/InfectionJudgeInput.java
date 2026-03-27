package com.zzhy.yg_ai.domain.model;

import com.zzhy.yg_ai.domain.enums.InfectionNodeType;
import java.time.LocalDate;
import lombok.Data;

/**
 * 院感法官节点标准输入契约。
 */
@Data
public class InfectionJudgeInput {

    private String reqno;
    private LocalDate dataDate;
    private InfectionNodeType nodeType;
    private String promptVersion;
    private String modelName;
    private Long latestRawDataId;
    private Long snapshotId;
    private Long latestAlertResultId;
    private InfectionEvidencePacket evidencePacket;
}
