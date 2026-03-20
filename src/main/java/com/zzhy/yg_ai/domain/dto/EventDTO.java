package com.zzhy.yg_ai.domain.dto;

import com.zzhy.yg_ai.domain.base.BaseDTO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 事件 DTO
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class EventDTO extends BaseDTO {

    private static final long serialVersionUID = 1L;

    /**
     * 事件 ID
     */
    private Long eventId;

    /**
     * 事件名称
     */
    private String eventName;

    /**
     * 事件类型
     */
    private String eventType;

    /**
     * 事件描述
     */
    private String description;
}
