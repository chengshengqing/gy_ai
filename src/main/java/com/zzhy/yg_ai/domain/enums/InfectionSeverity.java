package com.zzhy.yg_ai.domain.enums;

import java.util.Arrays;

public enum InfectionSeverity {
    MILD("mild", "轻度"),
    MODERATE("moderate", "中度"),
    SEVERE("severe", "重度"),
    CRITICAL("critical", "危重"),
    UNCLEAR("unclear", "未明");

    private final String code;
    private final String description;

    InfectionSeverity(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String code() {
        return code;
    }

    public String description() {
        return description;
    }

    public static InfectionSeverity fromCode(String code) {
        return Arrays.stream(values())
                .filter(item -> item.code.equalsIgnoreCase(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported InfectionSeverity code: " + code));
    }
}
