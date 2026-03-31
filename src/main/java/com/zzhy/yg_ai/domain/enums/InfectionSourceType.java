package com.zzhy.yg_ai.domain.enums;

import java.util.Arrays;

public enum InfectionSourceType {
    RAW("raw", "原始结构化层"),
    MID("mid", "中间语义层"),
    SUMMARY("summary", "时间轴摘要层"),
    MANUAL_PATCH("manual_patch", "人工修补");

    private final String code;
    private final String description;

    InfectionSourceType(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String code() {
        return code;
    }

    public String description() {
        return description;
    }

    public static InfectionSourceType fromCode(String code) {
        return Arrays.stream(values())
                .filter(item -> item.code.equalsIgnoreCase(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported InfectionSourceType code: " + code));
    }
}
