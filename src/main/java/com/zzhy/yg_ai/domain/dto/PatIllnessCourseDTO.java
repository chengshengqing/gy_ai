package com.zzhy.yg_ai.domain.dto;

import com.zzhy.yg_ai.domain.base.BaseRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 患者病程数据传输对象
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class PatIllnessCourseDTO extends BaseRequest {

    private static final long serialVersionUID = 1L;

    /**
     * 请求号
     */
    private String reqno;

    /**
     * 病原体 ID
     */
    private String pathosid;

    /**
     * 项目名称
     */
    private String itemname;

    /**
     * 创建人
     */
    private String creatperson;

    /**
     * 住院汇总
     */
    private String inhossum;

    /**
     * 文件名
     */
    private String fileName;

    @Override
    public boolean validate() {
        // 可以根据需要添加验证逻辑
        return true;
    }

    @Override
    public String getValidateMessage() {
        return super.getValidateMessage();
    }
}
