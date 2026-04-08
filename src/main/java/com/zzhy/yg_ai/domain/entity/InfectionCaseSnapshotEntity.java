package com.zzhy.yg_ai.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.zzhy.yg_ai.common.DateTimeUtils;
import com.zzhy.yg_ai.domain.enums.InfectionBodySite;
import com.zzhy.yg_ai.domain.enums.InfectionCaseState;
import com.zzhy.yg_ai.domain.enums.InfectionNosocomialLikelihood;
import com.zzhy.yg_ai.domain.enums.InfectionWarningLevel;
import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("infection_case_snapshot")
public class InfectionCaseSnapshotEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;
    private String reqno;
    private String caseState;
    private String warningLevel;
    private String primarySite;
    private String nosocomialLikelihood;
    private Boolean currentNewOnsetFlag;

    @TableField("current_after_48h_flag")
    private String currentAfter48hFlag;

    private Boolean currentProcedureRelatedFlag;
    private Boolean currentDeviceRelatedFlag;
    private String currentInfectionPolarity;
    private String activeEventKeysJson;
    private String activeRiskKeysJson;
    private String activeAgainstKeysJson;
    private LocalDateTime lastJudgeTime;
    private Integer lastResultVersion;
    private Long lastEventPoolVersion;
    private LocalDateTime lastCandidateSince;
    private LocalDateTime lastWarningSince;
    private LocalDateTime judgeDebounceUntil;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public void initForCreate() {
        LocalDateTime now = DateTimeUtils.now();
        this.caseState = this.caseState == null ? InfectionCaseState.NO_RISK.code() : this.caseState;
        this.warningLevel = this.warningLevel == null ? InfectionWarningLevel.NONE.code() : this.warningLevel;
        this.primarySite = this.primarySite == null ? InfectionBodySite.UNKNOWN.code() : this.primarySite;
        this.nosocomialLikelihood = this.nosocomialLikelihood == null ? InfectionNosocomialLikelihood.LOW.code() : this.nosocomialLikelihood;
        this.lastResultVersion = this.lastResultVersion == null ? 0 : this.lastResultVersion;
        this.lastEventPoolVersion = this.lastEventPoolVersion == null ? 0L : this.lastEventPoolVersion;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void touch() {
        this.updatedAt = DateTimeUtils.now();
    }
}
