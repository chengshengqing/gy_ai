package com.zzhy.yg_ai.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.zzhy.yg_ai.common.DateTimeUtils;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("infection_alert_result")
public class InfectionAlertResultEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;
    private String reqno;
    private LocalDate dataDate;
    private Integer resultVersion;
    private String alertStatus;
    private String overallRiskLevel;
    private String primarySite;
    private Boolean newOnsetFlag;
    @TableField("after_48h_flag")
    private String after48hFlag;
    private Boolean procedureRelatedFlag;
    private Boolean deviceRelatedFlag;
    private String infectionPolarity;
    private String resultJson;
    private String diffJson;
    private Long sourceSnapshotId;
    private LocalDateTime createTime;

    public void initForCreate() {
        this.createTime = this.createTime == null ? DateTimeUtils.now() : this.createTime;
    }
}
