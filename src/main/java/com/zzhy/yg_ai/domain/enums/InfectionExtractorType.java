package com.zzhy.yg_ai.domain.enums;

import java.util.Arrays;

public enum InfectionExtractorType {
    RULE_ENGINE("rule_engine", "规则引擎"),
    LLM_EVENT_EXTRACTOR("llm_event_extractor", "统一事件抽取器"),
    EVENT_NORMALIZER("event_normalizer", "事件规范化器"),
    MANUAL_PATCH("manual_patch", "人工修补");

    private final String code;
    private final String description;

    InfectionExtractorType(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String code() {
        return code;
    }

    public String description() {
        return description;
    }

    public static InfectionExtractorType fromCode(String code) {
        return Arrays.stream(values())
                .filter(item -> item.code.equalsIgnoreCase(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported InfectionExtractorType code: " + code));
    }
}
