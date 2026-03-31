package com.zzhy.yg_ai.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.zzhy.yg_ai.common.DateTimeUtils;
import com.zzhy.yg_ai.domain.enums.InfectionEventStatus;
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
    /**
     * 患者 reqno。
     */
    private String reqno;
    /**
     * 来源 patient_raw_data.id。
     */
    private Long rawDataId;
    /**
     * 快照日期。
     */
    private LocalDate dataDate;
    /**
     * 来源层枚举：raw/mid/summary/manual_patch。
     */
    private String sourceType;
    /**
     * 原始来源路径或引用。
     */
    private String sourceRef;
    /**
     * 事件幂等键。
     */
    private String eventKey;
    /**
     * 一级事件类型。
     */
    private String eventType;
    /**
     * 二级事件类型。
     */
    private String eventSubtype;
    /**
     * 事件分类，当前保留扩展。
     */
    private String eventCategory;
    /**
     * 事件时间。
     */
    private LocalDateTime eventTime;
    /**
     * 检测入池时间。
     */
    private LocalDateTime detectedTime;
    /**
     * 入库时间。
     */
    private LocalDateTime ingestTime;
    /**
     * 感染部位。
     */
    private String site;
    /**
     * 极性。
     */
    private String polarity;
    /**
     * 确定性。
     */
    private String certainty;
    /**
     * 严重程度。
     */
    private String severity;
    /**
     * 是否硬事实。
     */
    private Boolean isHardFact;
    /**
     * 是否当前有效。
     */
    private Boolean isActive;
    /**
     * 标题摘要。
     */
    private String title;
    /**
     * 主内容。
     */
    private String content;
    /**
     * 证据 JSON。
     */
    private String evidenceJson;
    /**
     * 扩展属性 JSON。
     */
    private String attributesJson;
    /**
     * 事件提取器来源。
     */
    private String extractorType;
    /**
     * Prompt 版本。
     */
    private String promptVersion;
    /**
     * 模型名称。
     */
    private String modelName;
    /**
     * 模型或规则置信度。
     */
    private BigDecimal confidence;
    /**
     * active/revoked/superseded/invalid。
     */
    private String status;
    /**
     * 创建时间。
     */
    private LocalDateTime createdAt;
    /**
     * 更新时间。
     */
    private LocalDateTime updatedAt;

    public void initForCreate() {
        LocalDateTime now = DateTimeUtils.now();
        this.status = this.status == null ? InfectionEventStatus.ACTIVE.code() : this.status;
        this.isHardFact = this.isHardFact == null ? Boolean.FALSE : this.isHardFact;
        this.isActive = this.isActive == null ? Boolean.TRUE : this.isActive;
        this.detectedTime = this.detectedTime == null ? now : this.detectedTime;
        this.ingestTime = this.ingestTime == null ? now : this.ingestTime;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void touch() {
        this.updatedAt = DateTimeUtils.now();
    }
}
