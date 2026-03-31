package com.zzhy.yg_ai.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * EventNormalizer 标准化后的统一事件对象。
 */
@Data
public class NormalizedInfectionEvent {

    private String reqno;
    private Long rawDataId;
    private LocalDate dataDate;
    private String sourceType;
    private String sourceRef;
    private String eventKey;
    private String eventType;
    private String eventSubtype;
    private String eventCategory;
    private LocalDateTime eventTime;
    private String site;
    private String polarity;
    private String certainty;
    private String severity;
    private Boolean isHardFact;
    private Boolean isActive;
    private String title;
    private String content;
    private String evidenceJson;
    private String attributesJson;
    private String extractorType;
    private String promptVersion;
    private String modelName;
    private BigDecimal confidence;
    private String status;
}
