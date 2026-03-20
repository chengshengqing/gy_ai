package com.zzhy.yg_ai.domain.dto;

import com.zzhy.yg_ai.domain.base.BaseRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 结构化病历查询 DTO
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class MedicalRecordStructuredDTO extends BaseRequest {

    private static final long serialVersionUID = 1L;

    /**
     * 原始记录ID
     */
    private Long recordId;

    /**
     * 请求号
     */
    private String reqno;

    /**
     * 患者 ID
     */
    private String pathosid;
    
    /**
     * 病程记录时间
     */
    private String courseTime;
    
    @Override
    public boolean validate() {
        return true;
    }

    @Override
    public String getValidateMessage() {
        return super.getValidateMessage();
    }
}
