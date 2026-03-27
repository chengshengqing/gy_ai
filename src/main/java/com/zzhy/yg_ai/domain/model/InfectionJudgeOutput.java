package com.zzhy.yg_ai.domain.model;

import com.zzhy.yg_ai.domain.enums.InfectionNodeRunStatus;
import com.zzhy.yg_ai.domain.enums.InfectionNodeType;
import com.zzhy.yg_ai.domain.enums.InfectionRiskLevel;
import java.math.BigDecimal;
import lombok.Data;

/**
 * 院感法官节点标准输出契约。
 */
@Data
public class InfectionJudgeOutput {

    private InfectionNodeType nodeType;
    private InfectionNodeRunStatus status;
    private InfectionRiskLevel riskLevel;
    private String suspectedSite;
    private BigDecimal confidence;
    private String summary;
    private String supportingEvidenceJson;
    private String excludingEvidenceJson;
    private String attributesJson;
}
