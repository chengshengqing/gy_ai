package com.zzhy.yg_ai.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 医疗事件实体
 */
@Data
@TableName(value = "medical_event", autoResultMap = true)
public class MedicalEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId
    private Long id;

    /**
     * 关联的结构化记录ID
     */
    private Long medicalRecordStructuredId;

    /**
     * 关联的结构化记录表中对应的字段
     */
    private String medicalRecordStructuredColumn;

    /**
     * 原始记录ID
     */
    private Long recordId;

    /**
     * 请求号
     */
    private String reqno;

    /**
     * 患者ID
     */
    private String pathosid;

    /**
     * 事件类型：symptom(症状)、exam(检查)、imaging(影像)、procedure(操作)、surgery(手术)、medication(用药)、transfer(转科)、unclassified(没有有效事件)
     */
    private String eventType;

    /**
     * 事件名称：症状、检查、影像、操作/导管、手术、抗菌药使用、转科等
     */
    private String eventName;

    /**
     * 事件发生时间
     */
    private String eventTimeRaw;

    /**
     * 事件的属性信息，键值对形式存储（JSON）
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Object attributes;

    /**
     * 确定性：true/false
     */
    private String negation;

    /**
     * 原始文本片段
     */
    private String sourceText;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;

    public void init() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void update() {
        this.updatedAt = LocalDateTime.now();
    }
}
