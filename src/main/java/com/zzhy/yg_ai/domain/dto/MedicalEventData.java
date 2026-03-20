package com.zzhy.yg_ai.domain.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.io.Serializable;
import java.util.Map;

/**
 * 医疗事件数据 DTO
 * 用于接收 AI 返回的事件抽取结果
 */
@Data
public class MedicalEventData implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 事件类型：symptom(症状)、exam(检查)、imaging(影像)、procedure(操作)、surgery(手术)、medication(用药)、transfer(转科)
     */
    private String eventType;

    /**
     * 事件名称：症状、检查、影像、操作/导管、手术、抗菌药使用、转科等
     */
    private String eventName;

    /**
     * 事件发生时间（原始文本中的时间表达）
     */
    private String eventTimeRaw;

    /**
     * 事件的属性信息，键值对形式存储
     */
    private Map<String, Object> attributes;

    /**
     * 确定性：true/false
     */
    private Boolean negation;

    /**
     * 原始文本片段
     */
    private String sourceText;
}
