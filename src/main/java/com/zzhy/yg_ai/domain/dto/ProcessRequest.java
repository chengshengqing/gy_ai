package com.zzhy.yg_ai.domain.dto;

import com.zzhy.yg_ai.domain.base.BaseRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 处理请求
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ProcessRequest extends BaseRequest {

    private static final long serialVersionUID = 1L;

    /**
     * 患者 ID
     */
    private Long patientId;

    /**
     * 患者姓名
     */
    private String patientName;

    /**
     * 患者年龄
     */
    private Integer patientAge;

    /**
     * 症状描述
     */
    private String symptoms;

    /**
     * 诊断类型
     */
    private String diagnosisType;

    @Override
    public boolean validate() {
        if (this.patientId == null || this.patientId <= 0) {
            return false;
        }
        if (this.symptoms == null || this.symptoms.trim().isEmpty()) {
            return false;
        }
        return true;
    }

    @Override
    public String getValidateMessage() {
        if (this.patientId == null || this.patientId <= 0) {
            return "患者 ID 不能为空";
        }
        if (this.symptoms == null || this.symptoms.trim().isEmpty()) {
            return "症状描述不能为空";
        }
        return super.getValidateMessage();
    }
}
