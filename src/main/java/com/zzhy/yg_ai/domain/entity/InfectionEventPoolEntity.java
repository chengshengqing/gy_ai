package com.zzhy.yg_ai.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 院感标准化事件池。
 * 当前阶段先提供数据骨架，不接入现有主流程写入。
 */
@Data
@TableName("infection_event_pool")
public class InfectionEventPoolEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;
    private String reqno;
    private Long rawDataId;
    private LocalDate dataDate;
    private String sourceType;
    private String sourceRef;
    private String eventKey;
    private String eventType;
    private String eventSubtype;
    private String eventCategory;
    private LocalDateTime eventTime;
    private LocalDateTime detectedTime;
    private LocalDateTime ingestTime;
    private String site;
    private String polarity;
    private String certainty;
    private String severity;
    private Boolean isHardFact;
    private Boolean isActive;
    private String title;
    private String content;
    private String evidenceJson;
    private String attributesJson;
    private String extractorType;
    private String promptVersion;
    private String modelName;
    private BigDecimal confidence;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public void initForCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.detectedTime = this.detectedTime == null ? now : this.detectedTime;
        this.ingestTime = this.ingestTime == null ? now : this.ingestTime;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void touch() {
        this.updatedAt = LocalDateTime.now();
    }
}
