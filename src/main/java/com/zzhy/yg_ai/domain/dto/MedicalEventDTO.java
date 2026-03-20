package com.zzhy.yg_ai.domain.dto;

import com.zzhy.yg_ai.domain.base.BaseRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 医疗事件查询 DTO
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class MedicalEventDTO extends BaseRequest {

    private static final long serialVersionUID = 1L;

    /**
     * 关联的结构化记录ID
     */
    private Long medicalRecordStructuredId;

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
     * 事件类型：symptom、exam、imaging、procedure、surgery、medication、transfer
     */
    private String eventType;

    /**
     * 事件名称
     */
    private String eventName;

    @Override
    public boolean validate() {
        return true;
    }

    @Override
    public String getValidateMessage() {
        return super.getValidateMessage();
    }
}
