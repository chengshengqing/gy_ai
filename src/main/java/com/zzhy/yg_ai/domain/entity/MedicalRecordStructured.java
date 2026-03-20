package com.zzhy.yg_ai.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 结构化病历实体
 */
@Data
@TableName(value = "medical_record_structured", autoResultMap = true)
public class MedicalRecordStructured implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId
    private Long id;

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
     * 结构化内容（JSON）
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Object content;

    /**
     * 病程记录时间
     */
    private String courseTime;

    /**
     * 症状/病史
     */
    private String symptoms;

    /**
     * 体征与检查结果
     */
    private String signsAndExaminations;

    /**
     * 医生评估与诊断
     */
    private String doctorAssessment;

    /**
     * 手术/操作记录
     */
    private String surgeryRecords;

    /**
     * 治疗与处理计划
     */
    private String treatmentPlan;

    /**
     * 会诊意见
     */
    private String consultationOpinions;

    /**
     * 未分类原文
     */
    private String unclassified;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    public void init() {
        this.createdAt = LocalDateTime.now();
    }
}
