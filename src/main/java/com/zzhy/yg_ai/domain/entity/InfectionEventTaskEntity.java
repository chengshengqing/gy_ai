package com.zzhy.yg_ai.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.zzhy.yg_ai.common.DateTimeUtils;
import com.zzhy.yg_ai.domain.enums.InfectionEventTaskStatus;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("infection_event_task")
public class InfectionEventTaskEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;
    private String taskType;
    private String status;
    private String reqno;
    private Long patientRawDataId;
    private LocalDate dataDate;
    private LocalDateTime rawDataLastTime;
    private LocalDateTime sourceBatchTime;
    private String changedTypes;
    private String triggerReasonCodes;
    private Integer priority;
    private String mergeKey;
    private LocalDateTime firstTriggeredAt;
    private LocalDateTime lastEventAt;
    private LocalDateTime debounceUntil;
    private String triggerPriority;
    private Long eventPoolVersionAtEnqueue;
    private Integer attemptCount;
    private Integer maxAttempts;
    private LocalDateTime availableAt;
    private LocalDateTime lastStartTime;
    private LocalDateTime lastFinishTime;
    private String lastErrorMessage;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public void initForCreate(int maxAttempts) {
        LocalDateTime now = DateTimeUtils.now();
        this.status = InfectionEventTaskStatus.PENDING.name();
        this.attemptCount = 0;
        this.maxAttempts = maxAttempts;
        this.priority = this.priority == null ? 100 : this.priority;
        this.availableAt = now;
        this.firstTriggeredAt = this.firstTriggeredAt == null ? now : this.firstTriggeredAt;
        this.lastEventAt = this.lastEventAt == null ? now : this.lastEventAt;
        this.debounceUntil = this.debounceUntil == null ? now : this.debounceUntil;
        this.createTime = now;
        this.updateTime = now;
    }
}
