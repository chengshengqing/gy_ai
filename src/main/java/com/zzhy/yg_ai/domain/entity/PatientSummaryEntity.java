package com.zzhy.yg_ai.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 患者摘要实体
 */
@TableName("patient_summary")
@Data
public class PatientSummaryEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    /**
     * 患者请求号
     */
    private String reqno;
    /**
     * 摘要JSON
     */
    private String summaryJson;
    /**
     * 令牌数量
     */
    private Integer tokenCount;
    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

}
