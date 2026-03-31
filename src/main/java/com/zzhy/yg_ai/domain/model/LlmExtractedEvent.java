package com.zzhy.yg_ai.domain.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 统一事件抽取器输出的单条事件结构。
 */
@Data
public class LlmExtractedEvent {

    @JsonProperty("event_time")
    private String eventTime;

    @JsonProperty("event_type")
    private String eventType;

    @JsonProperty("event_subtype")
    private String eventSubtype;

    @JsonProperty("body_site")
    private String bodySite;

    @JsonProperty("event_name")
    private String eventName;

    @JsonProperty("event_value")
    private String eventValue;

    @JsonProperty("event_unit")
    private String eventUnit;

    @JsonProperty("abnormal_flag")
    private Boolean abnormalFlag;

    @JsonProperty("infection_related")
    private Boolean infectionRelated;

    @JsonProperty("negation_flag")
    private Boolean negationFlag;

    @JsonProperty("uncertainty_flag")
    private Boolean uncertaintyFlag;

    @JsonProperty("clinical_meaning")
    private String clinicalMeaning;

    @JsonProperty("source_text")
    private String sourceText;
}
