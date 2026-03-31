package com.zzhy.yg_ai.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.zzhy.yg_ai.common.DateTimeUtils;
import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("patient_raw_data_collect_task")
public class PatientRawDataCollectTaskEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;
    private String reqno;
    private String status;
    private Integer attemptCount;
    private Integer maxAttempts;
    private LocalDateTime sourceLastTime;
    private String changeTypes;
    private LocalDateTime availableAt;
    private LocalDateTime lastStartTime;
    private LocalDateTime lastFinishTime;
    private String lastErrorMessage;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public void initForCreate(int maxAttempts) {
        LocalDateTime now = DateTimeUtils.now();
        this.status = "PENDING";
        this.attemptCount = 0;
        this.maxAttempts = maxAttempts;
        this.availableAt = now;
        this.createTime = now;
        this.updateTime = now;
    }
}
