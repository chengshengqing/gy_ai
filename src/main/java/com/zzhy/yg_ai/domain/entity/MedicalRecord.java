package com.zzhy.yg_ai.domain.entity;

import com.zzhy.yg_ai.domain.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 病历实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class MedicalRecord extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /**
     * 病历号
     */
    private String recordNumber;

    /**
     * 患者 ID
     */
    private Long patientId;

    /**
     * 患者姓名
     */
    private String patientName;

    /**
     * 患者性别 (0-未知，1-男，2-女)
     */
    private Integer gender;

    /**
     * 患者年龄
     */
    private Integer age;

    /**
     * 身份证号
     */
    private String idCard;

    /**
     * 联系电话
     */
    private String phone;

    /**
     * 主诉
     */
    private String chiefComplaint;

    /**
     * 现病史
     */
    private String presentIllness;

    /**
     * 既往史
     */
    private String pastHistory;

    /**
     * 个人史
     */
    private String personalHistory;

    /**
     * 家族史
     */
    private String familyHistory;

    /**
     * 体格检查
     */
    private String physicalExamination;

    /**
     * 辅助检查
     */
    private String auxiliaryExamination;

    /**
     * 初步诊断
     */
    private String preliminaryDiagnosis;

    /**
     * 确诊诊断
     */
    private String finalDiagnosis;

    /**
     * 治疗方案
     */
    private String treatmentPlan;

    /**
     * 医嘱
     */
    private String medicalAdvice;

    /**
     * 就诊类型 (0-初诊，1-复诊)
     */
    private Integer visitType;

    /**
     * 就诊时间
     */
    private LocalDateTime visitTime;

    /**
     * 就诊科室 ID
     */
    private Long departmentId;

    /**
     * 就诊科室名称
     */
    private String departmentName;

    /**
     * 接诊医生 ID
     */
    private Long doctorId;

    /**
     * 接诊医生姓名
     */
    private String doctorName;

    /**
     * 病历状态 (0-草稿，1-已完成，2-已归档)
     */
    private Integer status;
}
