package com.zzhy.yg_ai.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.zzhy.yg_ai.common.DateTimeUtils;
import com.zzhy.yg_ai.domain.enums.PatientRawDataChangeTaskStatus;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("patient_raw_data_change_task")
public class PatientRawDataChangeTaskEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long patientRawDataId;
    private String reqno;
    private LocalDate dataDate;
    private String status;
    private Integer attemptCount;
    private Integer maxAttempts;
    private LocalDateTime rawDataLastTime;
    private LocalDateTime sourceBatchTime;
    private LocalDateTime availableAt;
    private LocalDateTime lastStartTime;
    private LocalDateTime lastFinishTime;
    private String lastErrorMessage;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public void initForCreate(int maxAttempts) {
        LocalDateTime now = DateTimeUtils.now();
        this.status = PatientRawDataChangeTaskStatus.STRUCT_PENDING.name();
        this.attemptCount = 0;
        this.maxAttempts = maxAttempts;
        this.availableAt = now;
        this.createTime = now;
        this.updateTime = now;
    }
}
