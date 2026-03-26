package com.zzhy.yg_ai.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 患者原始数据实体
 */
@TableName("patient_raw_data")
@Data
public class PatientRawDataEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    /**
     * 患者请求号
     */
    private String reqno;

    /**
     * 时间轴 时间
     */
    private LocalDate dataDate;

    /**
     * 病程类型[]
     */

    private String clinicalNotes;
    /**
     * 原始数据JSON
     */
    private String dataJson;
    /**
     * 算法去重后的json
     */
    private String filterDataJson;
    /**
     * LLM格式化数据JSON
     */
    private String structDataJson;
    /**
     * LLM提取的事件JSON
     */
    private String eventJson;
    /**
     * 创建时间
     */
    private LocalDateTime createTime;
    /**
     * 最后时间
     */
    private LocalDateTime lastTime;

}
