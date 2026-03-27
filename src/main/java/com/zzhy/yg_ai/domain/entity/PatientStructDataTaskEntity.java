package com.zzhy.yg_ai.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("patient_struct_data_task")
public class PatientStructDataTaskEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;
    private String reqno;
    private String status;
    private Integer attemptCount;
    private Integer maxAttempts;
    private LocalDateTime latestRawDataTime;
    private LocalDate replayFromDate;
    private LocalDateTime availableAt;
    private LocalDateTime lastStartTime;
    private LocalDateTime lastFinishTime;
    private String lastErrorMessage;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public void initForCreate(int maxAttempts) {
        LocalDateTime now = LocalDateTime.now();
        this.status = "PENDING";
        this.attemptCount = 0;
        this.maxAttempts = maxAttempts;
        this.availableAt = now;
        this.createTime = now;
        this.updateTime = now;
    }
}
