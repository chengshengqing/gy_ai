package com.zzhy.yg_ai.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.zzhy.yg_ai.common.DateTimeUtils;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("infection_daily_job_log")
public class InfectionDailyJobLogEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;
    private LocalDate jobDate;
    private String reqno;
    private String stage;
    private String status;
    private String message;
    private LocalDateTime createTime;

    public void init() {
        this.jobDate = this.jobDate == null ? DateTimeUtils.today() : this.jobDate;
        this.createTime = DateTimeUtils.now();
    }
}
